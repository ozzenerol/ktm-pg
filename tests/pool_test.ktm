import "github.com/ozzenerol/ktm-pg";
import atomic, coro, date, json, reflect, t, thread, vec;

/* The pool: reuse, limits, waits, broken connections, coroutines and threads */

@json struct Item { i64 id; String name; };

void basics()
{
    ad String u = t.url();
    pg.Pool *db = pg.open(u);
    db.execScript("DROP TABLE IF EXISTS pool_t; CREATE TABLE pool_t (id int8, name text)");
    t.ok(db.exec("INSERT INTO pool_t VALUES ($1, $2), ($3, $4)", 1, "a", 2, "b") == 2, "exec on a pool");
    ad pg.Rows r = db.query("SELECT id FROM pool_t ORDER BY id");
    t.ok(db.openCount() == 1 && db.idleCount() == 0, "the rows hold the connection");
    i64 sum = 0;
    while (r.next()) { sum += r.int(0); }
    t.ok(sum == 3 && db.idleCount() == 1, "back once the rows end");
    r.free();
    vec.Vec<Item> items = pg.queryAll<Item>(db, "SELECT * FROM pool_t ORDER BY id");
    t.ok(items.len == 2 && items.get(1).name.equals("b"), "queryAll on a pool");
    pg.free(reflect.of(items));
    t.ok(pg.queryOne<i64>(db, "SELECT count(*) FROM pool_t") == 2, "queryOne on a pool");
    t.ok(db.openCount() == 1, "one connection did it all");

    /* the same connection again and again */
    i64 pid = pg.queryOne<i64>(db, "SELECT pg_backend_pid()");
    bool same = true;
    for (i64 i = 0; i < 20; i++) {
        if (pg.queryOne<i64>(db, "SELECT pg_backend_pid()") != pid) { same = false; }
    }
    t.ok(same, "reused");

    /* an error gives the connection back */
    i64 code = 0;
    try { db.exec("SELECT * FROM nope"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.UNDEFINED_TABLE && db.idleCount() == 1, "back after an error");
    code = 0;
    r = db.query("SELECT 1 / (x - 2) FROM generate_series(1, 3) x");
    try { while (r.next()) { } } catch (Error e) { code = e.code; }
    t.ok(code == pg.DIVISION_BY_ZERO && db.idleCount() == 1, "back after an error in rows");
    r.free();

    /* transactions keep their connection */
    {
        pg.Tx tx = db.begin();
        defer tx.rollback();
        tx.exec("INSERT INTO pool_t VALUES (3, 'c')");
        t.ok(db.idleCount() == 0 && pg.queryOne<i64>(&tx, "SELECT count(*) FROM pool_t") == 3, "a Tx holds its connection");
        tx.commit();
    }
    t.ok(db.idleCount() == 1 && pg.queryOne<i64>(db, "SELECT count(*) FROM pool_t") == 3, "commit gives it back");
    {
        pg.Tx tx = db.begin();
        defer tx.rollback();
        tx.exec("DELETE FROM pool_t");
    }
    t.ok(db.idleCount() == 1 && pg.queryOne<i64>(db, "SELECT count(*) FROM pool_t") == 3, "rollback gives it back");

    /* a connection left in a transaction is closed, not reused */
    pg.Conn *c = db.acquire();
    c.exec("BEGIN");
    c.release();
    t.ok(db.openCount() == 0 && db.idleCount() == 0, "a dirty connection is dropped");
    t.ok(pg.queryOne<i64>(db, "SELECT count(*) FROM pool_t") == 3, "and a new one opened");
    db.exec("DROP TABLE pool_t");
    db.ping();
    db.close();
}

void limits()
{
    ad String u = t.url();
    pg.Options o;
    o.maxConns = 2;
    o.acquireTimeoutMs = 200;
    o.healthCheckMs = 1;
    o.maxLifetimeMs = 300;
    pg.Pool *db = pg.open(u, &o);
    pg.Conn *a = db.acquire();
    pg.Conn *b = db.acquire();
    t.ok(db.openCount() == 2, "two open");
    i64 start = date.monoMs();
    i64 code = 0;
    try { db.acquire(); } catch (Error e) { code = e.code; }
    i64 took = date.monoMs() - start;
    t.ok(code == pg.ERR_POOL && took >= 190 && took < 1000, "a third waits, then ERR_POOL");

    /* a connection killed while idle is noticed and replaced */
    i64 pidA = a.backendPid();
    b.exec("SELECT pg_terminate_backend($1)", pidA);
    a.release();
    b.release();
    date.sleepMs(5);
    bool ok = true;
    for (i64 i = 0; i < 4; i++) {
        try {
            if (pg.queryOne<i64>(db, "SELECT pg_backend_pid()") == pidA) { ok = false; }
        } catch (Error e) {
            ok = false;
        }
    }
    t.ok(ok, "a killed connection is never handed out");

    /* old connections are replaced */
    i64 pid = pg.queryOne<i64>(db, "SELECT pg_backend_pid()");
    date.sleepMs(350);
    t.ok(pg.queryOne<i64>(db, "SELECT pg_backend_pid()") != pid, "maxLifetimeMs");
    t.ok(db.openCount() <= 2, "never more than maxConns");

    /* a wrong password shows at the first use */
    ad String bad = t.url("ktm_scram", "wrong");
    pg.Pool *nope = pg.open(bad);
    code = 0;
    try { nope.ping(); } catch (Error e) { code = e.code; }
    t.ok(code == pg.INVALID_PASSWORD && nope.openCount() == 0, "the error of connecting, and nothing open");
    nope.close();

    /* close() with a connection still out: freed when it comes back */
    pg.Conn *out = db.acquire();
    db.close();
    out.release();
}

/* 40 coroutines share 4 connections */
pg.Pool *gDb;
i64 gDone;
i64 gMaxOpen;

void *worker(void *arg)
{
    for (i64 i = 0; i < 5; i++) {
        i64 n = pg.queryOne<i64>(gDb, "SELECT $1::int8 FROM pg_sleep(0.01)", i);
        if (n != i) { return null; }
        i64 open = gDb.openCount();
        if (open > gMaxOpen) { gMaxOpen = open; }
    }
    gDone++;
    return null;
}

void coroutines()
{
    ad String u = t.url();
    pg.Options o;
    o.maxConns = 4;
    gDb = pg.open(u, &o);
    gDone = 0;
    gMaxOpen = 0;
    i64 start = date.monoMs();
    for (i64 i = 0; i < 40; i++) { coro.go(&worker); }
    coro.run();
    i64 took = date.monoMs() - start;
    t.ok(gDone == 40, "40 coroutines done");
    t.ok(gMaxOpen <= 4 && gDb.openCount() == 4, "with 4 connections");
    t.ok(took < 3000, "sharing them without blocking");
    gDb.close();
}

/* 8 threads, plain blocking code, 3 connections */
i64 gThreadsOk;

void threadWork(void *priv, void *arg)
{
    pg.Pool *db = arg;
    for (i64 i = 0; i < 25; i++) {
        if (pg.queryOne<i64>(db, "SELECT $1::int8 + 1", i) != i + 1) { return; }
    }
    atomic.add(&gThreadsOk, 1);
}

void threads()
{
    ad String u = t.url();
    pg.Options o;
    o.maxConns = 3;
    pg.Pool *db = pg.open(u, &o);
    thread.Pool *tp = thread.poolNew(8);
    for (i64 i = 0; i < 8; i++) { thread.poolEnqueue(tp, db, &threadWork); }
    thread.poolWait(tp);
    thread.poolStop(tp);
    thread.poolRelease(tp);
    t.ok(atomic.load(&gThreadsOk) == 8, "8 threads done");
    t.ok(db.openCount() <= 3, "within maxConns");
    db.close();
}

i32 main()
{
    t.start("pool");
    basics();
    limits();
    coroutines();
    threads();
    return t.done();
}
