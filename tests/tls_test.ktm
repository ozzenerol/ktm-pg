import "github.com/ozzenerol/ktm-pg";
import coro, cstr, date, str, t;

/* TLS: sslmode, certificate checks, client certificates, SCRAM-SHA-256-PLUS,
 * cancel and the pool over TLS (the test servers use tests/tls) */

i64 gCode;
String gTls;

/* Connects and returns the error code (0: it worked); gTls: its TLS version */
i64 tryConnect(u8[] url)
{
    gTls.clear();
    try {
        pg.Conn *c = pg.connect(url);
        gTls.append(c.tlsVersion());
        if (pg.queryOne<i64>(c, "SELECT 1") != 1) { c.close(); return -1; }
        c.close();
    } catch (Error e) {
        if (t.verbose()) { printf("\n    (%s)", e.message); }
        return e.code;
    }
    return 0;
}

String url(u8[] user, u8[] password, u8[] query, u8[] host = "127.0.0.1")
{
    String u = str.format("postgres://%s", user);
    if (password.len) { u.appendFormat(":%s", password); }
    ad String pt = t.port();
    u.appendFormat("@%s:%s/ktm_test?%s", host, pt, query);
    return u;
}

void modes()
{
    ad String prefer = url("postgres", "ktm", "");
    t.ok(tryConnect(prefer) == 0 && gTls.len > 0, "prefer (the default) uses TLS");
    ad String disable = url("postgres", "ktm", "sslmode=disable");
    t.ok(tryConnect(disable) == 0 && gTls.len == 0, "disable: plain");
    ad String require = url("postgres", "ktm", "sslmode=require");
    t.ok(tryConnect(require) == 0 && str.startsWith(gTls, "TLSv1."), "require");
    ad String ca = url("postgres", "ktm", "sslmode=verify-ca&sslrootcert=tls/ca.pem");
    t.ok(tryConnect(ca) == 0, "verify-ca with the right CA");
    ad String other = url("postgres", "ktm", "sslmode=verify-ca&sslrootcert=tls/other-ca.pem");
    t.ok(tryConnect(other) == pg.ERR_TLS, "verify-ca with another CA: ERR_TLS");
    ad String reqOther = url("postgres", "ktm", "sslmode=require&sslrootcert=tls/other-ca.pem");
    t.ok(tryConnect(reqOther) == pg.ERR_TLS, "require with sslrootcert checks the chain, as libpq");
    ad String full = url("postgres", "ktm", "sslmode=verify-full&sslrootcert=tls/ca.pem");
    t.ok(tryConnect(full) == 0, "verify-full: 127.0.0.1 is in the certificate");
    ad String byName = url("postgres", "ktm", "sslmode=verify-full&sslrootcert=tls/ca.pem", "localhost");
    t.ok(tryConnect(byName) == 0, "verify-full: localhost too");
    ad String wrong = url("postgres", "ktm", "sslmode=verify-full&sslrootcert=tls/ca.pem", "127.0.0.2");
    t.ok(tryConnect(wrong) == pg.ERR_TLS, "verify-full: another name is ERR_TLS");
    ad String noCa = url("postgres", "ktm", "sslmode=verify-ca&sslrootcert=tls/nope.pem");
    t.ok(tryConnect(noCa) == pg.ERR_TLS, "a CA file that isn't there: ERR_TLS");
    ad String bad = url("postgres", "ktm", "sslmode=sometimes");
    t.ok(tryConnect(bad) == pg.ERR_CONNECT, "an unknown sslmode");
}

