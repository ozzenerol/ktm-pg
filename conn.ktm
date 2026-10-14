module pg;
import date, hash, libc, mem, os, reflect, str;

/* What a Conn, a Tx and a Pool have in common: pg.queryAll and
 * pg.queryOne take any of them as a Db * */
pub struct Db
{
    private fn(Db *, u8[], reflect.Value[]) Rows open;
};

/* A connection to the server. One coroutine uses it at a time; share
 * connections through a Pool. */
pub struct Conn : Db
{
    private Stream s;
    private u8 *in;             /* received bytes: in[inStart:inEnd] not parsed yet */
    private i64 inCap;
    private i64 inStart;
    private i64 inEnd;
    private u8 *out;            /* messages not sent yet */
    private i64 outLen;
    private i64 outCap;
    private i64 msgStart;       /* where the message being written starts in out */
    private u8 msgType;         /* the message just received */
    private u8[] msg;           /* its body, a view into in */
    private i32 pid;
    private i32 secret;
    private u8 txStatus;        /* 'I' idle, 'T' in a transaction, 'E' in a failed one */
    private bool dead;
    private bool busy;          /* Rows are open on it */
    private Stmt *stmts;        /* the statement cache */
    private i64 nstmts;
    private i64 stmtSeq;        /* the next statement's name: ktm_<n> */
    private i64 clock;          /* use count, for the least recently used statement */
    private String version;     /* server_version */
    private void *pool;         /* the Pool it came from, or null */
    private i64 born;           /* date.monoMs() when it connected */
    private i64 idleSince;
    private void *shard;
};

/* A prepared statement of the cache */
struct Stmt
{
    String sql;
    u64 hash;
    u8 name[24];
    i64 nameLen;
    i64 lastUse;
    u32 *paramOids;
    i64 nparams;
    Column *cols;
    i64 ncols;
};

/* A result column */
struct Column
{
    String name;
    u32 oid;
    i16 format;     /* 0 text, 1 binary: what Bind asks for */
};

const i64 STMT_CACHE = 256;
const i64 IN_FIRST = 16384;
const i64 OUT_FIRST = 4096;

/* ---- writing messages ---- */

static void Conn.room(i64 n)
{
    if (self.outLen + n <= self.outCap) { return; }
    i64 cap = self.outCap ? self.outCap : OUT_FIRST;
    while (cap < self.outLen + n) { cap *= 2; }
    self.out = mem.realloc(self.out, cap);
    self.outCap = cap;
}

static void Conn.put8(u8 b)
{
    self.room(1);
    self.out[self.outLen++] = b;
}

static void Conn.put16(i64 v)
{
    self.room(2);
    self.out[self.outLen] = (v >> 8) & 0xFF;
    self.out[self.outLen + 1] = v & 0xFF;
    self.outLen += 2;
}

static void Conn.put32(i64 v)
{
    self.room(4);
    u8 *p = self.out + self.outLen;
    p[0] = (v >> 24) & 0xFF; p[1] = (v >> 16) & 0xFF; p[2] = (v >> 8) & 0xFF; p[3] = v & 0xFF;
    self.outLen += 4;
}

static void Conn.putBytes(u8[] b)
{
    self.room(b.len);
    mem.copy(self.out + self.outLen, b.ptr, b.len);
    self.outLen += b.len;
}

/* bytes and a NUL: a protocol string */
static void Conn.putStr(u8[] b)
{
    self.putBytes(b);
    self.put8(0);
}

/* Start a message of type t (0: the start-up message, which has none) */
static void Conn.begin(u8 t)
{
    if (t) { self.put8(t); }
    self.msgStart = self.outLen;
    self.put32(0);
}

/* The message started last is complete: fill in its length */
static void Conn.end()
{
    i64 n = self.outLen - self.msgStart;
    u8 *p = self.out + self.msgStart;
    p[0] = (n >> 24) & 0xFF; p[1] = (n >> 16) & 0xFF; p[2] = (n >> 8) & 0xFF; p[3] = n & 0xFF;
}

/* The connection broke: close it and throw ERR_IO */
static void Conn.broken(u8 *what)
{
    i32 err = os.errno();
    self.dead = true;
    self.s.close(&self.s);
    throw(ERR_IO, "pg: connection lost while %s: %s", what, libc.strerror(err));
}

