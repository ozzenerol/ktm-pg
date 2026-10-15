module t;
import cstr, libc, os, str;

/* The test helpers: a check counter and the URL of the server under test
 * (PGVER, default 17; T_PG_NAME and T_PG_PORT for other containers) */

i64 gChecks = 0;
i64 gFailed = 0;
u8[] gName = "";

pub void start(u8[] name)
{
    gName = name;
    printf("Test - %s: ", name);
}

/* Whether T_VERBOSE is set: print every check */
pub bool verbose()
{
    u8 *v = libc.getenv("T_VERBOSE");
    return v && v[0];
}

pub void ok(bool cond, u8 *what)
{
    gChecks++;
    u8 *verbose = libc.getenv("T_VERBOSE");
    if (verbose && verbose[0]) { printf("\n  %s: %s", cond ? "ok" : "no", what); os.flush(); }
    if (!cond) {
        gFailed++;
        printf("\n  FAIL: %s", what);
    }
}

/* Ends the test: its result line, and the exit code */
pub i32 done()
{
    if (gFailed) {
        printf("\n%s: %d of %d checks FAILED\n", gName, gFailed, gChecks);
        return 1;
    }
    if (verbose()) { printf("\n"); }
    printf("%d/%d passed\n", gChecks, gChecks);
    return 0;
}

pub u8[] version()
{
    u8 *v = libc.getenv("PGVER");
    if (!v || !v[0]) { return "17"; }
    return cstr.view(v);
}

u8[] envOr(u8 *name, u8[] dflt)
{
    u8 *v = libc.getenv(name);
    if (!v || !v[0]) { return dflt; }
    return cstr.view(v);
}

/* The server's container: <T_PG_NAME>-<PGVER> (default ktm-pg-17) */
pub String container()
{
    return str.format("%s-%s", envOr("T_PG_NAME", "ktm-pg"), version());
}

/* The server's port: <T_PG_PORT><PGVER> (default 55417) */
pub String port()
{
    return str.format("%s%s", envOr("T_PG_PORT", "554"), version());
}

/* The URL for user and password (or none) on the test server, database ktm_test */
pub String url(u8[] user = "postgres", u8[] password = "ktm", u8[] extra = "")
{
    String u = str.format("postgres://%s", user);
    if (password.len) { u.appendFormat(":%s", password); }
    scoped String p = port();
    u.appendFormat("@127.0.0.1:%s/ktm_test%s", p, extra);
    return u;
}
