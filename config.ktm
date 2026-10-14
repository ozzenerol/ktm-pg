module pg;
import atomic, cstr, fs, libc, mem, net.tls, str, vec;

/* Where and how to connect: from a URL, then the PG* environment, then
 * the defaults */
struct Config
{
    String host;
    i32 port;
    String user;
    String password;
    String database;
    i64 connectTimeoutMs;
    bool sslSet;                /* the URL said sslmode */
    i64 sslMode;                /* SSL_DISABLE .. SSL_VERIFY_FULL */
    String sslRootCert;         /* the CAs to trust ("system": the system's) */
    String sslCert;             /* a client certificate, and its key */
    String sslKey;
    String sslPassword;
    i64 channelBinding;         /* CB_PREFER, CB_REQUIRE, CB_DISABLE */
    i64 tlsLock;
    tls.Context *tlsCtx;        /* made at the first TLS connection, shared by a pool's */
    vec.Vec<String> params;     /* runtime parameters: key, value, key, value ... */
    /* SCRAM's keys for the last salt and iteration count: a pool's
     * reconnects skip the PBKDF2 (thousands of HMACs). Guarded by
     * scramLock, as a pool's threads share the Config. */
    i64 scramLock;
    String scramSalt;
    i64 scramIter;
    u8 clientKey[32];
    u8 serverKey[32];
};

static void Config.free()
{
    self.host.free();
    self.user.free();
    self.password.free();
    self.database.free();
    self.scramSalt.free();
    self.sslRootCert.free();
    self.sslCert.free();
    self.sslKey.free();
    self.sslPassword.free();
    if (self.tlsCtx) { self.tlsCtx.free(); }
    self.params.free();         /* and its Strings */
}

static u8[] envOr(u8 *name)
{
    u8 *v = libc.getenv(name);
    if (!v) { return ""; }
    return cstr.view(v);
}

static String decoded(u8[] s, u8[] what)
{
    String out = str.new();
    if (!str.urlDecode(s, &out)) {
        out.free();
        throw(ERR_CONNECT, "pg: bad %% escape in the URL's %s", what);
    }
    return out;
}

static void configSet(Config *c, u8[] key, u8[] value)
{
    if (key.equals("host")) { c.host.free(); c.host = str.from(value); return; }
    if (key.equals("port")) {
        i64 p;
        if (!str.toInt(value, &p) || p <= 0 || p > 65535) { throw(ERR_CONNECT, "pg: bad port \"%s\"", value); }
        c.port = p;
        return;
    }
    if (key.equals("user")) { c.user.free(); c.user = str.from(value); return; }
    if (key.equals("password")) { c.password.free(); c.password = str.from(value); return; }
    if (key.equals("dbname")) { c.database.free(); c.database = str.from(value); return; }
    if (key.equals("connect_timeout")) {
        i64 s;
        if (!str.toInt(value, &s) || s < 0) { throw(ERR_CONNECT, "pg: bad connect_timeout \"%s\"", value); }
        c.connectTimeoutMs = s * 1000;
        return;
    }
    if (key.equals("sslmode")) {
        c.sslSet = true;
        if (value.equals("disable")) { c.sslMode = SSL_DISABLE; }
        else if (value.equals("allow")) { c.sslMode = SSL_ALLOW; }
        else if (value.equals("prefer")) { c.sslMode = SSL_PREFER; }
        else if (value.equals("require")) { c.sslMode = SSL_REQUIRE; }
        else if (value.equals("verify-ca")) { c.sslMode = SSL_VERIFY_CA; }
        else if (value.equals("verify-full")) { c.sslMode = SSL_VERIFY_FULL; }
        else { throw(ERR_CONNECT, "pg: unknown sslmode \"%s\"", value); }
        return;
    }
    if (key.equals("sslrootcert")) { c.sslRootCert.free(); c.sslRootCert = str.from(value); return; }
    if (key.equals("sslcert")) { c.sslCert.free(); c.sslCert = str.from(value); return; }
    if (key.equals("sslkey")) { c.sslKey.free(); c.sslKey = str.from(value); return; }
    if (key.equals("sslpassword")) { c.sslPassword.free(); c.sslPassword = str.from(value); return; }
    if (key.equals("channel_binding")) {
        if (value.equals("prefer")) { c.channelBinding = CB_PREFER; }
        else if (value.equals("require")) { c.channelBinding = CB_REQUIRE; }
        else if (value.equals("disable")) { c.channelBinding = CB_DISABLE; }
        else { throw(ERR_CONNECT, "pg: unknown channel_binding \"%s\"", value); }
        return;
    }
    if (key.equals("sslnegotiation") || key.equals("sslcompression") || key.equals("gssencmode")) { return; }
    c.params.push(str.from(key));
    c.params.push(str.from(value));
}

