import "github.com/ozzenerol/ktm-pg";
import cstr, json, reflect, str, t, vec;

/* queryResult: every row in one Result, its text and bytes in one arena,
 * freed together; the same values as queryAll. -Memsafe (tests/run.sh's
 * JIT runs) checks nothing is left behind and nothing is freed twice. */

@json struct Tag { String name; i64 weight; };

struct Row
{
    i64 id;
    String name;
    u8[] blob;
    u8 *note;
    i64 *boss;              /* its own allocation, freed by Result.free */
    vec.Vec<i64> nums;      /* the same */
    Tag *tag;               /* a JSON column, the same */
    @skip i64 visits;
};

i32 main()
{
    t.start("result");
    scoped String u = t.url();
    pg.Conn *c = pg.connect(u);

    u8[] sql = "SELECT g AS id, 'name-' || g AS name, decode(lpad(to_hex(g), 4, '0'), 'hex') AS blob,"
               " CASE WHEN g % 2 = 0 THEN 'even' END AS note, CASE WHEN g % 3 = 0 THEN g * 10 END AS boss,"
               " ARRAY[g, g + 1] AS nums, json_build_object('name', 'tag' || g, 'weight', g)::json AS tag"
               " FROM generate_series(1, $1::int) g";
    {
        scoped pg.Result<Row> res = pg.queryResult<Row>(c, sql, 1000);
        t.ok(res.len == 1000, "1000 rows");
        bool same = true;
        i64 seen = 0;
        for (auto r : res) {
            seen++;
            scoped String want = str.format("name-%d", r.id);
            same = same && r.id == seen && r.name.equals(want) && r.name.ptr[r.name.len] == 0 &&
                   r.blob.len == 2 && r.blob[1] == (u8)(r.id & 0xff) &&
                   ((r.id % 2 == 0) ? (r.note && str.equals(cstr.view(r.note), "even")) : r.note == null) &&
                   ((r.id % 3 == 0) ? (r.boss && *r.boss == r.id * 10) : r.boss == null) &&
                   r.nums.len == 2 && r.nums.data[1] == r.id + 1 &&
                   r.tag && r.tag.weight == r.id;
        }
        t.ok(same && seen == 1000, "every value as queryAll gives it, the text NUL-terminated");
        t.ok(res.at(999).id == 1000 && res.at(0).name.equals("name-1"), "at(i)");
        i64 code = 0;
        try { res.at(1000); } catch (Error e) { code = e.code; }
        t.ok(code == ERR_BOUNDS, "at past the end throws ERR_BOUNDS");

        /* queryAll gives the same rows */
        vec.Vec<Row> all = pg.queryAll<Row>(c, sql, 1000);
        bool equal = all.len == res.len;
        for (i64 i = 0; i < all.len && equal; i++) {
            equal = all.data[i].id == res.data[i].id && all.data[i].name.equals(res.data[i].name) &&
                    all.data[i].blob.len == res.data[i].blob.len;
        }
        t.ok(equal, "the same rows as queryAll");
        pg.free(reflect.of(all));

        /* a value kept past the Result: cloned */
        String keep = res.at(41).name.clone();
        res.free();
        t.ok(res.len == 0 && res.data == null, "free empties it (and scoped's free after it is harmless)");
        t.ok(keep.equals("name-42"), "a clone outlives it");
        keep.free();
    }

    /* a scalar and a String per row */
    {
        scoped pg.Result<i64> ns = pg.queryResult<i64>(c, "SELECT g FROM generate_series(1, 5) g");
        i64 sum = 0;
        for (auto n : ns) { sum += n; }
        t.ok(ns.len == 5 && sum == 15, "a Result<i64>");
        scoped pg.Result<String> ss = pg.queryResult<String>(c, "SELECT 'x' || g FROM generate_series(1, 3) g");
        t.ok(ss.len == 3 && ss.at(2).equals("x3"), "a Result<String>");
    }

    /* no rows, and an error half way: nothing left behind */
    {
        scoped pg.Result<Row> none = pg.queryResult<Row>(c, sql, 0);
        t.ok(none.len == 0 && none.data == null, "no rows");
        i64 code = 0;
        try {
            pg.Result<Row> bad = pg.queryResult<Row>(c, "SELECT g AS id, 'n' AS name, 1 / (g - 3) AS boss"
                                                        " FROM generate_series(1, 5) g");
            bad.free();
        } catch (Error e) { code = e.code; }
        t.ok(code != 0, "an error after two rows throws, and frees what was read");
        t.ok(pg.queryOne<i64>(c, "SELECT 7") == 7, "the connection still works");
    }

    /* through a pool, and a big one: the arena grows block by block */
    {
        pg.Pool *p = pg.open(u);
        scoped pg.Result<Row> big = pg.queryResult<Row>(p, "SELECT g AS id, repeat('y', 300) AS name"
                                                        " FROM generate_series(1, 20000) g");
        t.ok(big.len == 20000 && big.at(19999).name.len == 300 && big.at(0).name.ptr[0] == 'y',
             "20000 rows of 300 bytes through a pool");
        p.close();
    }

    c.close();
    return t.done();
}