void users()
{
    ad String sslOnly = url("ktm_ssl", "sslpw", "sslmode=disable");
    t.ok(tryConnect(sslOnly) == pg.state("28000"), "a hostssl-only user without TLS is refused");
    ad String sslOk = url("ktm_ssl", "sslpw", "sslmode=require");
    t.ok(tryConnect(sslOk) == 0, "and connects with TLS");
    ad String cert = url("ktm_cert", "", "sslmode=verify-full&sslrootcert=tls/ca.pem&sslcert=tls/client.pem&sslkey=tls/client.key");
    t.ok(tryConnect(cert) == 0, "a client certificate logs in (cert auth)");
    ad String noCert = url("ktm_cert", "", "sslmode=require");
    t.ok(tryConnect(noCert) != 0, "without the certificate it doesn't");
    ad String badKey = url("ktm_cert", "", "sslmode=require&sslcert=tls/client.pem&sslkey=tls/server.key");
    t.ok(tryConnect(badKey) == pg.ERR_TLS, "a key that doesn't match: ERR_TLS");
}

void binding()
{
    ad String plus = url("ktm_scram", "scrampw", "sslmode=require&channel_binding=require");
    t.ok(tryConnect(plus) == 0, "SCRAM-SHA-256-PLUS (channel_binding=require)");
    ad String plusPostgres = url("postgres", "ktm", "channel_binding=require");
    t.ok(tryConnect(plusPostgres) == 0, "the same with prefer TLS");
    ad String noTls = url("ktm_scram", "scrampw", "sslmode=disable&channel_binding=require");
    t.ok(tryConnect(noTls) == pg.ERR_AUTH, "channel_binding=require without TLS: ERR_AUTH");
    ad String md5 = url("ktm_md5", "md5pw", "sslmode=require&channel_binding=require");
    t.ok(tryConnect(md5) == pg.ERR_AUTH, "channel_binding=require with an md5 user: ERR_AUTH");
    ad String trust = url("ktm_trust", "", "sslmode=require&channel_binding=require");
    t.ok(tryConnect(trust) == pg.ERR_AUTH, "channel_binding=require with trust: ERR_AUTH");
    ad String off = url("ktm_scram", "scrampw", "sslmode=require&channel_binding=disable");
    t.ok(tryConnect(off) == 0, "channel_binding=disable: plain SCRAM over TLS");
    ad String wrongPw = url("ktm_scram", "nope", "sslmode=require&channel_binding=require");
    t.ok(tryConnect(wrongPw) == pg.INVALID_PASSWORD, "a wrong password is still 28P01");
}

void cancelAndBig()
{
    ad String u = url("postgres", "ktm", "sslmode=require");
    pg.Conn *c = pg.connect(u);
    c.setQueryTimeout(200);
    i64 code = 0;
    i64 start = date.monoMs();
    try { c.exec("SELECT pg_sleep(5)"); } catch (Error e) { code = e.code; }
    t.ok(code == pg.QUERY_CANCELED && date.monoMs() - start < 3000, "a timeout cancels over TLS");
    c.setQueryTimeout(0);
    ad pg.Rows r = c.query("SELECT repeat('x', 3000000)");
    t.ok(r.next() && r.text(0).len == 3000000, "3 MB over TLS");
    r.free();
    c.close();
}

/* the pool over TLS, from coroutines */
pg.Pool *gDb;
i64 gOk;

void *worker(void *arg)
{
    for (i64 i = 0; i < 20; i++) {
        if (pg.queryOne<i64>(gDb, "SELECT $1::int8 + 1", i) == i + 1) { gOk++; }
    }
    return null;
}

void pooled()
{
    ad String u = url("ktm_scram", "scrampw", "sslmode=verify-full&sslrootcert=tls/ca.pem&channel_binding=require");
    pg.Options o;
    o.maxConns = 3;
    gDb = pg.open(u, &o);
    gOk = 0;
    for (i64 i = 0; i < 20; i++) { coro.go(&worker); }
    coro.run();
    t.ok(gOk == 400 && gDb.openCount() <= 3, "a TLS pool: 400 queries from 20 coroutines");
    gDb.close();
}

i32 main()
{
    t.start("tls");
    modes();
    users();
    binding();
    cancelAndBig();
    pooled();
    gTls.free();
    return t.done();
}
