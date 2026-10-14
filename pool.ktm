module pg;
import atomic, coro, date, libc, mem, net, reflect;

extern "c" void *pthread_self();

/* How a Pool runs. A declared Options is all zero: every 0 means the
 * default in the comment. */
pub struct Options
{
    i64 maxConns;           /* connections open at once, all threads together (0: max(4, CPUs)) */
    i64 maxIdleMs;          /* an idle connection is closed after this (0: 30 min) */
    i64 maxLifetimeMs;      /* a connection is closed after this, at its next release (0: 1 hour) */
    i64 acquireTimeoutMs;   /* waiting for a free connection longer throws ERR_POOL (0: 30 s) */
    i64 healthCheckMs;      /* a connection idle this long is checked before it is handed out (0: 1 s) */
};

/* The connections of one thread (and one kind: loop coroutines or plain
 * code). A non-blocking socket belongs to the coroutine loop of the
 * thread that opened it, so connections never move between shards and a
 * shard needs no lock: only its own thread touches it. */
struct Shard
{
    void *thread;
    bool loop;
    Conn **idle;            /* a stack: the most recently used on top */
    i64 nidle;
    i64 cap;
    i64 waiters;            /* coroutines of this shard waiting for a connection */
    i32 wake[2];            /* a pipe: a byte in it wakes the waiters (loop shards) */
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
    private i64 nopen;          /* connections open (atomic) */
    private i64 nwaiting;       /* waiters in every shard (atomic) */
    private Shard **shards;     /* up to MAX_SHARDS; never moved, so reads need no lock */
    private i64 nshards;        /* (atomic) */
    private i64 lock;           /* a spin lock for adding a shard */
    private i64 closed;
};

const i64 MAX_SHARDS = 1024;

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
    p.maxConns = o.maxConns > 0 ? o.maxConns : (cpus > 4 ? cpus : 4);
    p.maxIdleMs = o.maxIdleMs > 0 ? o.maxIdleMs : 30 * 60 * 1000;
    p.maxLifetimeMs = o.maxLifetimeMs > 0 ? o.maxLifetimeMs : 60 * 60 * 1000;
    p.acquireMs = o.acquireTimeoutMs > 0 ? o.acquireTimeoutMs : 30000;
    p.healthMs = o.healthCheckMs > 0 ? o.healthCheckMs : 1000;
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
    for (i64 i = 0; i < n; i++) {        /* only this thread adds its own, but look again anyway */
        Shard *s = self.shards[i];
        if (s.thread == th && s.loop == loop) { atomic.store(&self.lock, 0); return s; }
    }
    if (n == MAX_SHARDS) {
        atomic.store(&self.lock, 0);
        throw(ERR_POOL, "pg: a pool used from more than %d threads", MAX_SHARDS);
    }
    Shard *s = mem.allocZero(sizeof(Shard));
    s.thread = th;
    s.loop = loop;
    s.wake[0] = -1;
    s.wake[1] = -1;
    if (loop && libc.pipe(s.wake) == 0) {
        net.setNonBlocking(s.wake[0]);
        net.setNonBlocking(s.wake[1]);
    }
    self.shards[n] = s;
    atomic.store(&self.nshards, n + 1);
    atomic.store(&self.lock, 0);
    return s;
}

/* Wake the waiters of s (any thread may call it) */
static void shardWake(Shard *s)
{
    if (s.wake[1] >= 0) {
        u8 b = 1;
        libc.write(s.wake[1], &b, 1);
    }
}

/* A connection's budget is free again: wake a shard that waits for one */
static void Pool.wakeOne()
{
    if (!atomic.load(&self.nwaiting)) { return; }
    i64 n = atomic.load(&self.nshards);
    for (i64 i = 0; i < n; i++) {
        Shard *s = self.shards[i];
        if (atomic.load(&s.waiters) > 0) { shardWake(s); return; }
    }
}

/* Close a connection of the pool for good */
static void Pool.discard(Conn *c)
{
    c.pool = null;
    c.terminate();
    c.release_();
    atomic.sub(&self.nopen, 1);
    if (atomic.load(&self.closed)) { self.freeIfDone(); return; }
    self.wakeOne();
}

/* Whether an idle connection may be handed out: not too old, alive */
static bool Pool.usable(Conn *c, i64 now)
{
    if (c.dead) { return false; }
    if (now - c.born >= self.maxLifetimeMs || now - c.idleSince >= self.maxIdleMs) { return false; }
    if (now - c.idleSince >= self.healthMs) {
        try { c.ping(); } catch { return false; }
    }
    return true;
}

