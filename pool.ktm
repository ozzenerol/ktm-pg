module pg;
import atomic, coro, date, libc, mem, reflect;

extern "c" void *pthread_self();

/* How a Pool runs. A declared Options is all zero: every 0 means the
 * default in the comment. */
pub struct Options
{
    i64 maxConns;           /* connections open at once, all threads together (0: 4 per CPU, 8 to 64) */
    i64 maxIdleMs;          /* an idle connection is closed after this (0: 30 min) */
    i64 maxLifetimeMs;      /* a connection is closed after this, at its next release (0: 1 hour) */
    i64 acquireTimeoutMs;   /* waiting for a free connection longer throws ERR_POOL (0: 30 s) */
    i64 healthCheckMs;      /* a connection idle this long is pinged before it is handed out (0: 1 s) */
    i64 queryTimeoutMs;     /* every query's limit, see Conn.setQueryTimeout (0: none) */
};

/* The connections of one thread (and one kind: loop coroutines or plain
 * code). A non-blocking socket belongs to the coroutine loop of the
 * thread that opened it, so connections never move between shards.
 *
 * The budget: maxConns for the pool, and each shard's fair share of it,
 * maxConns / shards, its quota. A shard opens connections while the pool
 * has budget left. When it hasn't, a shard under its quota takes an idle
 * connection of a shard over its quota: it ends it there (Terminate and
 * shutdown(2), which touch no loop) and leaves it in that shard's
 * zombies, which its own thread closes the next time it uses the pool.
 * A shard over its quota closes what comes back while others wait.
 * Inside a shard, waiters queue and a connection given back goes to the
 * first one, waking it alone (coro.unpark, no system call). */
struct Shard
{
    void *thread;
    bool loop;
    i64 lock;               /* guards idle, zombies and the queue */
    Conn **idle;            /* a stack: the most recently used on top */
    i64 nidle;              /* (atomic: other threads read it) */
    i64 cap;
    i64 nconns;             /* connections of this shard, idle or in use (atomic) */
    i64 lastUse;            /* date.monoMs() of its last acquire (atomic) */
    i64 xwaiters;           /* waiters that need a connection to themselves (own thread only) */
    Conn **shared;          /* connections in shared use (own thread only) */
    i64 nshared;
    i64 sharedCap;
    Conn **zombies;         /* ended by another thread, to close here */
    i64 nzombies;
    i64 zcap;
    i64 waiters;            /* how many wait in the queue (atomic) */
    Waiter *qhead;
    Waiter *qtail;
};

/* A coroutine (or thread) waiting for a connection */
struct Waiter
{
    Conn *got;              /* handed over by poolPut */
    Waiter *next;
    coro.Coroutine *co;     /* parked in its loop (null for a plain thread: it polls) */
    Shard *shard;
};

/* A pool of connections, safe to share between the threads of
 * coro.serve and net.http. Each call takes a connection and gives it
 * back; Rows and a Tx keep theirs until rows.free(), commit() or
 * rollback(). */
pub struct Pool : Db
{
    private Config cfg;
    private i64 maxConns;
    private i64 maxIdleMs;
    private i64 maxLifetimeMs;
    private i64 acquireMs;
    private i64 healthMs;
    private i64 queryMs;
    private i64 nopen;          /* connections open (atomic) */
    private i64 nwaiting;       /* waiters in every shard (atomic) */
    private Shard **shards;     /* up to MAX_SHARDS; never moved, so reads need no lock */
    private i64 nshards;        /* (atomic) */
    private i64 lock;           /* a spin lock for adding a shard */
    private i64 closed;
};

const i64 MAX_SHARDS = 1024;

/* At most this many calls share one connection */
const i64 PIPE_MAX = 64;

/* A thread with no connection may take another's only one when that
 * thread hasn't used the pool for this long */
const i64 STARVE_MS = 20;

/* The idle connections of a thread that hasn't used the pool for this
 * long may be taken whatever the quotas */
const i64 STEAL_IDLE_MS = 2000;

/* A health check's ping (and a connect) gets at most what is left of
 * acquireTimeoutMs, and the ping at most this; no answer in time and the
 * connection is dropped, no cancel round */
const i64 HEALTH_PING_MS = 1000;

/* ... but never less than this, so a late acquire doesn't drop a healthy connection */
const i64 MIN_LIMIT_MS = 100;

