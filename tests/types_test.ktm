import "github.com/ozzenerol/ktm-pg";
import cstr, date, json, mem, reflect, str, t, vec;

/* Types: parameters from ktm values, results read back by getter and by
 * scanning into structs */

enum Role { guest, admin };

struct Pair { i64 id; String name; };

/* no @json: an i64 * can't be JSON, but it scans */
struct Opt { i64 id; i64 *boss; u8 *note; };

@json struct Tag { String name; i64 weight; };

@json struct User
{
    i64 id;
    String name;
    @key("email_address") String email;
    Role role;
    f64 score;
    bool active;
    i32 small;
    u8[] avatar;
    u8 uuid[16];
    date.Date born;
    vec.Vec<Tag> tags;
    Tag *main;
    @skip i64 visits;
};

void getters(pg.Conn *c)
{
    ad pg.Rows r = c.query("SELECT 1::int2, -2::int4, 9007199254740993::int8, 1.5::float4, 0.1::float8, true, false,"
                           " '\\x00ff10'::bytea, '1999-12-31 23:59:59.999999'::timestamp,"
                           " '1969-07-20 20:17:40+00'::timestamptz, '0001-01-01'::date, 'infinity'::timestamp,"
                           " 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid, 123.4500::numeric, 'NaN'::float8, 42::oid");
    t.ok(r.next(), "a row");
    t.ok(r.int(0) == 1 && r.int(1) == -2 && r.int(2) == 9007199254740993, "ints");
    t.ok(r.float(3) == 1.5 && r.float(4) == 0.1, "floats");
    t.ok(r.bool(5) && !r.bool(6) && r.int(5) == 1, "bools");
    t.ok(r.bytes(7).len == 3 && r.bytes(7)[0] == 0 && r.bytes(7)[1] == 0xff, "bytea bytes");
    t.ok(r.text(8).equals("1999-12-31 23:59:59.999999"), "timestamp text");
    t.ok(r.micros(8) == 946684799999999, "timestamp micros");
    t.ok(r.text(9).equals("1969-07-20 20:17:40+00") && r.micros(9) == -14182940000000, "timestamptz before 1970");
    t.ok(r.text(10).equals("0001-01-01"), "date text");
    t.ok(r.micros(11) == I64_MAX && r.text(11).equals("infinity"), "infinity");
    t.ok(r.text(12).equals("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11") && r.bytes(12).len == 16, "uuid");
    t.ok(r.text(13).equals("123.4500") && r.float(13) == 123.45, "numeric");
    t.ok(r.float(14) != r.float(14) && r.text(14).equals("NaN"), "NaN");
    t.ok(r.int(15) == 42 && r.text(4).equals("0.1") && r.text(2).equals("9007199254740993"), "oid and text of binary");
    i64 code = 0;
    try { r.int(4); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_SCAN, "0.1 isn't an integer");
    code = 0;
    try { r.micros(0); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_SCAN, "an int isn't a timestamp");
    code = 0;
    try { r.int(99); } catch (Error e) { code = e.code; }
    t.ok(code == ERR_BOUNDS, "column out of range");
}

void params(pg.Conn *c)
{
    c.execScript("DROP TABLE IF EXISTS types_t;"
                 "CREATE TABLE types_t (i2 int2, i4 int4, i8 int8, f4 float4, f8 float8, b bool, by bytea,"
                 " ts timestamp, tz timestamptz, d date, u uuid, n numeric, j jsonb, tx text)");
    u8 all[256];
    for (i64 i = 0; i < 256; i++) { all[i] = i; }
    u8[] bin = all;
    u8 id[16];
    for (i64 i = 0; i < 16; i++) { id[i] = i * 17; }
    date.Date when = pg.dateFromMicros(1760445296123400);     /* 2025-10-14 12:34:56.1234 */
    Tag tag;
    tag.name = str.from("math");
    tag.weight = 3;
    defer tag.name.free();
    i16 i2 = -7;
    i64 n = c.exec("INSERT INTO types_t VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)",
                   i2, 2147483647, -9223372036854775807, 0.25, 1e300, true, bin, when, when, when, id, 3, tag, 42);
    t.ok(n == 1, "insert of every type");
    ad pg.Rows r = c.query("SELECT i2, i4, i8, f4, f8, b, by, ts, tz, d, u, n, j->>'name', tx FROM types_t");
    t.ok(r.next(), "read back");
    t.ok(r.int(0) == -7 && r.int(1) == 2147483647 && r.int(2) == -9223372036854775807, "ints back");
    t.ok(r.float(3) == 0.25 && r.float(4) == 1e300 && r.bool(5), "floats and bool back");
    bool same = r.bytes(6).len == 256;
    for (i64 i = 0; same && i < 256; i++) { if (r.bytes(6)[i] != i) { same = false; } }
    t.ok(same, "256 bytes of bytea back");
    t.ok(r.micros(7) == 1760445296123400 && r.micros(8) == 1760445296123400, "timestamps back");
    t.ok(r.text(9).equals("2025-10-14"), "date back");
    t.ok(r.text(10).equals("00112233-4455-6677-8899-aabbccddeeff"), "uuid from u8[16]");
    t.ok(r.text(11).equals("3") && r.text(12).equals("math") && r.text(13).equals("42"), "numeric, json, text");
    r.free();

    /* text parameters: the server converts */
    t.ok(c.exec("UPDATE types_t SET u = $1, n = $2, ts = $3, i8 = $4", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
                "1.25", "2001-02-03 04:05:06", "77") == 1, "text into uuid, numeric, timestamp, int8");
    r = c.query("SELECT u::text, n::float8, ts, i8 FROM types_t");
    t.ok(r.next() && r.text(0).equals("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11") && r.float(1) == 1.25
         && r.text(2).equals("2001-02-03 04:05:06") && r.int(3) == 77, "converted");
    r.free();

    /* range and NULL */
    i64 code = 0;
    try { c.exec("UPDATE types_t SET i2 = $1", 40000); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_ARG, "40000 into int2 is ERR_ARG");
    i64 *none = null;
    Role role = Role.admin;
    t.ok(c.exec("UPDATE types_t SET i8 = $1, tx = $2", none, role) == 1, "NULL and an enum");
    r = c.query("SELECT i8, tx FROM types_t");
    t.ok(r.next() && r.isNull(0) && r.text(1).equals("admin"), "NULL and enum back");
    r.free();
    i64 v = 5;
    i64 *p = &v;
    t.ok(pg.queryOne<i64>(c, "SELECT $1::int8 * 2", p) == 10, "a pointer sends what it points to");
    c.exec("DROP TABLE types_t");
}