static void Conn.flush()
{
    if (self.dead) { throw(ERR_CLOSED, "pg: the connection is closed"); }
    if (!self.outLen) { return; }
    bool ok = self.s.write(&self.s, self.out[0:self.outLen]);
    self.outLen = 0;
    if (!ok) { self.broken("sending"); }
}

/* ---- reading messages ---- */

/* At least n unparsed bytes in `in` */
static void Conn.fill(i64 n)
{
    if (self.inEnd - self.inStart >= n) { return; }
    if (self.inStart && (self.inStart == self.inEnd || self.inStart + n > self.inCap)) {
        i64 have = self.inEnd - self.inStart;
        if (have) { libc.memmove(self.in, self.in + self.inStart, have); }
        self.inStart = 0;
        self.inEnd = have;
    }
    if (n > self.inCap) {
        i64 cap = self.inCap ? self.inCap : IN_FIRST;
        while (cap < n) { cap *= 2; }
        self.in = mem.realloc(self.in, cap);
        self.inCap = cap;
    }
    while (self.inEnd - self.inStart < n) {
        i64 got = self.s.read(&self.s, self.in[self.inEnd:self.inCap]);
        if (got <= 0) {
            if (got == 0) { os.setErrno(libc.ECONNRESET); }
            self.broken("receiving");
        }
        self.inEnd += got;
    }
}

/* The next message, whatever it is: msgType and msg (valid until the next read) */
static u8 Conn.readRaw()
{
    self.fill(5);
    u8 *p = self.in + self.inStart;
    i64 len = (i64)p[1] << 24 | (i64)p[2] << 16 | (i64)p[3] << 8 | p[4];
    if (len < 4 || len > 0x40000000) {
        self.dead = true;
        self.s.close(&self.s);
        throw(ERR_PROTOCOL, "pg: a message of %d bytes", len);
    }
    self.fill(1 + len);
    p = self.in + self.inStart;
    self.msgType = p[0];
    self.msg = p[5:1 + len];
    self.inStart += 1 + len;
    return self.msgType;
}

/* The next message that isn't one the server sends at any time
 * (ParameterStatus, NoticeResponse, NotificationResponse) */
static u8 Conn.recv()
{
    while (true) {
        u8 t = self.readRaw();
        if (t == 'S') {
            u8[] name = cstrAt(self.msg, 0);
            if (name.equals("server_version")) {
                self.version.free();
                self.version = str.from(cstrAt(self.msg, name.len + 1));
            }
            continue;
        }
        if (t == 'N' || t == 'A') { continue; }
        return t;
    }
    return 0;
}

/* The NUL-terminated string at b[at:] */
static u8[] cstrAt(u8[] b, i64 at)
{
    i64 i = at;
    while (i < b.len && b.ptr[i]) { i++; }
    return b[at:i];
}

static i64 be32At(u8[] b, i64 at)
{
    if (at + 4 > b.len) { throw(ERR_PROTOCOL, "pg: a message too short"); }
    return (i32)((u32)b.ptr[at] << 24 | (u32)b.ptr[at + 1] << 16 | (u32)b.ptr[at + 2] << 8 | b.ptr[at + 3]);
}

static i64 be16At(u8[] b, i64 at)
{
    if (at + 2 > b.len) { throw(ERR_PROTOCOL, "pg: a message too short"); }
    return (i16)((u16)b.ptr[at] << 8 | b.ptr[at + 1]);
}

/* An ErrorResponse's fields as an error: its SQLSTATE as the code and a
 * message with the detail and the hint */
struct ServerError
{
    i64 code;
    String message;
};

static ServerError serverError(u8[] m)
{
    ServerError e;
    e.code = 0;
    e.message = str.new();
    u8[] text = "", detail = "", hint = "", sev = "";
    i64 i = 0;
    while (i < m.len && m[i]) {
        u8 f = m[i];
        u8[] v = cstrAt(m, i + 1);
        i += v.len + 2;
        if (f == 'C') { e.code = state(v); }
        else if (f == 'M') { text = v; }
        else if (f == 'D') { detail = v; }
        else if (f == 'H') { hint = v; }
        else if (f == 'V') { sev = v; }
    }
    if (!e.code) { e.code = ERR_PROTOCOL; }
    ad String st = stateText(e.code);
    if (sev.len && !sev.equals("ERROR")) { e.message.appendFormat("%s: ", sev); }
    e.message.appendFormat("%s (SQLSTATE %s)", text, st);
    if (detail.len) { e.message.appendFormat("; detail: %s", detail); }
    if (hint.len) { e.message.appendFormat("; hint: %s", hint); }
    return e;
}