/* What is left until deadline, capped at most, at least MIN_LIMIT_MS */
static i64 limitTo(i64 deadline, i64 most)
{
    i64 left = deadline - date.monoMs();
    if (left > most) { left = most; }
    return left < MIN_LIMIT_MS ? MIN_LIMIT_MS : left;
}

/* A pool for the server the URL names (see pg.connect). It connects
 * when a connection is first needed, so a wrong URL shows at the first
 * query, not here; db.ping() checks at once. Close it with db.close(). */
pub Pool *open(u8[] url = "", Options *opts = null)
{
    Pool *p = mem.allocZero(sizeof(Pool));
    p.cfg = configParse(url);
    p.open = &poolOpen;
    Options o;
    mem.set(&o, 0, sizeof(Options));
    if (opts) { o = *opts; }
    i64 cpus = libc.sysconf(84);    /* _SC_NPROCESSORS_ONLN on Linux */
#ifdef IS_MACOS
    cpus = libc.sysconf(58);
#endif
    i64 dflt = 4 * cpus;
    if (dflt < 8) { dflt = 8; }
    if (dflt > 64) { dflt = 64; }
    p.maxConns = o.maxConns > 0 ? o.maxConns : dflt;
    p.maxIdleMs = o.maxIdleMs > 0 ? o.maxIdleMs : 30 * 60 * 1000;
    p.maxLifetimeMs = o.maxLifetimeMs > 0 ? o.maxLifetimeMs : 60 * 60 * 1000;
    p.acquireMs = o.acquireTimeoutMs > 0 ? o.acquireTimeoutMs : 30000;
    p.healthMs = o.healthCheckMs > 0 ? o.healthCheckMs : 1000;
    p.queryMs = o.queryTimeoutMs > 0 ? o.queryTimeoutMs : 0;
    p.shards = mem.allocZero(sizeof(Shard *) * MAX_SHARDS);
    return p;
}

/* The calling thread's shard (made on first use) */
static Shard *Pool.shard()
{
    void *th = pthread_self();
    bool loop = coro.inLoop();
    i64 n = atomic.load(&self.nshards);
    for (i64 i = 0; i < n; i++) {
        Shard *s = self.shards[i];
        if (s.thread == th && s.loop == loop) { return s; }
    }
    while (!atomic.cas(&self.lock, 0, 1)) { }
    n = atomic.load(&self.nshards);
    if (n == MAX_SHARDS) {
        atomic.store(&self.lock, 0);
        throw(ERR_POOL, "pg: a pool used from more than %d threads", MAX_SHARDS);
    }
    Shard *s = mem.allocZero(sizeof(Shard));
    s.thread = th;
    s.loop = loop;
    self.shards[n] = s;
    atomic.store(&self.nshards, n + 1);
    atomic.store(&self.lock, 0);
    return s;
}

/* A shard's fair share of maxConns */
static i64 Pool.quota()
{
    i64 n = atomic.load(&self.nshards);
    i64 q = self.maxConns / (n ? n : 1);
    return q ? q : 1;
}

static void Shard.lock_()
{
    while (!atomic.cas(&self.lock, 0, 1)) { }
}

static void Shard.unlock_()
{
    atomic.store(&self.lock, 0);
}

/* Close the connections another thread ended for this one */
static void Shard.reap()
{
    if (!atomic.load(&self.nzombies)) { return; }
    self.lock_();
    Conn **list = self.zombies;
    i64 n = self.nzombies;
    self.zombies = null;
    self.zcap = 0;
    atomic.store(&self.nzombies, 0);
    self.unlock_();
    for (i64 i = 0; i < n; i++) { list[i].release_(); }
    mem.free(list);
}

/* ---- waiting ---- */

/* Wake w (from any thread: coro.unpark reaches another thread's loop
 * too). The caller holds the shard's lock or is its thread, so w is
 * still waiting and its coroutine alive. A plain thread's waiter polls. */
static void Shard.wakeWaiter(Waiter *w)
{
    if (w.co) { coro.unpark(w.co); }
}

static void Shard.enqueue(Waiter *w, bool front)
{
    self.lock_();
    if (front) {
        w.next = self.qhead;
        self.qhead = w;
        if (!self.qtail) { self.qtail = w; }
    } else {
        w.next = null;
        if (self.qtail) { self.qtail.next = w; } else { self.qhead = w; }
        self.qtail = w;
    }
    atomic.add(&self.waiters, 1);
    self.unlock_();
}