void scanning(pg.Conn *c)
{
    c.execScript("DROP TABLE IF EXISTS users_t;"
                 "CREATE TABLE users_t (id int8, name text, email_address text, role text, score float8,"
                 " active bool, small int4, boss int8, avatar bytea, uuid uuid, born timestamptz, tags jsonb,"
                 " main jsonb, extra text)");
    c.exec("INSERT INTO users_t VALUES (1, 'ann', 'ann@x', 'admin', 9.5, true, 7, 2, '\\x0102', "
           "'00112233-4455-6677-8899-aabbccddeeff', '2025-10-14 12:00:00+00', "
           "'[{\"name\": \"a\", \"weight\": 1}, {\"name\": \"b\", \"weight\": 2}]', '{\"name\": \"m\"}', 'x')");
    c.exec("INSERT INTO users_t (id, name) VALUES (2, 'bob')");

    vec.Vec<User> us = pg.queryAll<User>(c, "SELECT * FROM users_t ORDER BY id");
    t.ok(us.len == 2, "2 users");
    User *a = us.at(0);
    t.ok(a.id == 1 && a.name.equals("ann") && a.email.equals("ann@x") && a.role == Role.admin, "scalars and @key");
    t.ok(a.score == 9.5 && a.active && a.small == 7, "float, bool, i32");
    t.ok(a.avatar.len == 2 && a.avatar[1] == 2 && a.uuid[15] == 0xff, "bytea into u8[] and u8[16]");
    t.ok(pg.microsFromDate(a.born) == 1760443200000000, "timestamptz into date.Date");
    t.ok(a.tags.len == 2 && a.tags.get(1).name.equals("b") && a.main && a.main.name.equals("m"), "jsonb into Vec and pointer");
    User *b = us.at(1);
    t.ok(b.id == 2 && b.email.len == 0 && b.tags.len == 0 && !b.main && b.visits == 0, "NULLs are zero and null");
    pg.free(reflect.of(us));

    vec.Vec<Opt> opts = pg.queryAll<Opt>(c, "SELECT id, boss, name AS note FROM users_t ORDER BY id");
    t.ok(opts.at(0).boss && *opts.at(0).boss == 2 && !opts.at(1).boss && cstr.view(opts.at(1).note).equals("bob"), "i64 * and u8 *");
    pg.free(reflect.of(opts));

    User one = pg.queryOne<User>(c, "SELECT id, name FROM users_t WHERE id = $1", 2);
    t.ok(one.id == 2 && one.name.equals("bob"), "queryOne");
    one.freeDecoded();
    i64 code = 0;
    try { User none = pg.queryOne<User>(c, "SELECT * FROM users_t WHERE id = 99"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_NO_ROWS, "queryOne with no row");
    t.ok(pg.queryOne<i64>(c, "SELECT count(*) FROM users_t") == 2, "a scalar");
    ad String nm = pg.queryOne<String>(c, "SELECT name FROM users_t WHERE id = 1");
    t.ok(nm.equals("ann"), "a String");
    vec.Vec<i64> ids = pg.queryAll<i64>(c, "SELECT id FROM users_t ORDER BY id DESC");
    t.ok(ids.len == 2 && ids.get(0) == 2, "a Vec of scalars");
    ids.free();

    /* strict */
    ad pg.Rows r = c.query("SELECT id, name, extra FROM users_t WHERE id = 1");
    r.next();
    User s;
    mem.set(&s, 0, sizeof(User));
    code = 0;
    try { r.scan(reflect.of(s), pg.STRICT); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_SCAN, "strict: a column with no field");
    pg.free(reflect.of(s));
    r.free();
    r = c.query("SELECT id, name FROM users_t ORDER BY id");
    Pair pr;
    mem.set(&pr, 0, sizeof(Pair));
    r.next();
    r.scan(reflect.of(pr), pg.STRICT);
    t.ok(pr.id == 1 && pr.name.equals("ann"), "strict: exact columns");
    pr.name.free();
    r.next();
    r.scan(reflect.of(pr), pg.STRICT);
    t.ok(pr.id == 2 && pr.name.equals("bob"), "the second row, same mapping");
    pr.name.free();
    r.free();
    r = c.query("SELECT id, email_address AS name FROM users_t WHERE id = 2");
    r.next();
    code = 0;
    mem.set(&pr, 0, sizeof(Pair));
    try { r.scan(reflect.of(pr), pg.STRICT); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_SCAN, "strict: NULL into a String");
    pg.free(reflect.of(pr));
    r.free();

    /* a bad value frees what was filled */
    code = 0;
    try {
        vec.Vec<User> bad = pg.queryAll<User>(c, "SELECT id, name, 'x' AS small FROM users_t");
    } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_SCAN, "text into an i32 is ERR_SCAN");
    code = 0;
    try { i64 x = pg.queryOne<i64>(c, "SELECT 'nope'::text"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_SCAN, "text into i64 is ERR_SCAN");

    /* in a transaction */
    pg.Tx tx = c.begin();
    defer tx.rollback();
    tx.exec("DELETE FROM users_t WHERE id = 2");
    t.ok(pg.queryOne<i64>(&tx, "SELECT count(*) FROM users_t") == 1, "queryOne on a Tx");
    vec.Vec<User> inTx = pg.queryAll<User>(&tx, "SELECT id, name FROM users_t");
    t.ok(inTx.len == 1, "queryAll on a Tx");
    pg.free(reflect.of(inTx));
    tx.rollback();
    c.exec("DROP TABLE users_t");
}

/* numeric is exact into i64 and String, never through a float */
struct Money { i64 cents; String amount; f64 approx; };

void numeric(pg.Conn *c)
{
    ad pg.Rows r = c.query("SELECT 9007199254740993::numeric, 99999999999999999.00::numeric, -9223372036854775808::numeric,"
                           " 9223372036854775808::numeric, 12.50::numeric, 'NaN'::numeric, 0.10::numeric, '-0'::numeric,"
                           " 12345678901234567890.123456789::numeric");
    t.ok(r.next(), "a row");
    t.ok(r.int(0) == 9007199254740993, "2^53 + 1 exact (a float would say ...992)");
    t.ok(r.int(1) == 99999999999999999, "a zero fraction is fine, exact");
    t.ok(r.int(2) == I64_MIN, "I64_MIN");
    i64 code = 0;
    try { r.int(3); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_SCAN, "I64_MAX + 1 is ERR_SCAN");
    code = 0;
    try { r.int(4); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_SCAN, "12.50 into an integer is ERR_SCAN");
    code = 0;
    try { r.int(5); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_SCAN, "NaN into an integer is ERR_SCAN");
    t.ok(r.float(6) == 0.1 && r.int(7) == 0, "f64 is the nearest float; -0 is 0");
    t.ok(r.text(8).equals("12345678901234567890.123456789"), "text is exact");
    r.free();
    vec.Vec<Money> m = pg.queryAll<Money>(c, "SELECT 123456789012345678::numeric AS cents, 1234567890.12345678901234::numeric AS amount, 2.5::numeric AS approx");
    t.ok(m.len == 1 && m.at(0).cents == 123456789012345678 && m.at(0).amount.equals("1234567890.12345678901234") && m.at(0).approx == 2.5, "scanned exact into i64 and String");
    pg.free(reflect.of(m));
    code = 0;
    try { i64 x = pg.queryOne<i64>(c, "SELECT 1.5::numeric"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.ERR_SCAN, "1.5 into an i64 field is ERR_SCAN");
}

void dates()
{
    i64 us[] = {0, 1, -1, 946684800000000, -62135596800000000, 253402300799999900, 1760445296123400};
    bool good = true;
    for (auto u : us) {
        if (pg.microsFromDate(pg.dateFromMicros(u)) != u / 100 * 100 - (u < 0 && u % 100 ? 100 : 0)) { good = false; }
    }
    t.ok(good, "date.Date and micros round trip (to 1/10000 s)");
}

i32 main()
{
    t.start("types");
    ad String u = t.url();
    pg.Conn *c = pg.connect(u);
    getters(c);
    params(c);
    scanning(c);
    numeric(c);
    dates();
    c.close();
    return t.done();
}