/* After an ErrorResponse: read up to ReadyForQuery, then throw it */
static void Conn.throwServerError()
{
    ServerError e = serverError(self.msg);
    defer e.message.free();
    self.drain();
    throw(e.code, "%s", e.message);
}

/* Read up to and including ReadyForQuery */
static void Conn.drain()
{
    while (self.recv() != 'Z') { }
    self.txStatus = self.msg.len ? self.msg[0] : 'I';
}

/* ---- connecting ---- */

/* A connection to the server the URL names:
 * postgres://user:password@host:port/database?key=value&...
 * Parts the URL leaves out come from PGHOST, PGPORT, PGUSER, PGPASSWORD,
 * PGDATABASE, PGAPPNAME, PGCONNECT_TIMEOUT and PGSSLMODE, then the
 * defaults (localhost, 5432, $USER, the user's name). A host starting with /
 * is the directory of a Unix socket. Throws ERR_CONNECT when it can't
 * connect, ERR_AUTH for an authentication it can't do, and the server's
 * error (28P01 INVALID_PASSWORD, 3D000 no such database ...). Close it
 * with c.close(). */
pub Conn *connect(u8[] url = "")
{
    ad Config cfg = configParse(url);
    return connectConfig(&cfg);
}

static Conn *connectConfig(Config *cfg)
{
    Conn *c = mem.allocZero(sizeof(Conn));
    c.open = &connOpen;
    c.s.fd = -1;
    if (!streamDial(&c.s, cfg.host, cfg.port, cfg.connectTimeoutMs)) {
        i32 err = os.errno();
        mem.free(c);
        throw(ERR_CONNECT, "pg: can't connect to %s:%d: %s", cfg.host, cfg.port, libc.strerror(err));
    }
    c.born = date.monoMs();
    try {
        c.startup(cfg);
    } catch (Error e) {
        c.release_();
        throw(e);
    }
    return c;
}

static void Conn.startup(Config *cfg)
{
    if (cfg.connectTimeoutMs > 0) { self.s.timeoutMs = cfg.connectTimeoutMs; }
    self.begin(0);
    self.put32(196608);     /* protocol 3.0 */
    self.putStr("user");
    self.putStr(cfg.user);
    self.putStr("database");
    self.putStr(cfg.database);
    self.putStr("client_encoding");
    self.putStr("UTF8");
    for (i64 i = 0; i + 1 < cfg.params.len; i += 2) {
        self.putStr(cfg.params.get(i));
        self.putStr(cfg.params.get(i + 1));
    }
    self.put8(0);
    self.end();
    self.flush();
    Scram sc;
    mem.set(&sc, 0, sizeof(Scram));
    defer sc.free();
    while (true) {
        u8 t = self.recv();
        if (t == 'R') {
            self.auth(cfg, &sc);
        } else if (t == 'K') {
            self.pid = be32At(self.msg, 0);
            self.secret = be32At(self.msg, 4);
        } else if (t == 'Z') {
            self.txStatus = self.msg.len ? self.msg[0] : 'I';
            break;
        } else if (t == 'E') {
            ServerError e = serverError(self.msg);
            defer e.message.free();
            throw(e.code, "pg: %s", e.message);
        } else if (t == 'v') {
            continue;       /* NegotiateProtocolVersion: 3.0 minor options we didn't ask for */
        } else {
            throw(ERR_PROTOCOL, "pg: unexpected message '%c' during start-up", t);
        }
    }
    self.s.timeoutMs = -1;
}

/* SCRAM-SHA-256 in progress */
struct Scram
{
    String clientFirstBare;
    String nonce;
    u8 serverSig[32];
};

static void Scram.free()
{
    self.clientFirstBare.free();
    self.nonce.free();
}

static void Conn.needPassword(Config *cfg, u8 *method)
{
    if (!cfg.password.len) {
        throw(ERR_AUTH, "pg: the server asks for a password (%s) and none was given", method);
    }
}

