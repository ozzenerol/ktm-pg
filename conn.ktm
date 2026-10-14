module pg;
import atomic, coro, date, hash, libc, mem, net.tls, os, reflect, str;

/* What a Conn, a Tx and a Pool have in common: pg.queryAll and
 * pg.queryOne take any of them as a Db * */
pub struct Db
{
    private fn(Db *, u8[], reflect.Value[], bool) Rows open;   /* bool: the rows are read at once (may share a connection) */
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
    private Req *qhead;         /* requests in flight, in the order of their answers */
    private Req *qtail;
    private u8 *spare;          /* the buffer flush() sends from, while others append to out */
    private i64 spareCap;
    private bool flushing;
    private Stmt **stmts;       /* the statement cache (pointers: a Stmt never moves) */
    private i64 nstmts;
    private i64 stmtsCap;
    private i64 stmtSeq;        /* the next statement's name: ktm_<n> */
    private i64 clock;          /* use count, for the least recently used statement */
    private String version;     /* server_version */
    private void *pool;         /* the Pool it came from, or null */
    private i64 born;           /* date.monoMs() when it connected */
    private i64 idleSince;
    private void *shard;
    private String host;        /* where it connected: a CancelRequest goes there too */
    private i32 port;
    private i64 timeoutMs;      /* per query, 0: none */
    private i64 deadline;       /* date.monoMs() the running query must end by, 0: none */
    private bool cancelSent;
    private i64 users;          /* pool calls sharing it (pipelining), 0 when one owner has it */
    private bool draining;      /* shared, but takes no new calls: it goes back when they are done */
    private tls.Context *tlsCtx;    /* the TLS context its session uses (a cancel uses it too) */
    private tls.Context *ownCtx;    /* the same, when this connection frees it (pg.connect) */
    private Note *noteHead;         /* notifications not read yet */
    private Note *noteTail;
};

/* A request on the connection: its messages are sent, and its answer
 * comes in turn. Coroutines of one loop may share a connection: each
 * appends its messages and sends them at once (pipelining), and only the
 * first request reads; when it is done, it wakes the next. */
struct Req
{
    coro.Coroutine *co;         /* null outside a loop coroutine */
    Req *next;
};

/* A prepared statement of the cache */
struct Stmt
{
    bool pending;               /* its Parse is in flight */
    i64 refs;                   /* requests using it: it isn't evicted meanwhile */
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

/* After a CancelRequest, the server has this long to answer before the
 * connection is given up */
const i64 CANCEL_GRACE_MS = 5000;
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
    if (self.s.impl) {              /* TLS: its reason says more than errno */
        ad String why = str.from(self.s.impl.error());
        self.dead = true;
        self.s.close(&self.s);
        self.wakeAll();
        if (why.len) {
            i64 code = str.contains(why, "alert") || str.contains(why, "certificate") ? ERR_TLS : ERR_IO;
            throw(code, "pg: connection lost while %s: %s", what, why);
        }
        throw(ERR_IO, "pg: connection lost while %s: %s", what, libc.strerror(err));
    }
    self.dead = true;
    self.s.close(&self.s);
    self.wakeAll();
    throw(ERR_IO, "pg: connection lost while %s: %s", what, libc.strerror(err));
}

/* Send what is in out. Another coroutine may append while a write
 * waits: the flusher sends from its own buffer and loops until out is
 * empty, and an append during a flush is sent by that flusher. */
