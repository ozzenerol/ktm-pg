import "github.com/ozzenerol/ktm-pg";
import coro, cstr, date, proc, str, t;

/* Timeouts and cancelling: the query ends on the server, the connection
 * stays usable */

i64 codeOf(pg.Conn *c, u8[] sql, String *msg = null)
{
    try {
        scoped pg.Rows r = c.query(sql);
        while (r.next()) { }
    } catch (Error e) {
        if (msg) { msg.append(cstr.view(e.message)); }
        if (t.verbose()) { printf("\n    (%s)", e.message); }
        return e.code;
    }
    return 0;
}

void timeouts()
{
    scoped String u = t.url();
    pg.Conn *c = pg.connect(u);
    defer c.close();
    c.setQueryTimeout(200);
    i64 start = date.monoMs();
    scoped String msg = str.new();
    i64 code = codeOf(c, "SELECT pg_sleep(5)", &msg);
    i64 took = date.monoMs() - start;
    t.ok(code == pg.QUERY_CANCELED && took >= 190 && took < 2000, "a slow query is cancelled");
    t.ok(str.contains(msg, "timeout after 200 ms"), "the message says it timed out");
    t.ok(!c.isDead() && c.txState() == 'I' && pg.queryOne<i64>(c, "SELECT 7") == 7, "the connection goes on");

    /* while the rows come */
    start = date.monoMs();
    code = codeOf(c, "SELECT pg_sleep(0.05) FROM generate_series(1, 100)");
    t.ok(code == pg.QUERY_CANCELED && date.monoMs() - start < 2000, "cancelled half way through the rows");
    t.ok(pg.queryOne<i64>(c, "SELECT 8") == 8, "usable again");

    /* a fast query after a timeout isn't hit by a late cancel */
    bool fine = true;
    for (i64 i = 0; i < 20; i++) {
        if (pg.queryOne<i64>(c, "SELECT $1::int8", i) != i) { fine = false; }
    }
    t.ok(fine, "no stray cancels");

    /* in a transaction: it fails, and rolls back */
    pg.Tx tx = c.begin();
    code = 0;
    try { tx.exec("SELECT pg_sleep(5)"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.QUERY_CANCELED && c.txState() == 'E', "a transaction fails on a timeout");
    tx.rollback();
    t.ok(c.txState() == 'I', "and rolls back");

    c.setQueryTimeout(0);
    t.ok(pg.queryOne<i64>(c, "SELECT 1 FROM pg_sleep(0.3)") == 1, "no limit again");

    /* the server's own statement_timeout */
    scoped String st = t.url("postgres", "ktm", "?statement_timeout=100");
    pg.Conn *s = pg.connect(st);
    t.ok(codeOf(s, "SELECT pg_sleep(2)") == pg.QUERY_CANCELED && pg.queryOne<i64>(s, "SELECT 2") == 2, "statement_timeout");
    s.close();
}

/* c.cancel() from another coroutine */
pg.Conn *gConn;
i64 gCode;
i64 gTook;

void *sleeper(void *arg)
{
    i64 start = date.monoMs();
    gCode = codeOf(gConn, "SELECT pg_sleep(5)");
    gTook = date.monoMs() - start;
    return null;
}

void *canceller(void *arg)
{
    coro.sleep(150);
    gConn.cancel();
    return null;
}

void *timed(void *arg)
{
    scoped String u = t.url();
    pg.Conn *c = pg.connect(u);
    c.setQueryTimeout(150);
    i64 start = date.monoMs();
    gCode = codeOf(c, "SELECT pg_sleep(5)");
    gTook = date.monoMs() - start;
    c.close();
    return null;
}

void coroutines()
{
    scoped String u = t.url();
    gConn = pg.connect(u);
    coro.go(&sleeper);
    coro.go(&canceller);
    coro.run();
    t.ok(gCode == pg.QUERY_CANCELED && gTook < 2000, "cancel() from another coroutine");
    t.ok(pg.queryOne<i64>(gConn, "SELECT 3") == 3, "usable after cancel()");
    gConn.close();
    gCode = 0;
    coro.go(&timed);
    coro.run();
    t.ok(gCode == pg.QUERY_CANCELED && gTook < 2000, "a timeout in a loop coroutine");
}

void pooled()
{
    scoped String u = t.url();
    pg.Options o;
    o.queryTimeoutMs = 150;
    pg.Pool *db = pg.open(u, &o);
    i64 code = 0;
    try { db.exec("SELECT pg_sleep(5)"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.QUERY_CANCELED && db.idleCount() == 1, "Options.queryTimeoutMs, connection kept");
    pg.Conn *c = db.acquire();
    c.setQueryTimeout(0);
    t.ok(c.exec("SELECT pg_sleep(0.3)") == 1, "one connection without a limit");
    c.release();
    code = 0;
    try { db.exec("SELECT pg_sleep(5)"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.QUERY_CANCELED, "the pool's limit is back for the next acquire");
    db.close();
}

/* A server that doesn't answer at all (docker pause): every call ends
 * within its limit, and the connection is given up */
void freeze()
{
    scoped String ct = t.container();
    proc.shellf("docker pause %s >/dev/null", ct);
}

void thaw()
{
    scoped String ct = t.container();
    proc.shellf("docker unpause %s >/dev/null", ct);
}

i64 pingCode(pg.Conn *c)
{
    try { c.ping(); } catch (Error e) {
        if (t.verbose()) { printf("\n    (%s)", e.message); }
        return e.code;
    }
    return 0;
}

void frozen()
{
    scoped String u = t.url();
    pg.Conn *c = pg.connect(u);
    c.setQueryTimeout(300);
    freeze();
    i64 start = date.monoMs();
    i64 code = codeOf(c, "SELECT 1");
    i64 took = date.monoMs() - start;
    thaw();
    /* the limit, then the cancel's grace: 5 s with the cancel's own connection */
    t.ok(code == pg.ERR_IO && c.isDead() && took >= 290 && took < 300 + 5000 + 700, "a frozen server: a query ends with ERR_IO in its limit + 5 s");
    c.close();

    c = pg.connect(u);
    c.setQueryTimeout(1000);
    freeze();
    start = date.monoMs();
    code = pingCode(c);
    took = date.monoMs() - start;
    thaw();
    t.ok(code == pg.ERR_IO && c.isDead() && took >= 990 && took < 1000 + 5000 + 700, "ping() too");
    c.close();
    pg.Conn *again = pg.connect(u);
    t.ok(pg.queryOne<i64>(again, "SELECT 1") == 1, "the server works again");
    again.close();
}

pg.Pool *gPool;
bool gLoop;

/* in the thread or coroutine that uses the pool: connections belong to
 * the shard of the one that opened them */
void frozenPoolRun()
{
    pg.Pool *db = gPool;
    u8 *where = gLoop ? "in a coroutine: " : "";
    t.ok(pg.queryOne<i64>(db, "SELECT 1") == 1 && db.idleCount() == 1, "an idle connection");
    date.sleepMs(1100);         /* past healthCheckMs: the next acquire pings it */
    freeze();
    i64 start = date.monoMs();
    i64 code = 0;
    try {
        pg.Conn *c = db.acquire();
        c.release();
    } catch (Error e) {
        if (t.verbose()) { printf("\n    (%s)", e.message); }
        code = e.code;
    }
    i64 took = date.monoMs() - start;
    thaw();
    /* the ping (at most 1 s, no cancel), then a connect in what is left */
    scoped String what = str.format("%sacquire() on a frozen server: ERR_CONNECT within acquireTimeoutMs (%d ms)", where, took);
    t.ok(code == pg.ERR_CONNECT && took < 2000 + 700, what.cstr());
    t.ok(db.openCount() == 0, "nothing left open");
    t.ok(pg.queryOne<i64>(db, "SELECT 2") == 2, "the pool works again");

    /* a connection just used isn't pinged: the query's own limit holds */
    freeze();
    start = date.monoMs();
    code = 0;
    try { db.ping(); } catch (Error e) { code = e.code; }
    took = date.monoMs() - start;
    thaw();
    t.ok(code == pg.ERR_IO && took < 1000 + 5000 + 700, "db.ping(): queryTimeoutMs + 5 s");
    t.ok(pg.queryOne<i64>(db, "SELECT 3") == 3, "and again");
}

void *frozenPoolCo(void *arg)
{
    frozenPoolRun();
    return null;
}

/* A pool on a frozen server: an idle connection's health ping and a new
 * connection both end within acquireTimeoutMs */
void frozenPool(bool loop)
{
    scoped String u = t.url();
    pg.Options o;
    o.maxConns = 2;
    o.acquireTimeoutMs = 2000;
    o.queryTimeoutMs = 1000;
    gPool = pg.open(u, &o);
    gLoop = loop;
    if (loop) {
        coro.go(&frozenPoolCo);
        coro.run();
    } else {
        frozenPoolRun();
    }
    gPool.close();
}

i32 main()
{
    t.start("cancel");
    timeouts();
    coroutines();
    pooled();
    frozen();
    frozenPool(false);
    frozenPool(true);
    return t.done();
}