static void Conn.auth(Config *cfg, Scram *sc)
{
    i64 kind = be32At(self.msg, 0);
    if (kind == 0) { return; }       /* AuthenticationOk */
    if (kind == 3) {                 /* cleartext */
        self.needPassword(cfg, "cleartext");
        self.begin('p');
        self.putStr(cfg.password);
        self.end();
        self.flush();
        return;
    }
    if (kind == 5) {                 /* md5 */
        self.needPassword(cfg, "md5");
        u8 out[35];
        md5Password(cfg.user, cfg.password, self.msg[4:8], out);
        self.begin('p');
        self.putStr(out[0:35]);
        self.end();
        self.flush();
        return;
    }
    if (kind == 10) {                /* SASL: the mechanisms */
        bool scram = false;
        i64 at = 4;
        while (at < self.msg.len && self.msg[at]) {
            u8[] m = cstrAt(self.msg, at);
            if (m.equals("SCRAM-SHA-256")) { scram = true; }
            at += m.len + 1;
        }
        if (!scram) { throw(ERR_AUTH, "pg: the server offers no SASL mechanism ktm-pg knows (it needs SCRAM-SHA-256)"); }
        self.needPassword(cfg, "SCRAM-SHA-256");
        u8 raw[18];
        randomBytes(raw[0:18]);
        sc.nonce = str.new();
        base64Append(&sc.nonce, raw[0:18]);
        sc.clientFirstBare = str.format("n=,r=%s", sc.nonce);
        self.begin('p');
        self.putStr("SCRAM-SHA-256");
        self.put32(3 + sc.clientFirstBare.len);
        self.putBytes("n,,");
        self.putBytes(sc.clientFirstBare);
        self.end();
        self.flush();
        return;
    }
    if (kind == 11) {                /* SASLContinue: the server-first message */
        self.scramFinal(cfg, sc, self.msg[4:]);
        return;
    }
    if (kind == 12) {                /* SASLFinal: v=<server signature> */
        u8[] v = self.msg[4:];
        ad String sig = str.new();
        if (!str.startsWith(v, "v=") || !base64Decode(v[2:], &sig) || sig.len != 32
            || !hash.equalCt(sig, sc.serverSig[0:32])) {
            throw(ERR_AUTH, "pg: the server's SCRAM signature is wrong: it doesn't know the password");
        }
        return;
    }
    throw(ERR_AUTH, "pg: the server asks for an authentication ktm-pg doesn't support (%d)", kind);
}

static void Conn.scramFinal(Config *cfg, Scram *sc, u8[] serverFirst)
{
    u8[] r = "", s = "";
    i64 iter = 0;
    u8[] rest = serverFirst;
    while (rest.len) {
        u8[] part = rest, before, after;
        if (str.splitFirst(rest, ",", &before, &after)) { part = before; rest = after; } else { rest = ""; }
        if (str.startsWith(part, "r=")) { r = part[2:]; }
        else if (str.startsWith(part, "s=")) { s = part[2:]; }
        else if (str.startsWith(part, "i=")) { str.toInt(part[2:], &iter); }
    }
    if (!str.startsWith(r, sc.nonce) || !s.len || iter <= 0) {
        throw(ERR_AUTH, "pg: a bad SCRAM server-first message");
    }
    ad String salt = str.new();
    if (!base64Decode(s, &salt)) { throw(ERR_AUTH, "pg: a bad SCRAM salt"); }
    u8 salted[32], clientKey[32], storedKey[32], sig[32], serverKey[32];
    pbkdf2Sha256(cfg.password, salt, iter, salted[0:32]);
    hmacSha256(salted[0:32], "Client Key", clientKey[0:32]);
    hash.sha256(clientKey[0:32], storedKey[0:32]);
    ad String finalBare = str.format("c=biws,r=%s", r);
    ad String authMsg = str.format("%s,%s,%s", sc.clientFirstBare, serverFirst, finalBare);
    hmacSha256(storedKey[0:32], authMsg, sig[0:32]);
    u8 proof[32];
    for (i64 i = 0; i < 32; i++) { proof[i] = clientKey[i] ^ sig[i]; }
    hmacSha256(salted[0:32], "Server Key", serverKey[0:32]);
    hmacSha256(serverKey[0:32], authMsg, sc.serverSig[0:32]);
    ad String msg = str.from(finalBare);
    msg.append(",p=");
    base64Append(&msg, proof[0:32]);
    self.begin('p');
    self.putBytes(msg);
    self.end();
    self.flush();
}

/* ---- closing ---- */

/* Frees what the connection holds, without telling the server */
static void Conn.release_()
{
    self.s.close(&self.s);
    for (i64 i = 0; i < self.nstmts; i++) { stmtFree(&self.stmts[i]); }
    mem.free(self.stmts);
    mem.free(self.in);
    mem.free(self.out);
    self.version.free();
    mem.free(self);
}

/* Ends the session and frees the connection. A connection from a pool
 * goes back to it instead (c.release() says so better). */