const i64 SSL_DISABLE = 0;
const i64 SSL_ALLOW = 1;
const i64 SSL_PREFER = 2;
const i64 SSL_REQUIRE = 3;
const i64 SSL_VERIFY_CA = 4;
const i64 SSL_VERIFY_FULL = 5;

const i64 CB_PREFER = 0;
const i64 CB_REQUIRE = 1;
const i64 CB_DISABLE = 2;

/* The TLS context for these settings, made on first use (one per pool) */
static tls.Context *Config.tlsContext()
{
    while (!atomic.cas(&self.tlsLock, 0, 1)) { }
    defer atomic.store(&self.tlsLock, 0);
    if (self.tlsCtx) { return self.tlsCtx; }
    tls.Config tc;
    mem.set(&tc, 0, sizeof(tls.Config));
    bool root = self.sslRootCert.len > 0;
    if (self.sslMode == SSL_VERIFY_FULL) { tc.verify = tls.Verify.full; }
    else if (self.sslMode == SSL_VERIFY_CA || (self.sslMode == SSL_REQUIRE && root)) { tc.verify = tls.Verify.chain; }
    else { tc.verify = tls.Verify.none; }
    if (root && !self.sslRootCert.equals("system")) { tc.caFile = self.sslRootCert; }
    if (self.sslCert.len) {
        tc.certFile = self.sslCert;
        tc.keyFile = self.sslKey;
        tc.keyPassword = self.sslPassword;
    }
    try {
        self.tlsCtx = tls.client(&tc);
    } catch (Error e) {
        throw(ERR_TLS, "pg: TLS settings: %s", e.message);
    }
    return self.tlsCtx;
}