static void Conn.flush()
{
    if (self.dead) { throw(ERR_CLOSED, "pg: the connection is closed"); }
    if (self.flushing || !self.outLen) { return; }
    self.flushing = true;
    while (self.outLen) {
        u8 *buf = self.out;
        i64 n = self.outLen;
        i64 cap = self.outCap;
        self.out = self.spare;
        self.outCap = self.spareCap;
        self.outLen = 0;
        self.spare = null;
        self.spareCap = 0;
        bool ok;
        try {
            ok = self.s.write(&self.s, buf[0:n]);
        } catch (Error e) {
            self.spare = buf;
            self.spareCap = cap;
            self.flushing = false;
            throw(e);
        }
        self.spare = buf;
        self.spareCap = cap;
        if (!ok) {
            self.flushing = false;
            self.broken("sending");
        }
    }
    self.flushing = false;
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
        if (self.deadline) {
            i64 left = self.deadline - date.monoMs();
            if (left <= 0) { self.timedOut(); continue; }
            self.s.timeoutMs = left;
        }
        i64 got = self.s.read(&self.s, self.in[self.inEnd:self.inCap]);
        if (got <= 0) {
            if (got < 0 && self.deadline && os.errno() == libc.ETIMEDOUT) { self.timedOut(); continue; }
            if (got == 0) { os.setErrno(libc.ECONNRESET); }
            self.broken("receiving");
        }
        self.inEnd += got;
    }
}

/* The running query passed its deadline: ask the server to cancel it
 * (the answer is its 57014 error, and the connection stays usable), or,
 * when that was asked already and nothing came, give the connection up */
static void Conn.timedOut()
{
    if (!self.cancelSent) {
        self.cancelSent = true;
        self.sendCancel();
        self.deadline = date.monoMs() + CANCEL_GRACE_MS;
        return;
    }
    self.deadline = 0;
    self.dead = true;
    self.s.close(&self.s);
    throw(ERR_IO, "pg: the query timed out after %d ms and the server didn't answer the cancel: connection closed",
          self.timeoutMs);
}

/* A CancelRequest for this connection's query, on a connection of its
 * own. Waits until the server closes that one, so the cancel has reached
 * the backend before the query's connection is used again. */
static void Conn.sendCancel()
{
    Stream cs;
    mem.set(&cs, 0, sizeof(Stream));
    if (!streamDial(&cs, self.host, self.port, 2000)) { return; }
    cs.timeoutMs = 2000;
    if (self.tlsCtx) {
        try { cs.startTls(self.tlsCtx, self.host); } catch { cs.close(&cs); return; }
    }
    u8 m[16];
    m[0] = 0; m[1] = 0; m[2] = 0; m[3] = 16;
    m[4] = 0x04; m[5] = 0xD2; m[6] = 0x16; m[7] = 0x2E;   /* 80877102 */
    m[8] = (self.pid >> 24) & 0xFF; m[9] = (self.pid >> 16) & 0xFF; m[10] = (self.pid >> 8) & 0xFF; m[11] = self.pid & 0xFF;
    m[12] = (self.secret >> 24) & 0xFF; m[13] = (self.secret >> 16) & 0xFF;
    m[14] = (self.secret >> 8) & 0xFF; m[15] = self.secret & 0xFF;
    if (cs.write(&cs, m[0:16])) {
        u8 b[16];
        while (cs.read(&cs, b[0:16]) > 0) { }
    }
    cs.close(&cs);
}

/* Cancels the query running on the connection, from another coroutine
 * or thread: it ends with QUERY_CANCELED (57014) */
pub void Conn.cancel()
{
    self.sendCancel();
}

/* Every query on this connection must end within ms milliseconds (0: no
 * limit). One that doesn't is cancelled on the server and throws
 * QUERY_CANCELED; the connection stays usable. */