pub void Conn.close()
{
    if (self.pool) { poolPut(self.pool, self); return; }
    self.terminate();
    self.release_();
}

static void Conn.terminate()
{
    if (self.dead) { return; }
    self.outLen = 0;
    self.begin('X');
    self.end();
    try { self.flush(); } catch { }
}

/* The server's version, as it says it ("17.2 (Debian 17.2-1.pgdg120+1)") */
pub u8[] Conn.serverVersion()
{
    return self.version;
}

/* Whether the connection is broken (it can only be closed then) */
pub bool Conn.isDead()
{
    return self.dead;
}

/* 'I' outside a transaction, 'T' inside one, 'E' inside a failed one */
pub u8 Conn.txState()
{
    return self.txStatus;
}

/* The server process serving the connection */
pub i64 Conn.backendPid()
{
    return self.pid;
}

/* ---- the statement cache ---- */

static void stmtFree(Stmt *st)
{
    st.sql.free();
    mem.free(st.paramOids);
    for (i64 i = 0; i < st.ncols; i++) { st.cols[i].name.free(); }
    mem.free(st.cols);
}

static void Conn.ready()
{
    if (self.dead) { throw(ERR_CLOSED, "pg: the connection is closed"); }
    if (self.busy) { throw(ERR_CLOSED, "pg: the connection still has open rows: free them first"); }
}

/* The cached statement for sql, parsed and described on first use */
static Stmt *Conn.prepare(u8[] sql)
{
    u64 h = hash.fnv1a(sql);
    for (i64 i = 0; i < self.nstmts; i++) {
        Stmt *st = &self.stmts[i];
        if (st.hash == h && st.sql.equals(sql)) {
            st.lastUse = ++self.clock;
            return st;
        }
    }
    if (!self.stmts) { self.stmts = mem.allocZero(sizeof(Stmt) * STMT_CACHE); }
    Stmt *st;
    bool reused = false;
    if (self.nstmts == STMT_CACHE) {
        st = &self.stmts[0];
        for (i64 i = 1; i < self.nstmts; i++) {
            if (self.stmts[i].lastUse < st.lastUse) { st = &self.stmts[i]; }
        }
        self.begin('C');            /* Close the old one, in the same round trip */
        self.put8('S');
        self.putStr(st.name[0:st.nameLen]);
        self.end();
        stmtFree(st);
        reused = true;
    } else {
        st = &self.stmts[self.nstmts];
    }
    mem.set(st, 0, sizeof(Stmt));
    st.nameLen = libc.snprintf(st.name, sizeof(st.name), "ktm_%d", self.stmtSeq++);
    self.begin('P');
    self.putStr(st.name[0:st.nameLen]);
    self.putStr(sql);
    self.put16(0);
    self.end();
    self.begin('D');
    self.put8('S');
    self.putStr(st.name[0:st.nameLen]);
    self.end();
    self.begin('S');
    self.end();
    self.flush();
    bool failed = false;
    ServerError err;
    while (true) {
        u8 t = self.recv();
        if (t == 'Z') { self.txStatus = self.msg.len ? self.msg[0] : 'I'; break; }
        if (failed) { continue; }
        if (t == 'E') { err = serverError(self.msg); failed = true; }
        else if (t == 't') {
            st.nparams = be16At(self.msg, 0);
            st.paramOids = mem.alloc(4 * (st.nparams + 1));
            for (i64 i = 0; i < st.nparams; i++) { st.paramOids[i] = be32At(self.msg, 2 + i * 4); }
        } else if (t == 'T') {
            self.readColumns(st);
        }
        /* '1' ParseComplete, '3' CloseComplete, 'n' NoData */
    }
    if (failed) {
        mem.free(st.paramOids);
        for (i64 i = 0; i < st.ncols; i++) { st.cols[i].name.free(); }
        mem.free(st.cols);
        mem.set(st, 0, sizeof(Stmt));
        if (reused) {               /* the slot is empty now: fill it with the last one */
            Stmt *last = &self.stmts[--self.nstmts];
            if (last != st) { *st = *last; mem.set(last, 0, sizeof(Stmt)); }
        }
        defer err.message.free();
        throw(err.code, "%s", err.message);
    }
    st.sql = str.from(sql);
    st.hash = h;
    st.lastUse = ++self.clock;
    if (!reused) { self.nstmts++; }
    return st;
}