/* A postgres:// or postgresql:// URL, or "" for the environment only */
static Config configParse(u8[] url)
{
    Config c;
    mem.set(&c, 0, sizeof(Config));
    c.params = vec.new<String>();
    try {
        u8[] rest = url;
        if (rest.len) {
            if (str.startsWith(rest, "postgres://")) { rest = rest[11:]; }
            else if (str.startsWith(rest, "postgresql://")) { rest = rest[13:]; }
            else { throw(ERR_CONNECT, "pg: a URL starts with postgres:// or postgresql://"); }
        }
        u8[] query = "";
        u8[] before, after;
        if (str.splitFirst(rest, "?", &before, &after)) { rest = before; query = after; }
        u8[] path = "";
        if (str.splitFirst(rest, "/", &before, &after)) { rest = before; path = after; }
        u8[] userinfo = "";
        if (str.splitLast(rest, "@", &before, &after)) { userinfo = before; rest = after; }
        if (userinfo.len) {
            if (str.splitFirst(userinfo, ":", &before, &after)) {
                c.user = decoded(before, "user");
                c.password = decoded(after, "password");
            } else {
                c.user = decoded(userinfo, "user");
            }
        }
        u8[] hostport = rest;
        if (hostport.len && hostport[0] == '[') {    /* [::1]:5432 */
            i64 close = str.find(hostport, "]");
            if (close < 0) { throw(ERR_CONNECT, "pg: missing ] in the URL's host"); }
            c.host = str.from(hostport[1:close]);
            hostport = hostport[close + 1:];
            if (hostport.len && hostport[0] == ':') { configSet(&c, "port", hostport[1:]); }
        } else if (str.splitLast(hostport, ":", &before, &after)) {
            c.host = decoded(before, "host");
            if (after.len) { configSet(&c, "port", after); }
        } else if (hostport.len) {
            c.host = decoded(hostport, "host");
        }
        if (path.len) { c.database = decoded(path, "database"); }
        while (query.len) {
            u8[] pair = query;
            if (str.splitFirst(query, "&", &before, &after)) { pair = before; query = after; } else { query = ""; }
            if (!pair.len) { continue; }
            u8[] k = pair, v = "";
            if (str.splitFirst(pair, "=", &before, &after)) { k = before; v = after; }
            ad String key = decoded(k, "query");
            ad String value = decoded(v, "query");
            configSet(&c, key, value);
        }
        if (!c.host.len) {
            u8[] h = envOr("PGHOST");
            c.host.free();
            c.host = str.from(h.len ? h : "localhost");
        }
        if (!c.port) {
            u8[] p = envOr("PGPORT");
            if (p.len) { configSet(&c, "port", p); } else { c.port = 5432; }
        }
        if (!c.user.len) {
            u8[] u = envOr("PGUSER");
            if (!u.len) { u = envOr("USER"); }
            c.user.free();
            c.user = str.from(u);
        }
        if (!c.password.len) { c.password.free(); c.password = str.from(envOr("PGPASSWORD")); }
        if (!c.database.len) {
            u8[] d = envOr("PGDATABASE");
            c.database.free();
            c.database = str.from(d.len ? d : c.user);
        }
        if (!c.connectTimeoutMs) {
            u8[] t = envOr("PGCONNECT_TIMEOUT");
            if (t.len) { configSet(&c, "connect_timeout", t); }
        }
        u8[] app = envOr("PGAPPNAME");
        if (app.len) {
            bool has = false;
            for (i64 i = 0; i < c.params.len; i += 2) {
                if (c.params.get(i).equals("application_name")) { has = true; }
            }
            if (!has) { configSet(&c, "application_name", app); }
        }
        u8[] ssl = envOr("PGSSLMODE");
        if (ssl.len && !c.sslSet) { configSet(&c, "sslmode", ssl); }
        if (!c.sslSet && !ssl.len) { c.sslMode = SSL_PREFER; }   /* libpq's default */
        u8[] e = envOr("PGSSLROOTCERT");
        if (e.len && !c.sslRootCert.len) { configSet(&c, "sslrootcert", e); }
        e = envOr("PGSSLCERT");
        if (e.len && !c.sslCert.len) { configSet(&c, "sslcert", e); }
        e = envOr("PGSSLKEY");
        if (e.len && !c.sslKey.len) { configSet(&c, "sslkey", e); }
        e = envOr("PGCHANNELBINDING");
        if (e.len && c.channelBinding == CB_PREFER) { configSet(&c, "channel_binding", e); }
        /* libpq's files in ~/.postgresql, when they exist */
        u8[] home = envOr("HOME");
        if (home.len) {
            ad String root = str.format("%s/.postgresql/root.crt", home);
            ad String crt = str.format("%s/.postgresql/postgresql.crt", home);
            ad String key = str.format("%s/.postgresql/postgresql.key", home);
            if (!c.sslRootCert.len && fs.exists(root)) { configSet(&c, "sslrootcert", root); }
            if (!c.sslCert.len && fs.exists(crt) && fs.exists(key)) {
                configSet(&c, "sslcert", crt);
                if (!c.sslKey.len) { configSet(&c, "sslkey", key); }
            }
        }
    } catch (Error e) {
        c.free();
        throw(e);
    }
    return c;
}