/* Take w off the queue; false when it wasn't on it */
static bool Shard.dequeue(Waiter *w)
{
    self.lock_();
    Waiter *prev = null;
    for (Waiter *q = self.qhead; q; q = q.next) {
        if (q == w) {
            if (prev) { prev.next = q.next; } else { self.qhead = q.next; }
            if (self.qtail == q) { self.qtail = prev; }
            atomic.sub(&self.waiters, 1);
            self.unlock_();
            return true;
        }
        prev = q;
    }
    self.unlock_();
    return false;
}

/* The first waiter, off the queue (null if none); the lock is held */
static Waiter *Shard.popLocked()
{
    Waiter *w = self.qhead;
    if (!w) { return null; }
    self.qhead = w.next;
    if (!self.qhead) { self.qtail = null; }
    atomic.sub(&self.waiters, 1);
    return w;
}

/* Budget came free: wake the first waiter of a shard under its quota
 * (or of any shard), so it opens a connection */
static void Pool.wakeOne()
{
    if (!atomic.load(&self.nwaiting)) { return; }
    i64 q = self.quota();
    i64 n = atomic.load(&self.nshards);
    Shard *any = null;
    for (i64 i = 0; i < n; i++) {
        Shard *s = self.shards[i];
        if (!atomic.load(&s.waiters)) { continue; }
        if (atomic.load(&s.nconns) < q) { any = s; break; }
        if (!any) { any = s; }
    }
    if (!any) { return; }
    any.lock_();
    if (any.qhead) { any.wakeWaiter(any.qhead); }
    any.unlock_();
}

/* Pause until woken or the deadline: a second at most (a safety net),
 * or just past STARVE_MS for a thread with no connection at all, as
 * another thread's idle one may be taken by then without a wake-up */
static void Pool.wait(Waiter *w, i64 deadline)
{
    i64 left = deadline - date.monoMs();
    if (left <= 0) { return; }
    if (w.co) {
        i64 most = atomic.load(&w.shard.nconns) ? 1000 : STARVE_MS + 5;
        coro.park(left > most ? most : left);
    } else {
        date.sleepMs(1);
    }
}

/* ---- taking and giving back ---- */

/* Close a connection of the pool for good */
static void Pool.discard(Conn *c)
{
    Shard *s = c.shard;
    c.pool = null;
    c.terminate();
    c.release_();
    atomic.sub(&s.nconns, 1);
    atomic.sub(&self.nopen, 1);
    if (atomic.load(&self.closed)) { self.freeIfDone(); return; }
    self.wakeOne();
}

/* Whether an idle connection may be handed out: not too old, alive.
 * The health ping ends by deadline (the acquire's). */
static bool Pool.usable(Conn *c, i64 now, i64 deadline)
{
    if (c.dead || !c.quiet()) { return false; }
    if (now - c.born >= self.maxLifetimeMs || now - c.idleSince >= self.maxIdleMs) { return false; }
    if (now - c.idleSince >= self.healthMs) {
        c.timeoutMs = limitTo(deadline, HEALTH_PING_MS);
        c.dropCode = ERR_IO;
        try { c.ping(); } catch { c.dropCode = 0; return false; }
        c.dropCode = 0;
    }
    return true;
}

/* An idle connection of s that is fit to use, or null */
static Conn *Pool.takeIdle(Shard *s, i64 deadline)
{
    i64 now = date.monoMs();
    while (true) {
        s.lock_();
        if (!s.nidle) { s.unlock_(); return null; }
        Conn *c = s.idle[s.nidle - 1];
        atomic.store(&s.nidle, s.nidle - 1);
        s.unlock_();
        if (self.usable(c, now, deadline)) { return c; }
        self.discard(c);
    }
    return null;
}

/* A new connection for s, if the budget allows: null if it doesn't.
 * Connecting ends by deadline when the URL sets no connect_timeout. */
static Conn *Pool.openNew(Shard *s, i64 deadline)
{
    if (atomic.add(&self.nopen, 1) >= self.maxConns) {
        atomic.sub(&self.nopen, 1);
        return null;
    }
    atomic.add(&s.nconns, 1);
    Conn *c;
    try {
        c = connectConfig(&self.cfg, limitTo(deadline, self.acquireMs));
    } catch (Error e) {
        atomic.sub(&s.nconns, 1);
        atomic.sub(&self.nopen, 1);
        self.wakeOne();
        throw(e);
    }
    c.pool = self;
    c.shard = s;
    return c;
}

/* End an idle connection of another shard so mine may open one: when
 * mine is under its quota and the other over its own, or when the other
 * thread hasn't used the pool for STEAL_IDLE_MS. false when there is
 * none. */
