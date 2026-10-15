import "github.com/ozzenerol/ktm-pg";
import coro, date, str, t;

/* Pipelining: coroutines of one loop share a connection; each query's
 * answer comes in turn, errors stay with their query */

pg.Conn *gC;
i64 gGood;
i64 gBad;
i64 gErrOk;

void *worker(void *arg)
{
    i64 id = (i64)arg;
    for (i64 i = 0; i < 20; i++) {
        try {
            i64 got;
            if (i % 5 == 0) {
                /* a statement of its own: prepared while others pipeline */
                scoped String q = str.format("SELECT $1::int8 + %d", id);
                got = pg.queryOne<i64>(gC, q, i) - id;
            } else {
                got = pg.queryOne<i64>(gC, "SELECT $1::int8", i);
            }
            if (got == i) { gGood++; } else { gBad++; }
        } catch (Error e) {
            if (gBad < 3) { printf("\n  worker error: %s", e.message); }
            gBad++;
        }
    }
    return null;
}

void *failer(void *arg)
{
    for (i64 i = 0; i < 10; i++) {
        try {
            pg.queryOne<i64>(gC, "SELECT 1 / ($1::int8 - $1::int8)", i);
        } catch (Error e) {
            if (e.code == pg.DIVISION_BY_ZERO) { gErrOk++; } else { printf("\n  failer div: %s", e.message); }
        }
        try {
            gC.exec("SELECT $1::int8", "not a number");
        } catch (Error e) {
            if (e.code == pg.state("22P02")) { gErrOk++; } else { printf("\n  failer: %s", e.message); }
        }
    }
    return null;
}

void shared()
{
    scoped String u = t.url();
    gC = pg.connect(u);
    gGood = 0;
    gBad = 0;
    gErrOk = 0;
    i64 start = date.monoMs();
    for (i64 i = 0; i < 50; i++) { coro.go(&worker, (void *)i); }
    coro.go(&failer);
    coro.go(&failer);
    coro.run();
    i64 took = date.monoMs() - start;
    t.ok(gGood == 1000 && gBad == 0, "1000 answers on one connection, all right");
    t.ok(gErrOk == 40, "errors stayed with their queries");
    t.ok(took < 5000, "in time");
    t.ok(!gC.isDead() && pg.queryOne<i64>(gC, "SELECT 9") == 9, "the connection is fine");
    gC.close();
}

/* Rows held by one coroutine: the others wait their turn */
i64 gOrder;
i64 gSecond;

void *holder(void *arg)
{
    scoped pg.Rows r = gC.query("SELECT x FROM generate_series(1, 3) x");
    coro.sleep(50);
    i64 n = 0;
    while (r.next()) { n += r.int(0); }
    gOrder = gOrder * 10 + 1;
    i64 code = 0;
    scoped pg.Rows r2 = gC.query("SELECT 1");
    try { gC.exec("SELECT 2"); } catch (Error e) { code = e.code; }
    if (code == pg.ERR_CLOSED) { gOrder = gOrder * 10 + 7; }
    return null;
}

void *second(void *arg)
{
    gSecond = pg.queryOne<i64>(gC, "SELECT 2");
    gOrder = gOrder * 10 + 2;
    return null;
}

void rowsInTurn()
{
    scoped String u = t.url();
    gC = pg.connect(u);
    gOrder = 0;
    coro.go(&holder);
    coro.go(&second);
    coro.run();
    t.ok(gOrder == 172 || gOrder == 127, "the second query waits for the rows; own rows block own queries");
    t.ok(gSecond == 2, "and gets its answer");
    gC.close();
}

/* The connection breaks with queries queued: every one fails, none hangs */
i64 gFailed;

void *queued(void *arg)
{
    try { gC.exec("SELECT pg_sleep(0.2)"); } catch { gFailed++; }
    return null;
}

void *killer(void *arg)
{
    coro.sleep(50);
    scoped String u = t.url();
    pg.Conn *k = pg.connect(u);
    k.exec("SELECT pg_terminate_backend($1)", gC.backendPid());
    k.close();
    return null;
}

void broken()
{
    scoped String u = t.url();
    gC = pg.connect(u);
    gFailed = 0;
    for (i64 i = 0; i < 10; i++) { coro.go(&queued); }
    coro.go(&killer);
    i64 start = date.monoMs();
    coro.run();
    t.ok(gFailed == 10 && date.monoMs() - start < 3000 && gC.isDead(), "all 10 queued queries fail at once");
    gC.close();
}

/* A pool of 2 connections serves 100 coroutines by pipelining */
pg.Pool *gDb;

void *poolWorker(void *arg)
{
    for (i64 i = 0; i < 10; i++) {
        try {
            if (pg.queryOne<i64>(gDb, "SELECT $1::int8 * 3", i) == i * 3) { gGood++; } else { gBad++; }
            gDb.exec("SELECT 1");
        } catch (Error e) {
            if (gBad < 3) { printf("\n  pool worker: %s", e.message); }
            gBad++;
        }
    }
    return null;
}

void pooled()
{
    scoped String u = t.url();
    pg.Options o;
    o.maxConns = 2;
    gDb = pg.open(u, &o);
    gGood = 0;
    gBad = 0;
    i64 start = date.monoMs();
    for (i64 i = 0; i < 100; i++) { coro.go(&poolWorker); }
    coro.run();
    t.ok(gGood == 1000 && gBad == 0, "1000 queries through 2 connections");
    t.ok(gDb.openCount() <= 2 && date.monoMs() - start < 5000, "within maxConns, in time");
    gDb.close();
}

i32 main()
{
    t.start("pipeline");
    shared();
    rowsInTurn();
    broken();
    pooled();
    return t.done();
}