/* Wait until a connection may be free, or the deadline */
static void Pool.wait(Shard *s, i64 deadline)
{
    i64 left = deadline - date.monoMs();
    if (left <= 0) { return; }
    atomic.add(&s.waiters, 1);
    atomic.add(&self.nwaiting, 1);
    if (s.loop && s.wake[0] >= 0) {
        coro.wait(s.wake[0], libc.POLLIN, left > 50 ? 50 : left);
        u8 buf[64];
        while (libc.read(s.wake[0], buf, 64) > 0) { }
    } else {
        date.sleepMs(1);
    }
    atomic.sub(&s.waiters, 1);
    atomic.sub(&self.nwaiting, 1);
}

/* A connection of the pool, all yours until c.release() (or c.close())
 * gives it back. Throws ERR_POOL when none is free within
 * acquireTimeoutMs, and what connecting throws. */
pub Conn *Pool.acquire()
{
    Shard *s = self.shard();
    i64 deadline = date.monoMs() + self.acquireMs;
    while (true) {
        if (atomic.load(&self.closed)) { throw(ERR_POOL, "pg: the pool is closed"); }
        i64 now = date.monoMs();
        while (s.nidle) {
            Conn *c = s.idle[--s.nidle];
            if (self.usable(c, now)) { return c; }
            self.discard(c);
        }
        if (atomic.add(&self.nopen, 1) < self.maxConns) {
            Conn *c;
            try {
                c = connectConfig(&self.cfg);
            } catch (Error e) {
                atomic.sub(&self.nopen, 1);
                self.wakeOne();
                throw(e);
            }
            c.pool = self;
            c.shard = s;
            return c;
        }
        atomic.sub(&self.nopen, 1);
        if (date.monoMs() >= deadline) {
            throw(ERR_POOL, "pg: no connection free within %d ms (maxConns %d)", self.acquireMs, self.maxConns);
        }
        self.wait(s, deadline);
    }
    return null;
}

/* c comes back to its pool */
static void poolPut(void *pool, Conn *c)
{
    Pool *p = pool;
    Shard *s = c.shard;
    i64 now = date.monoMs();
    if (c.dead || c.busy || c.txStatus != 'I' || atomic.load(&p.closed) || now - c.born >= p.maxLifetimeMs) {
        p.discard(c);
        return;
    }
    if (atomic.load(&s.waiters) == 0 && atomic.load(&p.nwaiting) > 0) {
        /* others wait, in other threads: free the budget for them */
        p.discard(c);
        return;
    }
    if (s.nidle == s.cap) {
        s.cap = s.cap ? s.cap * 2 : 8;
        s.idle = mem.realloc(s.idle, sizeof(Conn *) * s.cap);
    }
    c.idleSince = now;
    s.idle[s.nidle++] = c;
    if (atomic.load(&s.waiters)) { shardWake(s); }
}

/* Gives a connection from Pool.acquire back (the same as c.close()) */
pub void Conn.release()
{
    self.close();
}

/* Checks the connection works: a round trip to the server */
pub void Conn.ping()
{
    self.ready();
    self.begin('Q');
    self.put8(0);
    self.end();
    self.flush();
    while (true) {
        u8 t = self.recv();
        if (t == 'E') { self.throwServerError(); }
        if (t == 'Z') { self.txStatus = self.msg.len ? self.msg[0] : 'I'; break; }
    }
}

/* Checks the server can be reached: takes a connection and pings it */
pub void Pool.ping()
{
    Conn *c = self.acquire();
    defer c.release();
    c.ping();
}

static Rows poolOpen(Db *db, u8[] sql, reflect.Value[] args)
{
    Pool *p = db;
    Conn *c = p.acquire();
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
    Conn *c = self.acquire();
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
    return poolOpen(self, sql, args);
}

pub Rows Pool.query(u8[] sql, any ...args)
{
    return poolOpen(self, sql, args);
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
        while (s.nidle) { self.discard(s.idle[--s.nidle]); }
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
        mem.free(s.idle);
        if (s.wake[0] >= 0) { libc.close(s.wake[0]); libc.close(s.wake[1]); }
        mem.free(s);
    }
    mem.free(self.shards);
    self.cfg.free();
    mem.free(self);
}