static bool Pool.steal(Shard *mine)
{
    i64 q = self.quota();
    i64 n = atomic.load(&self.nshards);
    for (i64 i = 0; i < n; i++) {
        Shard *o = self.shards[i];
        i64 on = atomic.load(&o.nconns);
        if (o == mine || !atomic.load(&o.nidle)) { continue; }
        i64 have = atomic.load(&mine.nconns);
        /* fair: mine is under its quota and the other over its own, or mine
         * has none and the other has more than one, or one quiet for a moment */
        bool fair = have < q && on > q;
        if (!have && (on > 1 || date.monoMs() - atomic.load(&o.lastUse) >= STARVE_MS)) { fair = true; }
        o.lock_();
        if (!o.nidle) { o.unlock_(); continue; }
        Conn *c = o.idle[0];        /* the least recently used */
        if (!fair && date.monoMs() - atomic.load(&o.lastUse) < STEAL_IDLE_MS) { o.unlock_(); continue; }
        for (i64 j = 1; j < o.nidle; j++) { o.idle[j - 1] = o.idle[j]; }
        atomic.store(&o.nidle, o.nidle - 1);
        u8 bye[5] = {'X', 0, 0, 0, 4};
        libc.write(c.s.fd, bye, 5);
        libc.shutdown(c.s.fd, libc.SHUT_RDWR);
        c.dead = true;
        c.pool = null;
        if (o.nzombies == o.zcap) {
            o.zcap = o.zcap ? o.zcap * 2 : 8;
            o.zombies = mem.realloc(o.zombies, sizeof(Conn *) * o.zcap);
        }
        o.zombies[o.nzombies] = c;
        atomic.store(&o.nzombies, o.nzombies + 1);
        o.unlock_();
        atomic.sub(&o.nconns, 1);
        atomic.sub(&self.nopen, 1);
        return true;
    }
    return false;
}

/* A connection of the pool, all yours until c.release() (or c.close())
 * gives it back. Throws ERR_POOL when none is free within
 * acquireTimeoutMs, and what connecting throws. Waiters are served in
 * the order they came. */
pub Conn *Pool.acquire()
{
    return self.take(true);
}

/* acquire(); own: the caller needs the connection to itself (a
 * transaction, Rows, acquire()), so shared calls stop joining busy
 * connections while it waits */
static Conn *Pool.take(bool own)
{
    if (!self) { dbNull("acquire"); }
    if (atomic.load(&self.closed)) { throw(ERR_POOL, "pg: the pool is closed"); }
    Shard *s = self.shard();
    s.reap();
    atomic.store(&s.lastUse, date.monoMs());
    i64 deadline = date.monoMs() + self.acquireMs;
    Conn *c;
    if (!atomic.load(&s.waiters)) {             /* nobody queued: the fast way */
        c = self.takeIdle(s, deadline);
        if (!c) { c = self.openNew(s, deadline); }
        if (c) {
            c.timeoutMs = self.queryMs;
            return c;
        }
    }
    Waiter w;
    w.got = null;
    w.next = null;
    w.co = s.loop ? coro.current() : null;
    w.shard = s;
    bool queued = false;
    bool first = false;
    if (own) { s.xwaiters++; }
    defer {
        if (queued) { s.dequeue(&w); atomic.sub(&self.nwaiting, 1); }
        if (own) { s.xwaiters--; }
    }
    while (true) {
        if (w.got) {                /* handed over by poolPut, which took us off the queue */
            c = w.got;
            w.got = null;
            if (queued) { queued = false; atomic.sub(&self.nwaiting, 1); }
            if (atomic.load(&self.closed)) { self.discard(c); continue; }
            if (self.usable(c, date.monoMs(), deadline)) {
                c.timeoutMs = self.queryMs;
                return c;
            }
            self.discard(c);
            continue;
        }
        if (atomic.load(&self.closed)) { throw(ERR_POOL, "pg: the pool is closed"); }
        if (!queued || s.qhead == &w) {
            /* off the queue while we try: a ping or a connect pauses this
             * coroutine, and poolPut must not hand us a second connection */
            if (queued) {
                if (s.dequeue(&w)) {
                    queued = false;
                    atomic.sub(&self.nwaiting, 1);
                }
                if (w.got) { continue; }
            }
            if (!queued) {
                s.reap();
                c = self.takeIdle(s, deadline);
                if (!c) { c = self.openNew(s, deadline); }
                if (!c && self.steal(s)) { c = self.openNew(s, deadline); }
                if (c) {
                    c.timeoutMs = self.queryMs;
                    return c;
                }
            }
        }
        if (date.monoMs() >= deadline) {
            throw(ERR_POOL, "pg: no connection free within %d ms (maxConns %d)", self.acquireMs, self.maxConns);
        }
        if (!queued) {
            s.enqueue(&w, first);
            atomic.add(&self.nwaiting, 1);
            queued = true;
            first = true;           /* from now on it keeps its place */
        }
        self.wait(&w, deadline);
    }
    return null;
}

