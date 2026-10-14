import "github.com/ozzenerol/ktm-pg";
import coro, cstr, date, proc, str, t;

/* Timeouts and cancelling: the query ends on the server, the connection
 * stays usable */

i64 codeOf(pg.Conn *c, u8[] sql, String *msg = null)
{
    try {
        ad pg.Rows r = c.query(sql);
        while (r.next()) { }
    } catch (Error e) {
        if (msg) { msg.append(cstr.view(e.message)); }
        return e.code;
    }
    return 0;
}

void timeouts()
{
    ad String u = t.url();
    pg.Conn *c = pg.connect(u);
    defer c.close();
    c.setQueryTimeout(200);
    i64 start = date.monoMs();
    ad String msg = str.new();
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
    ad String st = t.url("postgres", "ktm", "?statement_timeout=100");
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
    ad String u = t.url();
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
    ad String u = t.url();
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
    ad String u = t.url();
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

/* A server that doesn't answer at all: the connection is given up */
void frozen()
{
    ad String u = t.url();
    pg.Conn *c = pg.connect(u);
    c.setQueryTimeout(300);
    proc.shellf("docker pause ktm-pg-%s >/dev/null", t.version());
    i64 start = date.monoMs();
    i64 code = codeOf(c, "SELECT 1");
    i64 took = date.monoMs() - start;
    proc.shellf("docker unpause ktm-pg-%s >/dev/null", t.version());
    t.ok(code == pg.ERR_IO && c.isDead() && took < 15000, "a frozen server: ERR_IO and a dead connection");
    c.close();
    pg.Conn *again = pg.connect(u);
    t.ok(pg.queryOne<i64>(again, "SELECT 1") == 1, "the server works again");
    again.close();
}

i32 main()
{
    t.start("cancel");
    timeouts();
    coroutines();
    pooled();
    frozen();
    return t.done();
}
