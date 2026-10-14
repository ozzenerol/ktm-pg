import "github.com/ozzenerol/ktm-pg";
import coro, cstr, date, proc, t;

/* The server restarts and goes away: the pool notices, recovers, and
 * never hands out a broken connection */

void waitUp(u8[] url)
{
    for (i64 i = 0; i < 300; i++) {
        try {
            pg.Conn *c = pg.connect(url);
            c.close();
            return;
        } catch { }
        date.sleepMs(100);
    }
}

void restart()
{
    proc.shellf("docker restart -t 1 ktm-pg-%s >/dev/null", t.version());
}

void idleAfterRestart()
{
    ad String u = t.url();
    pg.Options o;
    o.maxConns = 4;
    pg.Pool *db = pg.open(u, &o);
    pg.Conn *a = db.acquire();
    pg.Conn *b = db.acquire();
    a.release();
    b.release();
    t.ok(db.idleCount() == 2, "2 idle connections");
    restart();
    waitUp(u);
    date.sleepMs(100);
    i64 bad = 0;
    for (i64 i = 0; i < 5; i++) {
        try { pg.queryOne<i64>(db, "SELECT 1"); } catch { bad++; }
    }
    t.ok(bad == 0, "after a restart, no query sees a dead connection");
    t.ok(db.openCount() <= 2, "the dead ones were dropped");
    db.close();
}

void serverDown()
{
    ad String u = t.url();
    pg.Pool *db = pg.open(u);
    proc.shellf("docker stop -t 1 ktm-pg-%s >/dev/null", t.version());
    i64 start = date.monoMs();
    i64 code = 0;
    try { db.exec("SELECT 1"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_CONNECT && date.monoMs() - start < 5000, "server down: ERR_CONNECT at once");
    t.ok(db.openCount() == 0, "nothing counted open");
    proc.shellf("docker start ktm-pg-%s >/dev/null", t.version());
    waitUp(u);
    t.ok(pg.queryOne<i64>(db, "SELECT 5") == 5, "back when the server is");
    db.close();
}

/* Load through a restart: queries fail only while the server is away */
pg.Pool *gDb;
i64 gOk;
i64 gFailed;
i64 gLastFail;
i64 gStop;
i64 gStart;
String gLastMsg;

void *loader(void *arg)
{
    while (date.monoMs() < gStop) {
        try {
            if (pg.queryOne<i64>(gDb, "SELECT $1::int8 FROM pg_sleep(0.005)", 3) == 3) { gOk++; }
        } catch (Error e) {
            if (!gLastMsg.equals(cstr.view(e.message))) {
                printf("\n  [%d ms] %s", date.monoMs() - gStart, e.message);
                gLastMsg.clear();
                gLastMsg.append(cstr.view(e.message));
            }
            gFailed++;
            gLastFail = date.monoMs();
            coro.sleep(20);
        }
    }
    return null;
}

void *restarter(void *arg)
{
    coro.sleep(1000);
    proc.shellf("docker restart -t 1 ktm-pg-%s >/dev/null", t.version());     /* blocks this loop's thread a moment */
    return null;
}

void underLoad()
{
    ad String u = t.url();
    pg.Options o;
    o.maxConns = 8;
    o.acquireTimeoutMs = 2000;
    gDb = pg.open(u, &o);
    i64 start = date.monoMs();
    gStart = start;
    gStop = start + 12000;
    for (i64 i = 0; i < 16; i++) { coro.go(&loader); }
    coro.go(&restarter);
    coro.run();
    printf("\n  ok %d failed %d last failure at %d ms", gOk, gFailed, gLastFail - start);
    t.ok(gOk > 100, "queries ran before and after");
    t.ok(gLastFail == 0 || gStop - gLastFail > 3000, "and the last seconds had no failures");
    t.ok(gDb.openCount() <= 8, "within maxConns");
    gDb.close();
    gLastMsg.free();
}

i32 main()
{
    t.start("resilience");
    idleAfterRestart();
    serverDown();
    underLoad();
    return t.done();
}