/* Whether s should close a connection coming back, for another shard
 * that waits: s is over its quota and that one under its own, or that
 * one has two fewer. Never between shards one apart, so a steady load
 * settles and reconnects nothing. */
static bool Pool.giveUp(Shard *s)
{
    i64 q = self.quota();
    i64 mine = atomic.load(&s.nconns);
    i64 n = atomic.load(&self.nshards);
    for (i64 i = 0; i < n; i++) {
        Shard *o = self.shards[i];
        if (o == s || !atomic.load(&o.waiters)) { continue; }
        i64 theirs = atomic.load(&o.nconns);
        if ((mine > q && theirs < q) || theirs + 1 < mine) { return true; }
    }
    return false;
}

/* c comes back to its pool */
static void poolPut(void *pool, Conn *c)
{
    Pool *p = pool;
    Shard *s = c.shard;
    i64 now = date.monoMs();
    if (c.dead || c.inUse() || c.txStatus != 'I' || atomic.load(&p.closed) || now - c.born >= p.maxLifetimeMs) {
        p.discard(c);
        return;
    }
    c.idleSince = now;
    if (atomic.load(&s.waiters)) {  /* the first waiter gets it */
        s.lock_();
        Waiter *w = s.popLocked();
        s.unlock_();
        if (w) {
            w.got = c;
            s.wakeWaiter(w);
            return;
        }
    }
    if (atomic.load(&p.nwaiting) && p.giveUp(s)) {
        p.discard(c);               /* over our share while others wait */
        return;
    }
    s.lock_();
    if (s.nidle == s.cap) {
        s.cap = s.cap ? s.cap * 2 : 8;
        s.idle = mem.realloc(s.idle, sizeof(Conn *) * s.cap);
    }
    s.idle[s.nidle] = c;
    atomic.store(&s.nidle, s.nidle + 1);
    s.unlock_();
    s.reap();
}

/* Gives a connection from Pool.acquire back (the same as c.close()) */
pub void Conn.release()
{
    self.close();
}

/* Checks the connection works: a round trip to the server */
pub void Conn.ping()
{
    Req r;
    self.join(&r);
    defer self.leave(&r);
    self.begin('Q');
    self.put8(0);
    self.end();
    self.flush();
    self.turn(&r);
    self.readCount();
}

/* Checks the server can be reached: takes a connection and pings it */
pub void Pool.ping()
{
    Conn *c = self.acquire();
    defer c.release();
    c.ping();
}

/* A connection for one call whose answer is read at once (exec,
 * queryAll, queryOne): an idle one, a new one while the budget allows,
 * else the least busy one of this thread's shared connections, the call
 * pipelined behind the others. Plain threads and pools with a query
 * timeout (a cancel would hit the wrong query) don't share. */
static Conn *Pool.acquireShared()
{
    if (!self) { dbNull("a query"); }
    if (self.queryMs || !coro.inLoop()) { return self.take(false); }
    if (atomic.load(&self.closed)) { throw(ERR_POOL, "pg: the pool is closed"); }
    Shard *s = self.shard();
    s.reap();
    atomic.store(&s.lastUse, date.monoMs());
    Conn *c = null;
    if (!atomic.load(&s.waiters)) {
        i64 deadline = date.monoMs() + self.acquireMs;
        c = self.takeIdle(s, deadline);
        if (!c) { c = self.openNew(s, deadline); }
    }
    if (!c && !s.xwaiters) {
        /* join the least busy shared one. A caller that needs a
         * connection of its own waits: then they must come free, and so
         * must one of ours when another thread has two fewer. */
        Conn *best = null;
        i64 live = 0;
        for (i64 i = 0; i < s.nshared; i++) {
            Conn *x = s.shared[i];
            if (x.dead || x.txStatus != 'I' || x.draining) { continue; }
            live++;
            if (!best || x.users < best.users) { best = x; }
        }
        if (best && live >= 2 && self.giveUp(s)) {
            best.draining = true;
            Conn *next = null;
            for (i64 i = 0; i < s.nshared; i++) {
                Conn *x = s.shared[i];
                if (x.dead || x.txStatus != 'I' || x.draining) { continue; }
                if (!next || x.users < next.users) { next = x; }
            }
            best = next;
        }
        if (best && best.users < PIPE_MAX) {
            best.users++;
            return best;
        }
    }
    if (!c) { c = self.take(false); }
    c.timeoutMs = 0;
    c.users = 1;
    c.draining = false;
    if (s.nshared == s.sharedCap) {
        s.sharedCap = s.sharedCap ? s.sharedCap * 2 : 8;
        s.shared = mem.realloc(s.shared, sizeof(Conn *) * s.sharedCap);
    }
    s.shared[s.nshared++] = c;
    return c;
}

