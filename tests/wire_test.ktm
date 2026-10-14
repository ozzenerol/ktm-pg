import "github.com/ozzenerol/ktm-pg";
import coro, cstr, date, libc, reflect, str, t, thread;

/* The wire protocol: start-up, every auth method, simple and extended
 * queries, errors, the statement cache, and coroutines */

/* Connects as user and returns the error code, 0 when it worked */
i64 tryConnect(u8[] url)
{
    try {
        pg.Conn *c = pg.connect(url);
        c.close();
    } catch (Error e) {
        return e.code;
    }
    return 0;
}

void auth()
{
    ad String scram = t.url("ktm_scram", "scrampw");
    ad String md5 = t.url("ktm_md5", "md5pw");
    ad String clear = t.url("ktm_clear", "clearpw");
    ad String trust = t.url("ktm_trust", "");
    ad String bad = t.url("ktm_scram", "nope");
    ad String badMd5 = t.url("ktm_md5", "nope");
    ad String none = t.url("ktm_scram", "");
    t.ok(tryConnect(scram) == 0, "scram user connects");
    t.ok(tryConnect(md5) == 0, "md5 user connects");
    t.ok(tryConnect(clear) == 0, "cleartext user connects");
    t.ok(tryConnect(trust) == 0, "trust user connects");
    t.ok(tryConnect(bad) == pg.INVALID_PASSWORD, "wrong scram password is 28P01");
    t.ok(tryConnect(badMd5) == pg.INVALID_PASSWORD, "wrong md5 password is 28P01");
    t.ok(tryConnect(none) == pg.ERR_AUTH, "no password is ERR_AUTH");
    t.ok(tryConnect("postgres://postgres:ktm@127.0.0.1:1/x") == pg.ERR_CONNECT, "refused port is ERR_CONNECT");
    ad String ssl = t.url("postgres", "ktm", "?sslmode=require");
    t.ok(tryConnect(ssl) == 0, "sslmode=require connects (the test servers have TLS)");
    ad String pt = t.port();
    ad String nodb = str.format("postgres://postgres:ktm@127.0.0.1:%s/no_such_db", pt);
    t.ok(tryConnect(nodb) == pg.state("3D000"), "unknown database is 3D000");
    t.ok(tryConnect("mysql://x") == pg.ERR_CONNECT, "other scheme is ERR_CONNECT");
}

void env()
{
    ad String port = t.port();
    libc.setenv("PGHOST", "127.0.0.1", 1);
    libc.setenv("PGPORT", port.cstr(), 1);
    libc.setenv("PGUSER", "ktm_md5", 1);
    libc.setenv("PGPASSWORD", "md5pw", 1);
    libc.setenv("PGDATABASE", "ktm_test", 1);
    libc.setenv("PGAPPNAME", "ktm-env", 1);
    pg.Conn *c = pg.connect();
    ad pg.Rows r = c.query("SELECT current_user, current_database(), current_setting('application_name')");
    t.ok(r.next(), "env: a row");
    t.ok(r.text(0).equals("ktm_md5") && r.text(1).equals("ktm_test") && r.text(2).equals("ktm-env"), "env: PG* used");
    r.free();
    c.close();
    /* the URL wins over the environment; extra keys are runtime parameters */
    ad String u = t.url("postgres", "ktm", "?application_name=url%20app&search_path=public");
    c = pg.connect(u);
    r = c.query("SELECT current_user, current_setting('application_name')");
    t.ok(r.next() && r.text(0).equals("postgres") && r.text(1).equals("url app"), "env: the URL wins");
    r.free();
    c.close();
    libc.unsetenv("PGHOST"); libc.unsetenv("PGPORT"); libc.unsetenv("PGUSER");
    libc.unsetenv("PGPASSWORD"); libc.unsetenv("PGDATABASE"); libc.unsetenv("PGAPPNAME");
}