pub void Conn.setQueryTimeout(i64 ms)
{
    self.timeoutMs = ms > 0 ? ms : 0;
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
        if (t == 'A') { self.queueNote(); continue; }
        if (t == 'N') { continue; }
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

/* The ErrorResponse just read, saying so when our timeout cancelled it */
static ServerError Conn.serverErr()
{
    ServerError e = serverError(self.msg);
    if (self.cancelSent && e.code == QUERY_CANCELED) {
        ad String pre = str.format("timeout after %d ms: ", self.timeoutMs);
        e.message.insert(0, pre);
    }
    return e;
}

/* After an ErrorResponse: read up to ReadyForQuery, then throw it */
static void Conn.throwServerError()
{
    ServerError e = self.serverErr();
    defer e.message.free();
    try { self.drain(); } catch { }     /* a FATAL error ends the session: its error says why */
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
    Conn *c = connectConfig(&cfg);
    c.ownCtx = cfg.tlsCtx;          /* the Config goes now; the session keeps its context */
    cfg.tlsCtx = null;
    return c;
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
    c.host = str.from(cfg.host);
    c.port = cfg.port;
    try {
        c.negotiateTls(cfg);
        c.startup(cfg);
    } catch (Error e) {
        c.release_();
        throw(e);
    }
    return c;
}

/* TLS as sslmode says: disable none; allow and prefer when the server
 * has it; require, verify-ca and verify-full or fail */
static void Conn.negotiateTls(Config *cfg)
{
    if (cfg.sslMode == SSL_DISABLE || (cfg.host.len && cfg.host[0] == '/')) { return; }
    if (cfg.connectTimeoutMs > 0) { self.s.timeoutMs = cfg.connectTimeoutMs; }
    tls.Context *ctx = cfg.tlsContext();
    if (self.s.startTls(ctx, cfg.host)) {
        self.tlsCtx = ctx;
        return;
    }
    if (self.s.fd < 0) { throw(ERR_CONNECT, "pg: the server closed the connection at the TLS request"); }
    if (cfg.sslMode >= SSL_REQUIRE) {
        throw(ERR_TLS, "pg: sslmode=%s, but the server doesn't do TLS",
              cfg.sslMode == SSL_REQUIRE ? "require" : cfg.sslMode == SSL_VERIFY_CA ? "verify-ca" : "verify-full");
    }
}

/* Whether the session is encrypted, and how ("TLSv1.3"; "" if not) */
pub u8[] Conn.tlsVersion()
{
    if (!self.s.impl) { return ""; }
    return self.s.impl.version();
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
    bool plus;                  /* SCRAM-SHA-256-PLUS: channel binding to the TLS session */
    String gs2;
    String clientFirstBare;
    String nonce;
    u8 serverSig[32];
};

static void Scram.free()
{
    self.gs2.free();
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
    if (kind == 0) {                 /* AuthenticationOk */
        if (cfg.channelBinding == CB_REQUIRE && !sc.plus) {
            throw(ERR_AUTH, "pg: channel_binding=require, but the server authenticated without it");
        }
        return;
    }
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
        bool scram = false, plus = false;
        i64 at = 4;
        while (at < self.msg.len && self.msg[at]) {
            u8[] m = cstrAt(self.msg, at);
            if (m.equals("SCRAM-SHA-256")) { scram = true; }
            if (m.equals("SCRAM-SHA-256-PLUS")) { plus = true; }
            at += m.len + 1;
        }
        bool tlsOn = self.s.impl != null;
        sc.plus = plus && tlsOn && cfg.channelBinding != CB_DISABLE;
        if (cfg.channelBinding == CB_REQUIRE && !sc.plus) {
            throw(ERR_AUTH, "pg: channel_binding=require, but %s", tlsOn ? "the server doesn't offer SCRAM-SHA-256-PLUS" : "the connection isn't TLS");
        }
        if (!scram && !sc.plus) { throw(ERR_AUTH, "pg: the server offers no SASL mechanism ktm-pg knows (it needs SCRAM-SHA-256)"); }
        self.needPassword(cfg, "SCRAM-SHA-256");
        u8 raw[18];
        randomBytes(raw[0:18]);
        sc.nonce = str.new();
        base64Append(&sc.nonce, raw[0:18]);
        sc.clientFirstBare = str.format("n=,r=%s", sc.nonce);
        /* the GS2 header: p= with channel binding; y when we could bind but
         * the server can't (TLS without -PLUS); n without TLS */
        u8[] gs2 = "n,,";
        if (sc.plus) { gs2 = "p=tls-server-end-point,,"; }
        else if (tlsOn && cfg.channelBinding != CB_DISABLE) { gs2 = "y,,"; }
        sc.gs2 = str.from(gs2);
        u8[] mech = "SCRAM-SHA-256";
        if (sc.plus) { mech = "SCRAM-SHA-256-PLUS"; }
        self.begin('p');
        self.putStr(mech);
        self.put32(sc.gs2.len + sc.clientFirstBare.len);
        self.putBytes(sc.gs2);
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
    bool cached = false;
    while (!atomic.cas(&cfg.scramLock, 0, 1)) { }
    if (cfg.scramIter == iter && cfg.scramSalt.equals(salt)) {
        mem.copy(clientKey, cfg.clientKey, 32);
        mem.copy(serverKey, cfg.serverKey, 32);
        cached = true;
    }
    atomic.store(&cfg.scramLock, 0);
    if (!cached) {
        pbkdf2Sha256(cfg.password, salt, iter, salted[0:32]);
        hmacSha256(salted[0:32], "Client Key", clientKey[0:32]);
        hmacSha256(salted[0:32], "Server Key", serverKey[0:32]);
        while (!atomic.cas(&cfg.scramLock, 0, 1)) { }
        cfg.scramSalt.free();
        cfg.scramSalt = str.from(salt);
        cfg.scramIter = iter;
        mem.copy(cfg.clientKey, clientKey, 32);
        mem.copy(cfg.serverKey, serverKey, 32);
        atomic.store(&cfg.scramLock, 0);
    }
    hash.sha256(clientKey[0:32], storedKey[0:32]);
    /* c=: the GS2 header, and with -PLUS the hash of the server's
     * certificate (tls-server-end-point, RFC 5929) */
    ad String cbind = str.from(sc.gs2);
    if (sc.plus) {
        i32 md = 0;
        u8[] der = self.s.impl.peerCert(&md);
        if (!der.len) { throw(ERR_AUTH, "pg: SCRAM-SHA-256-PLUS: the server sent no certificate"); }
        u8 h[64];
        i64 hl = certHash(der, md, h);
        cbind.append(h[0:hl]);
    }
    ad String finalBare = str.from("c=");
    base64Append(&finalBare, cbind);
    finalBare.appendFormat(",r=%s", r);
    ad String authMsg = str.format("%s,%s,%s", sc.clientFirstBare, serverFirst, finalBare);
    hmacSha256(storedKey[0:32], authMsg, sig[0:32]);
    u8 proof[32];
    for (i64 i = 0; i < 32; i++) { proof[i] = clientKey[i] ^ sig[i]; }
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
    for (i64 i = 0; i < self.nstmts; i++) {
        stmtFree(self.stmts[i]);
        mem.free(self.stmts[i]);
    }
    mem.free(self.stmts);
    mem.free(self.spare);
    mem.free(self.in);
    mem.free(self.out);
    self.version.free();
    self.host.free();
    self.freeNotes();
    if (self.ownCtx) { self.ownCtx.free(); }
    mem.free(self);
}

/* Ends the session and frees the connection. A connection from a pool
 * goes back to it instead (c.release() says so better). */
pub void Conn.close()
{
    if (self.pool) {
        if (self.users) { poolUnshare(self.pool, self); } else { poolPut(self.pool, self); }
        return;
    }
    self.terminate();
    self.release_();
}

static void Conn.terminate()
{
    if (self.dead) { return; }
    if (!self.qhead) { self.s.setLoop(coro.inLoop()); }    /* closed from main() after coro.run() */
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

/* An idle connection has nothing to read: one that has (the server's
 * FATAL error before it hung up, or the end of the stream) is broken,
 * though no query found out yet. One poll(2), no waiting. */
static bool Conn.quiet()
{
    if (self.inEnd > self.inStart) { return false; }
    libc.pollfd p;
    p.fd = self.s.fd;
    p.events = libc.POLLIN;
    p.revents = 0;
    if (libc.poll(&p, 1, 0) == 0) { return true; }
    self.dead = true;
    return false;
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

/* ---- the request queue ---- */

/* Every coroutine waiting on the connection finds out it broke */
static void Conn.wakeAll()
{
    coro.Coroutine *me = coro.inLoop() ? coro.current() : null;
    for (Req *q = self.qhead; q; q = q.next) {
        if (q.co && q.co != me) { coro.unpark(q.co); }
    }
}

/* r joins the queue: its messages may be appended now */
static void Conn.join(Req *r)
{
    if (self.dead) { throw(ERR_CLOSED, "pg: the connection is closed"); }
    bool loop = coro.inLoop();
    r.co = loop ? coro.current() : null;
    r.next = null;
    for (Req *q = self.qhead; q; q = q.next) {
        if (q.co == r.co) {
            throw(ERR_CLOSED, "pg: the connection still has open rows of this coroutine: free them first");
        }
    }
    if (!self.qhead) { self.s.setLoop(loop); }
    if (self.qtail) { self.qtail.next = r; } else { self.qhead = r; }
    self.qtail = r;
}

/* Wait until r is first: its answer is next */
static void Conn.turn(Req *r)
{
    while (self.qhead != r) {
        if (self.dead) { throw(ERR_IO, "pg: the connection broke while a query waited for its turn"); }
        coro.park();
    }
    if (self.dead) { throw(ERR_CLOSED, "pg: the connection is closed"); }
    self.cancelSent = false;
    self.deadline = self.timeoutMs ? date.monoMs() + self.timeoutMs : 0;
    self.s.timeoutMs = -1;          /* fill() sets it while there is a deadline */
}

/* r leaves the queue (done, or given up); the next one is woken */
static void Conn.leave(Req *r)
{
    Req *prev = null;
    for (Req *q = self.qhead; q; q = q.next) {
        if (q == r) {
            if (prev) { prev.next = q.next; } else { self.qhead = q.next; }
            if (self.qtail == q) { self.qtail = prev; }
            break;
        }
        prev = q;
    }
    if (!prev && self.qhead && self.qhead.co) { coro.unpark(self.qhead.co); }
}

/* Whether a request is in flight (open rows, or another coroutine's query) */
static bool Conn.inUse()
{
    return self.qhead != null;
}

/* ---- the statement cache ---- */

static Stmt *Conn.lookup(u8[] sql, u64 h)
{
    for (i64 i = 0; i < self.nstmts; i++) {
        Stmt *st = self.stmts[i];
        if (st.hash == h && st.sql.equals(sql)) { return st; }
    }
    return null;
}

static void Conn.removeStmt(Stmt *st)
{
    for (i64 i = 0; i < self.nstmts; i++) {
        if (self.stmts[i] == st) {
            self.stmts[i] = self.stmts[--self.nstmts];
            break;
        }
    }
    stmtFree(st);
    mem.free(st);
}

/* The cached statement for sql, parsed and described on first use. r is
 * not in the queue, before or after: the caller joins right before it
 * appends its messages, with no pause in between, so the order of the
 * queue is the order of the messages. */
static Stmt *Conn.prepare(u8[] sql, Req *r)
{
    u64 h = hash.fnv1a(sql);
    while (true) {
        Stmt *st = self.lookup(sql, h);
        if (!st) { break; }
        if (!st.pending) {
            st.lastUse = ++self.clock;
            return st;
        }
        /* another coroutine prepares it: wait for that */
        if (!coro.inLoop()) { throw(ERR_CLOSED, "pg: a statement is being prepared by another coroutine"); }
        coro.park(1);               /* rare: only while the first one is prepared */
        if (self.dead) { throw(ERR_IO, "pg: the connection broke while a statement was prepared"); }
    }
    /* a new one: evict the least recently used one nobody uses, if full */
    if (self.nstmts >= STMT_CACHE) {
        Stmt *old = null;
        for (i64 i = 0; i < self.nstmts; i++) {
            Stmt *s = self.stmts[i];
            if (!s.pending && !s.refs && (!old || s.lastUse < old.lastUse)) { old = s; }
        }
        if (old) {
            self.begin('C');        /* Close it, in the same round trip */
            self.put8('S');
            self.putStr(old.name[0:old.nameLen]);
            self.end();
            self.removeStmt(old);
        }
    }
    if (self.nstmts == self.stmtsCap) {
        self.stmtsCap = self.stmtsCap ? self.stmtsCap * 2 : 32;
        self.stmts = mem.realloc(self.stmts, sizeof(Stmt *) * self.stmtsCap);
    }
    Stmt *st = mem.allocZero(sizeof(Stmt));
    self.stmts[self.nstmts++] = st;
    st.pending = true;
    st.sql = str.from(sql);
    st.hash = h;
    st.nameLen = libc.snprintf(st.name, sizeof(st.name), "ktm_%d", self.stmtSeq++);
    try {
        self.join(r);
    } catch (Error e) {
        self.removeStmt(st);
        throw(e);
    }
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
    bool failed = false;
    ServerError err;
    try {
        self.flush();
        self.turn(r);
        while (true) {
            u8 t;
            try {
                t = self.recv();
            } catch (Error e) {
                if (!failed) { throw(e); }
                break;              /* the session ended after its error: throw that */
            }
            if (t == 'Z') { self.txStatus = self.msg.len ? self.msg[0] : 'I'; break; }
            if (failed) { continue; }
            if (t == 'E') { err = self.serverErr(); failed = true; }
            else if (t == 't') {
                st.nparams = be16At(self.msg, 0);
                st.paramOids = mem.alloc(4 * (st.nparams + 1));
                for (i64 i = 0; i < st.nparams; i++) { st.paramOids[i] = be32At(self.msg, 2 + i * 4); }
            } else if (t == 'T') {
                self.readColumns(st);
            }
            /* '1' ParseComplete, '3' CloseComplete, 'n' NoData */
        }
    } catch (Error e) {
        if (failed) { err.message.free(); }
        self.leave(r);
        self.removeStmt(st);
        throw(e);
    }
    self.leave(r);
    if (failed) {
        self.removeStmt(st);
        defer err.message.free();
        throw(err.code, "%s", err.message);
    }
    st.pending = false;
    st.lastUse = ++self.clock;
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
static void Conn.bindExecute(Stmt *st, reflect.Value[] args, bool sync = true)
{
    if (args.len != st.nparams) {
        throw(ERR_ARG, "pg: the statement has %d parameters, %d given", st.nparams, args.len);
    }
    i64 start = self.outLen;
    self.begin('B');
    self.put8(0);                   /* the unnamed portal */
    self.putStr(st.name[0:st.nameLen]);
    self.put16(args.len);
    for (i64 i = 0; i < args.len; i++) { self.put16(paramFormat(st.paramOids[i], args[i])); }
    self.put16(args.len);
    try {
        for (i64 i = 0; i < args.len; i++) { self.putArg(st.paramOids[i], args[i], i); }
    } catch (Error e) {
        self.outLen = start;        /* nothing of the Bind goes out */
        throw(e);
    }
    self.put16(st.ncols);
    for (i64 i = 0; i < st.ncols; i++) { self.put16(st.cols[i].format); }
    self.end();
    self.begin('E');
    self.put8(0);
    self.put32(0);
    self.end();
    if (!sync) { return; }
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
    Req r;
    Stmt *st = self.prepare(sql, &r);
    self.join(&r);
    defer self.leave(&r);
    st.refs++;
    defer st.refs--;
    self.bindExecute(st, args);
    self.turn(&r);
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
    Req r;
    self.join(&r);
    defer self.leave(&r);
    self.begin('Q');
    self.putStr(sql);
    self.end();
    self.flush();
    self.turn(&r);
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
    Req *req = mem.allocZero(sizeof(Req));
    Stmt *st;
    bool joined = false;
    try {
        st = self.prepare(sql, req);
        self.join(req);
        joined = true;
        self.bindExecute(st, args);
    } catch (Error e) {
        if (joined) { self.leave(req); }
        mem.free(req);
        throw(e);
    }
    st.refs++;
    Rows r;
    mem.set(&r, 0, sizeof(Rows));
    r.c = self;
    r.st = st;
    r.req = req;
    r.cols = st.ncols;
    r.off = mem.alloc(sizeof(i32) * 2 * (st.ncols + 1));
    return r;
}

/* queryArgs with the parameters $1, $2 ... as arguments of any type */
static Rows connOpen(Db *db, u8[] sql, reflect.Value[] args, bool shared)
{
    Conn *c = db;
    return c.queryArgs(sql, args);
}

pub Rows Conn.query(u8[] sql, any ...args)
{
    return self.queryArgs(sql, args);
}
