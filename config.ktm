module pg;
import cstr, libc, mem, str, vec;

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
    vec.Vec<String> params;     /* runtime parameters: key, value, key, value ... */
};

static void Config.free()
{
    self.host.free();
    self.user.free();
    self.password.free();
    self.database.free();
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
        if (value.equals("disable") || value.equals("allow") || value.equals("prefer")) { return; }
        throw(ERR_CONNECT, "pg: sslmode=%s needs TLS, which ktm-pg doesn't have yet: use sslmode=disable", value);
    }
    c.params.push(str.from(key));
    c.params.push(str.from(value));
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
    } catch (Error e) {
        c.free();
        throw(e);
    }
    return c;
}