/* A sharing call is done with c */
static void poolUnshare(void *pool, Conn *c)
{
    if (--c.users) { return; }
    Shard *s = c.shard;
    for (i64 i = 0; i < s.nshared; i++) {
        if (s.shared[i] == c) {
            s.shared[i] = s.shared[--s.nshared];
            break;
        }
    }
    poolPut(pool, c);
}

static Rows poolOpen(Db *db, u8[] sql, reflect.Value[] args, bool shared)
{
    Pool *p = db;
    Conn *c = shared ? p.acquireShared() : p.take(true);
    Rows r;
    try {
        r = c.queryArgs(sql, args);
    } catch (Error e) {
        c.release();
        throw(e);
    }
    r.own = true;
    return r;
}

pub i64 Pool.execArgs(u8[] sql, reflect.Value[] args)
{
    Conn *c = self.acquireShared();
    defer c.release();
    return c.execArgs(sql, args);
}

/* Runs sql with the parameters $1, $2 ... on a connection of the pool */
pub i64 Pool.exec(u8[] sql, any ...args)
{
    return self.execArgs(sql, args);
}

pub i64 Pool.execScript(u8[] sql)
{
    Conn *c = self.acquire();
    defer c.release();
    return c.execScript(sql);
}

/* A query on a connection of the pool: the Rows keep it until rows.free() */
pub Rows Pool.queryArgs(u8[] sql, reflect.Value[] args)
{
    return poolOpen(self, sql, args, false);
}

pub Rows Pool.query(u8[] sql, any ...args)
{
    return poolOpen(self, sql, args, false);
}

/* A transaction on a connection of the pool: it keeps it until commit()
 * or rollback() */
pub Tx Pool.begin(u8[] mode = "")
{
    Conn *c = self.acquire();
    Tx tx;
    try {
        tx = c.begin(mode);
    } catch (Error e) {
        c.release();
        throw(e);
    }
    tx.own = true;
    return tx;
}

/* Connections open, and idle ones in all threads */
pub i64 Pool.openCount()
{
    return atomic.load(&self.nopen);
}

pub i64 Pool.idleCount()
{
    i64 n = 0;
    i64 k = atomic.load(&self.nshards);
    for (i64 i = 0; i < k; i++) { n += self.shards[i].nidle; }
    return n;
}

/* Closes the idle connections and frees the pool; connections still in
 * use are closed as they are given back, and the last one frees it */
pub void Pool.close()
{
    atomic.store(&self.closed, 1);
    i64 k = atomic.load(&self.nshards);
    for (i64 i = 0; i < k; i++) {
        Shard *s = self.shards[i];
        s.reap();
        while (true) {
            s.lock_();
            if (!s.nidle) { s.unlock_(); break; }
            Conn *c = s.idle[s.nidle - 1];
            atomic.store(&s.nidle, s.nidle - 1);
            s.unlock_();
            self.discard(c);
        }
    }
    atomic.add(&self.nopen, -1 << 32);      /* nopen < 0 from now on: discard frees at 0 */
    self.freeIfDone();
}

/* After close(): free the pool once no connection is open */
static void Pool.freeIfDone()
{
    if (atomic.load(&self.nopen) != -1 << 32) { return; }
    if (!atomic.cas(&self.nopen, -1 << 32, I64_MIN)) { return; }
    i64 k = atomic.load(&self.nshards);
    for (i64 i = 0; i < k; i++) {
        Shard *s = self.shards[i];
        s.reap();
        mem.free(s.idle);
        mem.free(s.shared);
        mem.free(s);
    }
    mem.free(self.shards);
    self.cfg.free();
    mem.free(self);
}