void queries(pg.Conn *c)
{
    c.execScript("DROP TABLE IF EXISTS wire_t; CREATE TABLE wire_t (id int8 PRIMARY KEY, name text, score float8, ok bool, note text)");
    i64 id = 1;
    ad String name = str.from("ann");
    f64 score = 2.5;
    bool yes = true;
    u8 *note = null;
    t.ok(c.exec("INSERT INTO wire_t VALUES ($1, $2, $3, $4, $5)", id, name, score, yes, note) == 1, "insert counts 1");
    id = 2;
    u8[] bob = "bob";
    note = "a note";
    yes = false;
    score = -0.125;
    t.ok(c.exec("INSERT INTO wire_t VALUES ($1, $2, $3, $4, $5)", id, bob, score, yes, note) == 1, "insert with u8[] and u8 *");
    t.ok(c.exec("INSERT INTO wire_t VALUES ($1, $2, $3, $4, $5)", 3, "cy", 1.5, true, "lit") == 1, "insert with literals");
    reflect.Value a[] = {reflect.of(id)};
    t.ok(c.execArgs("DELETE FROM wire_t WHERE id > $1", a) == 1, "execArgs with a reflect.Value[]");
    t.ok(c.exec("UPDATE wire_t SET score = score * 2") == 2, "update counts 2");

    ad pg.Rows r = c.query("SELECT id, name, score, ok, note FROM wire_t ORDER BY id");
    t.ok(r.cols == 5, "5 columns");
    t.ok(r.name(1).equals("name") && r.column("note") == 4 && r.typeOid(0) == pg.INT8, "column names and types");
    i64 n = 0;
    while (r.next()) {
        n++;
        if (n == 1) {
            t.ok(r.int(0) == 1 && r.text(1).equals("ann") && r.float(2) == 5.0 && r.bool(3), "row 1 values");
            t.ok(r.isNull(4) && r.text(4).len == 0, "row 1 NULL");
        } else {
            t.ok(r.int(0) == 2 && r.text(1).equals("bob") && r.float(2) == -0.25 && !r.bool(3), "row 2 values");
            t.ok(!r.isNull(4) && r.text(4).equals("a note"), "row 2 note");
        }
    }
    t.ok(n == 2 && r.affected() == 2, "2 rows");
    r.free();

    /* stop early: the rest is dropped and the connection is free again */
    r = c.query("SELECT x FROM generate_series(1, 10000) x");
    t.ok(r.next() && r.int(0) == 1, "first of many");
    r.free();
    t.ok(c.exec("SELECT 1") == 1, "usable after an early free");

    /* busy while rows are open */
    r = c.query("SELECT 1");
    i64 code = 0;
    try { c.exec("SELECT 2"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_CLOSED, "exec with open rows throws");
    r.free();

    /* parameter count */
    code = 0;
    try { c.exec("SELECT $1::int"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_ARG, "missing parameter is ERR_ARG");

    /* a big value grows the buffers */
    i64 big = 3000000;
    r = c.query("SELECT repeat('x', $1::int)", big);
    t.ok(r.next() && r.text(0).len == big, "a 3 MB value");
    r.free();

    /* notices are dropped */
    c.execScript("DO $$ BEGIN RAISE NOTICE 'hello'; END $$");
    t.ok(c.exec("SELECT 1") == 1, "after a notice");

    /* a script returns the last count */
    t.ok(c.execScript("SELECT 1; SELECT * FROM generate_series(1, 3)") == 3, "script count");
}

void errors(pg.Conn *c)
{
    i64 code = 0;
    ad String msg = str.new();
    try { c.exec("SELEC 1"); } catch (Error e) { code = e.code; msg.append(cstr.view(e.message)); }
    t.ok(code == pg.SYNTAX_ERROR && pg.isServerError(code), "syntax error is 42601");
    t.ok(str.contains(msg, "(SQLSTATE 42601)"), "message has the SQLSTATE");
    ad String st = pg.stateText(code);
    t.ok(st.equals("42601"), "stateText");
    t.ok(c.exec("SELECT 1") == 1, "usable after an error");

    code = 0;
    msg.clear();
    try { c.exec("INSERT INTO wire_t (id) VALUES (1)"); } catch (Error e) { code = e.code; msg.append(cstr.view(e.message)); }
    t.ok(code == pg.UNIQUE_VIOLATION, "duplicate key is 23505");
    t.ok(str.contains(msg, "detail: Key (id)=(1) already exists."), "message has the detail");

    /* an error half way through the rows */
    ad pg.Rows r = c.query("SELECT 10 / (x - 3) FROM generate_series(1, 5) x");
    i64 rows = 0;
    code = 0;
    try {
        while (r.next()) { rows++; }
    } catch (Error e) {
        code = e.code;
    }
    t.ok(code == pg.DIVISION_BY_ZERO && rows == 2, "error during next()");
    r.free();
    t.ok(c.exec("SELECT 1") == 1, "usable after an error in rows");

    code = 0;
    try { c.exec("SELECT * FROM no_such_table"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.UNDEFINED_TABLE, "undefined table is 42P01");
    t.ok(!pg.isServerError(pg.ERR_IO), "client codes aren't server errors");
}

void cache(pg.Conn *c)
{
    /* more statements than the cache holds: the oldest are closed and parsed again */
    bool good = true;
    for (i64 round = 0; round < 2; round++) {
        for (i64 i = 0; i < 300; i++) {
            ad String q = str.format("SELECT %d + $1::int8", i);
            ad pg.Rows r = c.query(q, round);
            if (!r.next() || r.int(0) != i + round) { good = false; }
        }
    }
    t.ok(good, "300 statements twice");
    /* a statement that fails to parse doesn't stay in the cache */
    i64 code = 0;
    try { c.exec("SELECT nope"); } catch (Error e) { code = e.code; }
    try { c.exec("SELECT nope"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.UNDEFINED_COLUMN, "a failed parse is not cached");
}

void transactions(pg.Conn *c)
{
    c.execScript("DROP TABLE IF EXISTS wire_tx; CREATE TABLE wire_tx (id int PRIMARY KEY)");
    {
        pg.Tx tx = c.begin();
        defer tx.rollback();
        tx.exec("INSERT INTO wire_tx VALUES (1)");
        t.ok(c.txState() == 'T', "in a transaction");
        tx.commit();
    }
    t.ok(c.txState() == 'I', "idle after commit");
    {
        pg.Tx tx = c.begin("ISOLATION LEVEL SERIALIZABLE");
        defer tx.rollback();
        tx.exec("INSERT INTO wire_tx VALUES (2)");
    }
    ad pg.Rows r = c.query("SELECT count(*) FROM wire_tx");
    t.ok(r.next() && r.int(0) == 1, "commit kept, rollback dropped");
    r.free();

    pg.Tx tx = c.begin();
    i64 code = 0;
    try { tx.exec("INSERT INTO wire_tx VALUES (1)"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.UNIQUE_VIOLATION && c.txState() == 'E', "failed transaction");
    code = 0;
    try { tx.exec("SELECT 1"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.IN_FAILED_TRANSACTION, "statements in a failed transaction are 25P02");
    code = 0;
    try { tx.commit(); } catch (Error e) { code = e.code; }
    t.ok(code == pg.IN_FAILED_TRANSACTION && c.txState() == 'I', "commit of a failed transaction throws");
    code = 0;
    try { tx.exec("SELECT 1"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_CLOSED, "an ended transaction can't be used");
    tx.rollback();
    c.exec("DROP TABLE wire_tx");
}

/* A connection and threads: plain threads may take turns with one; one
 * used in a coroutine loop stays with its thread */
i64 gThreadCode;
pg.Conn *gShared;

void useIt(void *priv, void *arg)
{
    gThreadCode = 0;
    try {
        if (pg.queryOne<i64>(gShared, "SELECT 5") != 5) { gThreadCode = -1; }
    } catch (Error e) {
        gThreadCode = e.code;
    }
}

void *inLoop(void *arg)
{
    pg.queryOne<i64>(gShared, "SELECT 6");
    return null;
}

void threads()
{
    ad String u = t.url();
    gShared = pg.connect(u);
    thread.Pool *tp = thread.poolNew(1);
    thread.poolEnqueue(tp, null, &useIt);
    thread.poolWait(tp);
    t.ok(gThreadCode == 0, "another plain thread may use a connection, in turn");
    t.ok(pg.queryOne<i64>(gShared, "SELECT 7") == 7, "and this one again after it");
    coro.go(&inLoop);
    coro.run();
    thread.poolEnqueue(tp, null, &useIt);
    thread.poolWait(tp);
    t.ok(gThreadCode == pg.ERR_CLOSED, "once used in a loop, another thread gets ERR_CLOSED");
    thread.poolStop(tp);
    thread.poolRelease(tp);
    gShared.close();
}

/* Coroutines: each sleeps on its own connection; together they take as
 * long as one, so the waits don't block the loop's thread */
i64 gDone = 0;
i64 gInLoop = 0;

void *sleeper(void *arg)
{
    String *u = arg;
    pg.Conn *c = pg.connect(*u);
    defer c.close();
    c.exec("SELECT pg_sleep(0.3)");
    gDone++;
    return null;
}

void loop()
{
    ad String u = t.url();
    i64 start = date.monoMs();
    for (i64 i = 0; i < 8; i++) { coro.go(&sleeper, &u); }
    coro.run();
    i64 took = date.monoMs() - start;
    t.ok(gDone == 8, "8 coroutines done");
    t.ok(took < 2500, "they ran at the same time (8 x 0.3 s would be 2.4 s one after another)");
}

i32 main()
{
    t.start("wire");
    auth();
    env();
    ad String u = t.url();
    pg.Conn *c = pg.connect(u);
    t.ok(c.serverVersion().len > 0 && c.txState() == 'I' && c.backendPid() > 0, "connection state");
    queries(c);
    errors(c);
    cache(c);
    transactions(c);
    c.exec("DROP TABLE wire_t");
    c.ping();
    t.ok(c.txState() == 'I', "ping");

    /* the server ends the session: ERR_IO, and the connection is dead */
    pg.Conn *other = pg.connect(u);
    other.exec("SELECT pg_terminate_backend($1)", c.backendPid());
    other.close();
    i64 code = 0;
    try { c.exec("SELECT 1"); } catch (Error e) { code = e.code; }
    t.ok((code == pg.ERR_IO || code == pg.state("57P01")) && c.isDead(), "a killed session is ERR_IO and dead");
    code = 0;
    try { c.exec("SELECT 1"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_CLOSED, "a dead connection says closed");
    c.close();
    threads();
    loop();
    return t.done();
}
