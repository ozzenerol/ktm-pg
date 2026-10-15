import "github.com/ozzenerol/ktm-pg";
import cstr, date, json, mem, reflect, str, t, vec;

/* copyInto<T>: COPY in binary format from structs, every column type it
 * takes, read back the same; the mistakes it refuses before anything is
 * sent, and a server error at end() */

enum Mood { calm, cross };

@json struct Meta { String kind; i64 n; };

struct Item
{
    i64 id;
    i32 small;
    i64 mid;
    f64 price;
    f64 ratio;
    bool active;
    String name;
    @key("label") u8[] tag;
    u8[] blob;
    u8 uid[16];
    date.Date at;
    date.Date day;
    Meta meta;
    Meta raw;
    Mood mood;
    i64 *boss;
    pg.Interval wait;
    @skip i64 local;
};

/* what the table holds for row i, as a ktm value */
Item itemOf(i64 i, i64 *boss)
{
    Item x;
    mem.set(&x, 0, sizeof(Item));
    x.id = i;
    x.small = (i32)(i % 1000);
    x.mid = i * 7;
    x.price = (f64)i / 4.0;
    x.ratio = 0.5;
    x.active = i % 2 == 0;
    x.name = str.format("item-%d", i);
    x.tag = "t\tab\\";
    x.blob = "\x00\x01";
    x.uid[15] = (u8)i;
    x.at = pg.dateFromMicros((1700000000 + i) * 1000000);
    x.day = pg.dateFromMicros(1700000000 * 1000000);
    x.meta.kind = str.from("k");
    x.meta.n = i;
    x.raw.kind = str.from("r");
    x.mood = i % 3 == 0 ? Mood.cross : Mood.calm;
    x.boss = i % 5 == 0 ? null : boss;
    x.wait.days = 1;
    x.wait.micros = 5;
    return x;
}

void freeItem(Item *x)
{
    x.name.free();
    x.meta.kind.free();
    x.raw.kind.free();
}

struct NoCol { i64 id; i64 nothere; };
struct Num { i64 id; f64 amount; };
struct Wrong { String id; String name; };
struct Right { i64 id; String name; };

struct Back
{
    i64 id;
    i64 small;
    i64 mid;
    f64 price;
    bool active;
    String name;
    String label;
    u8[] blob;
    String uid;
    i64 at;
    String day;
    String meta;
    String raw;
    String mood;
    i64 *boss;
    String wait;
};

i32 main()
{
    t.start("copy binary");
    ad String u = t.url();
    pg.Conn *c = pg.connect(u);
    c.exec("DROP TABLE IF EXISTS copybin");
    c.exec("DROP TYPE IF EXISTS mood");
    c.exec("CREATE TYPE mood AS ENUM ('calm', 'cross')");
    c.exec("CREATE TABLE copybin (id int8 PRIMARY KEY, small int2, mid int4, price float8, ratio float4,"
           " active bool, name text, label varchar(20), blob bytea, uid uuid, at timestamptz, day date,"
           " meta jsonb, raw json, mood mood, boss int8, wait interval, extra text DEFAULT 'dflt')");

    i64 boss = 99;
    pg.CopyInto<Item> in = c.copyInto<Item>("copybin");
    for (i64 i = 1; i <= 1000; i++) {
        Item x = itemOf(i, &boss);
        in.put(x);
        freeItem(&x);
    }
    t.ok(in.end() == 1000, "1000 rows copied");

    vec.Vec<Back> rows = pg.queryAll<Back>(c, "SELECT id, small, mid, price, active, name, label, blob,"
        " uid::text, (extract(epoch FROM at))::int8 AS at, day::text, meta::text, raw::text, mood::text, boss,"
        " wait::text FROM copybin ORDER BY id");
    bool same = rows.len == 1000;
    for (i64 k = 0; k < rows.len && same; k++) {
        Back *b = &rows.data[k];
        i64 i = k + 1;
        ad String name = str.format("item-%d", i);
        ad String hex = str.format("%02x", i & 0xff);
        same = b.id == i && b.small == i % 1000 && b.mid == i * 7 && b.price == (f64)i / 4.0 &&
               b.active == (i % 2 == 0) && b.name.equals(name) && b.label.equals("t\tab\\") &&
               b.blob.len == 2 && b.blob[1] == 1 && b.uid.endsWith(hex) &&
               b.at == 1700000000 + i && b.day.equals("2023-11-14") &&
               b.meta.contains("\"n\": ") && b.raw.startsWith("{") &&
               b.mood.equals(i % 3 == 0 ? cstr.view("cross") : cstr.view("calm")) &&
               ((i % 5 == 0) ? b.boss == null : (b.boss && *b.boss == 99)) &&
               b.wait.equals("1 day 00:00:00.000005");
        if (!same) { printf("\n  row %d differs: %s %s %s %s", i, b.name, b.uid, b.day, b.wait); }
    }
    t.ok(same, "every column read back as it was put (NULL from a null pointer)");
    pg.free(reflect.of(rows));
    t.ok(pg.queryOne<i64>(c, "SELECT count(*) FROM copybin WHERE extra = 'dflt'") == 1000,
         "a column no field names gets its default");

    /* refused before anything is sent */
    i64 code = 0;
    try { pg.CopyInto<NoCol> bad = c.copyInto<NoCol>("copybin"); bad.free(); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_ARG, "a field with no column: ERR_ARG");
    c.exec("CREATE TEMP TABLE copynum (id int8, amount numeric)");
    code = 0;
    try { pg.CopyInto<Num> bad = c.copyInto<Num>("copynum"); bad.free(); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_ARG, "a numeric column: ERR_ARG (no binary form here)");
    code = 0;
    try { pg.CopyInto<Item> bad = c.copyInto<Item>("no_such_table"); bad.free(); } catch (Error e) { code = e.code; }
    t.ok(code != 0, "a table that doesn't exist: the server's error");
    t.ok(pg.queryOne<i64>(c, "SELECT 1") == 1, "the connection still works");

    /* a value of the wrong kind: refused, the row not written, the COPY goes on */
    c.exec("CREATE TEMP TABLE copykinds (id int8, name text)");
    {
        ad pg.CopyInto<Wrong> w = c.copyInto<Wrong>("copykinds");
        Wrong x;
        x.id = str.from("7");
        x.name = str.from("n");
        code = 0;
        try { w.put(x); } catch (Error e) { code = e.code; }
        t.ok(code == pg.ERR_ARG, "a String into an int8 column: ERR_ARG before it is sent");
        x.id.free();
        x.name.free();
        w.abort();
    }
    {
        ad pg.CopyInto<Right> r = c.copyInto<Right>("copykinds");
        Right x;
        x.id = 1;
        x.name = str.from("one");
        r.put(x);
        x.name.free();
        t.ok(r.end() == 1, "a new COPY after an aborted one");
    }

    /* a server error at end: nothing stays */
    {
        pg.CopyInto<Right> dup = c.copyInto<Right>("copybin");
        Right x;
        x.id = 1;
        x.name = str.from("dup");
        dup.put(x);
        x.name.free();
        code = 0;
        try { dup.end(); } catch (Error e) { code = e.code; }
        t.ok(code != 0, "a duplicate key fails end()");
        t.ok(pg.queryOne<i64>(c, "SELECT count(*) FROM copybin") == 1000, "and nothing of it stays");
    }

    c.exec("DROP TABLE copybin");
    c.exec("DROP TYPE mood");
    c.close();
    return t.done();
}