/* A RowDescription into st.cols */
static void Conn.readColumns(Stmt *st)
{
    u8[] m = self.msg;
    st.ncols = be16At(m, 0);
    st.cols = mem.allocZero(sizeof(Column) * (st.ncols + 1));
    i64 at = 2;
    for (i64 i = 0; i < st.ncols; i++) {
        u8[] name = cstrAt(m, at);
        at += name.len + 1;
        st.cols[i].name = str.from(name);
        st.cols[i].oid = be32At(m, at + 6);
        st.cols[i].format = resultFormat(st.cols[i].oid);
        at += 18;
    }
}

/* ---- running statements ---- */

/* Bind st's parameters, Execute and Sync, all in one write */
static void Conn.bindExecute(Stmt *st, reflect.Value[] args)
{
    if (args.len != st.nparams) {
        throw(ERR_ARG, "pg: the statement has %d parameters, %d given", st.nparams, args.len);
    }
    self.begin('B');
    self.put8(0);                   /* the unnamed portal */
    self.putStr(st.name[0:st.nameLen]);
    self.put16(args.len);
    for (i64 i = 0; i < args.len; i++) { self.put16(paramFormat(st.paramOids[i], args[i])); }
    self.put16(args.len);
    try {
        for (i64 i = 0; i < args.len; i++) { self.putArg(st.paramOids[i], args[i], i); }
    } catch (Error e) {
        self.outLen = 0;            /* nothing of the Bind goes out */
        throw(e);
    }
    self.put16(st.ncols);
    for (i64 i = 0; i < st.ncols; i++) { self.put16(st.cols[i].format); }
    self.end();
    self.begin('E');
    self.put8(0);
    self.put32(0);
    self.end();
    self.begin('S');
    self.end();
    self.flush();
}

/* The rows a CommandComplete tag says it touched: the last number of it */
static i64 tagCount(u8[] tag)
{
    i64 i = tag.len;
    while (i > 0 && tag[i - 1] >= '0' && tag[i - 1] <= '9') { i--; }
    i64 n = 0;
    for (i64 j = i; j < tag.len; j++) { n = n * 10 + tag[j] - '0'; }
    return n;
}

/* Runs sql with args and returns how many rows it changed (or returned);
 * the rows themselves are dropped */
pub i64 Conn.execArgs(u8[] sql, reflect.Value[] args)
{
    self.ready();
    Stmt *st = self.prepare(sql);
    self.bindExecute(st, args);
    i64 n = 0;
    while (true) {
        u8 t = self.recv();
        if (t == 'C') { n = tagCount(cstrAt(self.msg, 0)); }
        else if (t == 'E') { self.throwServerError(); }
        else if (t == 'Z') { self.txStatus = self.msg.len ? self.msg[0] : 'I'; break; }
    }
    return n;
}

/* exec with the parameters $1, $2 ... as arguments of any type */
pub i64 Conn.exec(u8[] sql, any ...args)
{
    return self.execArgs(sql, args);
}

/* Runs a script of any number of statements, without parameters (the
 * simple protocol). Returns the count of the last one. */
pub i64 Conn.execScript(u8[] sql)
{
    self.ready();
    self.begin('Q');
    self.putStr(sql);
    self.end();
    self.flush();
    i64 n = 0;
    while (true) {
        u8 t = self.recv();
        if (t == 'C') { n = tagCount(cstrAt(self.msg, 0)); }
        else if (t == 'E') { self.throwServerError(); }
        else if (t == 'Z') { self.txStatus = self.msg.len ? self.msg[0] : 'I'; break; }
    }
    return n;
}

/* Runs a query; read its rows with rows.next() and free them with
 * rows.free() (or ad). The connection is busy until then. */
pub Rows Conn.queryArgs(u8[] sql, reflect.Value[] args)
{
    self.ready();
    Stmt *st = self.prepare(sql);
    self.bindExecute(st, args);
    Rows r;
    mem.set(&r, 0, sizeof(Rows));
    r.c = self;
    r.st = st;
    r.cols = st.ncols;
    r.off = mem.alloc(sizeof(i32) * 2 * (st.ncols + 1));
    self.busy = true;
    return r;
}

/* queryArgs with the parameters $1, $2 ... as arguments of any type */
static Rows connOpen(Db *db, u8[] sql, reflect.Value[] args)
{
    Conn *c = db;
    return c.queryArgs(sql, args);
}

pub Rows Conn.query(u8[] sql, any ...args)
{
    return self.queryArgs(sql, args);
}
