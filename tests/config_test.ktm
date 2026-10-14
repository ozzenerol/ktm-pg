import "github.com/ozzenerol/ktm-pg";
import date, str, t;

/* Connection URLs: escapes, IPv6, ports, options, timeouts */

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

i32 main()
{
    t.start("config");
    ad String admin = t.url();
    pg.Conn *c = pg.connect(admin);
    c.execScript("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ktm odd') THEN "
                 "CREATE USER \"ktm odd\" PASSWORD 'p@ss:w/rd%?&='; END IF; END $$");
    c.close();

    ad String odd = str.format("postgres://ktm%%20odd:p%%40ss%%3Aw%%2Frd%%25%%3F%%26%%3D@127.0.0.1:554%s/ktm_test", t.version());
    t.ok(tryConnect(odd) == 0, "escaped user and password");
    ad String v6 = str.format("postgresql://postgres:ktm@[::1]:554%s/ktm_test", t.version());
    i64 code = tryConnect(v6);
    t.ok(code == 0 || code == pg.ERR_CONNECT, "an IPv6 host parses");
    t.ok(tryConnect("postgres://postgres:ktm@127.0.0.1:99999/x") == pg.ERR_CONNECT, "a bad port");
    t.ok(tryConnect("postgres://postgres:ktm@127.0.0.1:abc/x") == pg.ERR_CONNECT, "a port that isn't a number");
    t.ok(tryConnect("postgres://u:p@h/db?x=%zz") == pg.ERR_CONNECT, "a bad escape");
    t.ok(tryConnect("postgres://u:p@[::1/db") == pg.ERR_CONNECT, "a missing ]");

    /* connect_timeout: an address that never answers */
    i64 start = date.monoMs();
    code = tryConnect("postgres://u:p@10.255.255.1:5432/db?connect_timeout=1");
    i64 took = date.monoMs() - start;
    t.ok(code == pg.ERR_CONNECT && took >= 900 && took < 3000, "connect_timeout=1");

    /* options become runtime parameters */
    ad String opts = t.url("postgres", "ktm", "?sslmode=disable&search_path=pg_catalog&statement_timeout=1234&TimeZone=Asia/Tokyo");
    c = pg.connect(opts);
    ad pg.Rows r = c.query("SELECT current_setting('search_path'), current_setting('statement_timeout'), current_setting('TimeZone')");
    t.ok(r.next() && r.text(0).equals("pg_catalog") && r.text(1).equals("1234ms") && r.text(2).equals("Asia/Tokyo"), "runtime parameters");
    r.free();
    /* an unknown parameter is the server's error */
    c.close();
    ad String unknown = t.url("postgres", "ktm", "?no_such_setting=1");
    t.ok(tryConnect(unknown) == pg.state("42704"), "an unknown parameter is 42704");
    return t.done();
}
