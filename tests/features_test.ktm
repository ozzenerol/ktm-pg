import "github.com/ozzenerol/ktm-pg";
import coro, cstr, date, json, reflect, str, t, vec;

/* LISTEN/NOTIFY, COPY in and out, arrays, interval, inet */

@json struct Tagged { i64 id; i64[] nums; vec.Vec<String> tags; bool[] flags; };

void notifications()
{
    ad String u = t.url();
    pg.Conn *l = pg.connect(u);
    pg.Conn *s = pg.connect(u);
    l.listen("jobs");
    l.listen("odd \"name\"");
    s.notify("jobs", "one");
    s.notify("odd \"name\"", "two");
    s.exec("NOTIFY jobs");
    pg.Notification n;
    t.ok(l.waitNotification(&n, 2000) && n.channel.equals("jobs") && n.payload.equals("one") && n.pid == s.backendPid(), "a notification");
    n.free();
    t.ok(l.waitNotification(&n, 2000) && n.channel.equals("odd \"name\"") && n.payload.equals("two"), "a quoted channel name");
    n.free();
    t.ok(l.waitNotification(&n, 2000) && n.payload.len == 0, "NOTIFY without a payload");
    n.free();
    i64 start = date.monoMs();
    t.ok(!l.waitNotification(&n, 200) && date.monoMs() - start >= 190, "none: false after the wait");
    /* notifications that come during queries are kept */
    s.notify("jobs", "three");
    t.ok(pg.queryOne<i64>(l, "SELECT 1 FROM pg_sleep(0.1)") == 1, "a query while listening");
    t.ok(l.waitNotification(&n, 0) && n.payload.equals("three"), "kept for later");
    n.free();
    l.unlisten("*");
    s.notify("jobs", "four");
    t.ok(!l.waitNotification(&n, 300), "unlisten: nothing more");
    l.close();
    s.close();
}

/* a listener coroutine and a sender in the same loop */
i64 gGot;

void *listener(void *arg)
{
    ad String u = t.url();
    pg.Conn *l = pg.connect(u);
    l.listen("loop");
    pg.Notification n;
    while (gGot < 5 && l.waitNotification(&n, 3000)) {
        gGot++;
        n.free();
    }
    l.close();
    return null;
}

void *sender(void *arg)
{
    coro.sleep(100);
    ad String u = t.url();
    pg.Conn *s = pg.connect(u);
    for (i64 i = 0; i < 5; i++) { s.notify("loop", "x"); coro.sleep(10); }
    s.close();
    return null;
}

void loopNotifications()
{
    gGot = 0;
    coro.go(&listener);
    coro.go(&sender);
    coro.run();
    t.ok(gGot == 5, "notifications in a loop coroutine");
}

void copying(pg.Conn *c)
{
    c.execScript("DROP TABLE IF EXISTS copy_t; CREATE TABLE copy_t (id int8, name text, note text, b bytea)");
    pg.CopyIn in = c.copyIn("COPY copy_t (id, name, note) FROM STDIN");
    for (i64 i = 0; i < 1000; i++) {
        u8 *note = i % 2 ? "tab\there, line\nend, back\\slash" : null;
        in.row(i, "name", note);
    }
    t.ok(in.end() == 1000, "COPY FROM STDIN: 1000 rows");
    t.ok(pg.queryOne<i64>(c, "SELECT count(*) FROM copy_t WHERE note IS NULL") == 500, "NULLs");
    ad String note = pg.queryOne<String>(c, "SELECT note FROM copy_t WHERE id = 1");
    t.ok(note.equals("tab\there, line\nend, back\\slash"), "escapes round trip");
    in = c.copyIn("COPY copy_t (id, name) FROM STDIN (FORMAT csv)");
    in.write("2000,\"a, b\"\n2001,c\n");
    t.ok(in.end() == 2 && pg.queryOne<i64>(c, "SELECT count(*) FROM copy_t") == 1002, "csv with write()");
    in = c.copyIn("COPY copy_t (id) FROM STDIN");
    in.write("not a number\n");
    i64 code = 0;
    try { in.end(); } catch (Error e) { code = e.code; }
    t.ok(code == pg.state("22P02") && pg.queryOne<i64>(c, "SELECT count(*) FROM copy_t") == 1002, "a bad row: the error, nothing copied");
    in = c.copyIn("COPY copy_t (id) FROM STDIN");
    in.row(5);
    in.abort();
    t.ok(pg.queryOne<i64>(c, "SELECT count(*) FROM copy_t") == 1002, "abort(): nothing copied");
    code = 0;
    try { pg.CopyIn bad = c.copyIn("COPY nope FROM STDIN"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.UNDEFINED_TABLE && pg.queryOne<i64>(c, "SELECT 1") == 1, "COPY of a missing table");

    pg.CopyOut out = c.copyOut("COPY (SELECT id, name FROM copy_t WHERE id < 3 ORDER BY id) TO STDOUT (FORMAT csv)");
    ad String all = str.new();
    while (out.next()) { all.append(out.data); }
    t.ok(all.equals("0,name\n1,name\n2,name\n") && out.rows() == 3, "COPY TO STDOUT");
    out.free();
    out = c.copyOut("COPY copy_t TO STDOUT");
    t.ok(out.next(), "stop early");
    out.free();
    t.ok(pg.queryOne<i64>(c, "SELECT 2") == 2, "usable after");
    c.exec("DROP TABLE copy_t");
}

void arrays(pg.Conn *c)
{
    i64 nums[3] = {1, -2, 3};
    vec.Vec<String> tags = vec.new<String>();
    tags.push(str.from("a"));
    tags.push(str.from("b \"c\",d\\e"));
    tags.push(str.from("NULL"));
    ad String back = pg.queryOne<String>(c, "SELECT $1::int8[]::text", nums);
    t.ok(back.equals("{1,-2,3}"), "an i64 array as int8[]");
    vec.Vec<String> got = pg.queryOne<vec.Vec<String>>(c, "SELECT $1::text[]", tags);
    t.ok(got.len == 3 && got.get(1).equals("b \"c\",d\\e") && got.get(2).equals("NULL"), "a Vec<String> as text[] and back, quotes and all");
    got.free();
    vec.Vec<i64> ns = pg.queryOne<vec.Vec<i64>>(c, "SELECT ARRAY[4, 5, 6]::int8[]");
    t.ok(ns.len == 3 && ns.get(2) == 6, "int8[] into a Vec<i64>");
    ns.free();
    vec.Vec<Tagged> rows = pg.queryAll<Tagged>(c, "SELECT 1 AS id, ARRAY[1,2]::int4[] AS nums, ARRAY['x','y z']::text[] AS tags, ARRAY[true,false] AS flags");
    Tagged *r = rows.at(0);
    t.ok(r.nums.len == 2 && r.nums[1] == 2 && r.tags.get(1).equals("y z") && r.flags.len == 2 && r.flags[0], "arrays into struct fields");
    pg.free(reflect.of(rows));
    vec.Vec<f64> fs = pg.queryOne<vec.Vec<f64>>(c, "SELECT ARRAY[1.5, NULL, 2.25]::float8[]");
    t.ok(fs.len == 3 && fs.get(0) == 1.5 && fs.get(1) == 0 && fs.get(2) == 2.25, "NULL elements are 0");
    fs.free();
    ad String nested = pg.queryOne<String>(c, "SELECT ARRAY[[1,2],[3,4]]::text");
    t.ok(nested.equals("{{1,2},{3,4}}"), "a 2-D array as text");
    vec.Vec<i64> empty = pg.queryOne<vec.Vec<i64>>(c, "SELECT '{}'::int8[]");
    t.ok(empty.len == 0, "an empty array");
    empty.free();
    i64 code = 0;
    try { i64 x = pg.queryOne<i64>(c, "SELECT ARRAY[1]"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_SCAN, "an array into an i64 is ERR_SCAN");
    tags.free();
}

void intervals(pg.Conn *c)
{
    pg.Interval iv = pg.queryOne<pg.Interval>(c, "SELECT '1 year 2 mons 3 days 04:05:06.5'::interval");
    t.ok(iv.months == 14 && iv.days == 3 && iv.micros == 14706500000, "an interval");
    ad String txt = pg.queryOne<String>(c, "SELECT '1 year 2 mons 3 days 04:05:06.5'::interval");
    t.ok(txt.equals("1 year 2 mons 3 days 04:05:06.5"), "its text as the server writes it");
    pg.Interval back = pg.queryOne<pg.Interval>(c, "SELECT $1::interval * 2", iv);
    t.ok(back.months == 28 && back.days == 6 && back.micros == 29413000000, "an interval parameter");
    ad String neg = pg.queryOne<String>(c, "SELECT '-1 day -00:00:01'::interval");
    t.ok(neg.equals("-1 days -00:00:01"), "negative parts");
    ad String zero = pg.queryOne<String>(c, "SELECT '0'::interval");
    t.ok(zero.equals("00:00:00"), "zero");
    ad String ip = pg.queryOne<String>(c, "SELECT $1::inet", "192.168.1.5/24");
    t.ok(ip.equals("192.168.1.5/24"), "inet as text");
}

void batches(pg.Conn *c)
{
    c.execScript("DROP TABLE IF EXISTS batch_t; CREATE TABLE batch_t (id int8 PRIMARY KEY, name text, tags text[], doc jsonb)");
    pg.Batch b;
    defer b.free();
    for (i64 i = 0; i < 100; i++) {
        ad String name = str.format("n%d", i);
        String tags[2] = {str.from("a"), str.from("b")};
        b.add("INSERT INTO batch_t VALUES ($1, $2, $3, $4)", i, name, tags, tags);
        tags[0].free();
        tags[1].free();
    }
    b.add("UPDATE batch_t SET name = 'x' WHERE id < $1", 10);
    i64[] counts = c.execBatch(&b);
    t.ok(counts.len == 101 && counts[0] == 1 && counts[100] == 10, "a batch of 101 statements");
    counts.free();
    ad String tg = pg.queryOne<String>(c, "SELECT tags::text || doc::text FROM batch_t WHERE id = 5");
    t.ok(tg.equals("{a,b}[\"a\", \"b\"]"), "lists as an array and as JSON, by the column's type");
    b.clear();
    b.add("INSERT INTO batch_t (id) VALUES ($1)", 1000);
    b.add("INSERT INTO batch_t (id) VALUES ($1)", 1);       /* a duplicate */
    b.add("INSERT INTO batch_t (id) VALUES ($1)", 1001);
    i64 code = 0;
    ad String msg = str.new();
    try { c.execBatch(&b); } catch (Error e) { code = e.code; msg.append(cstr.view(e.message)); }
    t.ok(code == pg.UNIQUE_VIOLATION && str.contains(msg, "batch statement 2"), "a failing statement: its error and number");
    t.ok(pg.queryOne<i64>(c, "SELECT count(*) FROM batch_t") == 100, "and nothing of the batch stays");
    c.exec("DROP TABLE batch_t");
}

i32 main()
{
    t.start("features");
    notifications();
    loopNotifications();
    ad String u = t.url();
    pg.Conn *c = pg.connect(u);
    copying(c);
    arrays(c);
    intervals(c);
    batches(c);
    c.close();
    return t.done();
}
