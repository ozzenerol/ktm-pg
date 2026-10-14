module pg;
import mem, reflect, str;

/* COPY: bulk data into a table, and out of one or a query.
 *
 *   pg.CopyIn in = c.copyIn("COPY users (name, email) FROM STDIN");
 *   in.row("Ada", "ada@example.com");          // or in.write(text in COPY's format)
 *   i64 n = in.end();                          // the rows copied
 *
 *   pg.CopyOut out = c.copyOut("COPY users TO STDOUT (FORMAT csv)");
 *   defer out.free();
 *   while (out.next()) { use(out.data); }      // a chunk: usually one row
 *
 * The connection does nothing else until end() or abort() (or, for
 * copyOut, the last next() or free()). From a pool, acquire() one. */

pub struct CopyIn
{
    private Conn *c;
    private Req *req;
    private bool done;
};

pub struct CopyOut
{
    u8[] data;                  /* the last chunk next() read: valid until the next call */
    private Conn *c;
    private Req *req;
    private bool done;
    private i64 count;
};

const i64 COPY_FLUSH = 65536;

/* Run sql (a COPY) and read until the server answers with want ('G' or
 * 'H'); a server error is thrown, the connection usable */
static Req *Conn.copyStart(u8[] sql, u8 want)
{
    Req *req = mem.allocZero(sizeof(Req));
    try {
        self.join(req);
    } catch (Error e) {
        mem.free(req);
        throw(e);
    }
    try {
        self.begin('Q');
        self.putStr(sql);
        self.end();
        self.flush();
        self.turn(req);
        while (true) {
            u8 t = self.recv();
            if (t == want) { return req; }
            if (t == 'E') { self.throwServerError(); }
            if (t == 'Z') { throw(ERR_PROTOCOL, "pg: that statement isn't a COPY %s", want == 'G' ? "FROM STDIN" : "TO STDOUT"); }
            if (t == 'G' || t == 'H') { throw(ERR_PROTOCOL, "pg: a COPY the other way: use copy%s", want == 'G' ? "Out" : "In"); }
        }
    } catch (Error e) {
        self.leave(req);
        mem.free(req);
        throw(e);
    }
    return req;
}

/* Starts a COPY ... FROM STDIN */
pub CopyIn Conn.copyIn(u8[] sql)
{
    CopyIn ci;
    ci.c = self;
    ci.req = self.copyStart(sql, 'G');
    ci.done = false;
    return ci;
}

static Conn *CopyIn.conn_()
{
    if (self.done) { throw(ERR_CLOSED, "pg: the COPY has ended"); }
    return self.c;
}

/* Raw COPY data, in the COPY's format (text, csv or binary) */
pub void CopyIn.write(u8[] data)
{
    Conn *c = self.conn_();
    c.begin('d');
    c.putBytes(data);
    c.end();
    if (c.outLen >= COPY_FLUSH) { c.flush(); }
}

/* One row in COPY's text format (the default): the values tab-separated,
 * escaped, NULL as \N. Values as for parameters; bytea as its \x hex text. */
pub void CopyIn.row(any ...args)
{
    Conn *c = self.conn_();
    ad String line = str.new();
    for (i64 i = 0; i < args.len; i++) {
        if (i) { line.appendByte('\t'); }
        ad String v = str.new();
        if (!argText(args[i], &v, i)) {
            line.append("\\N");
            continue;
        }
        for (i64 j = 0; j < v.len; j++) {
            u8 b = v[j];
            if (b == '\\') { line.append("\\\\"); }
            else if (b == '\t') { line.append("\\t"); }
            else if (b == '\n') { line.append("\\n"); }
            else if (b == '\r') { line.append("\\r"); }
            else { line.appendByte(b); }
        }
    }
    line.appendByte('\n');
    self.write(line);
}

static void CopyIn.finish()
{
    self.done = true;
    self.c.leave(self.req);
    mem.free(self.req);
    self.req = null;
}

/* Ends the COPY: the rows copied. A server error (a bad row) is thrown
 * then, and nothing of the COPY stays. */
pub i64 CopyIn.end()
{
    Conn *c = self.conn_();
    defer self.finish();
    c.begin('c');
    c.end();
    c.flush();
    i64 n = 0;
    while (true) {
        u8 t = c.recv();
        if (t == 'C') { n = tagCount(cstrAt(c.msg, 0)); }
        else if (t == 'E') { c.throwServerError(); }
        else if (t == 'Z') { c.txStatus = c.msg.len ? c.msg[0] : 'I'; break; }
    }
    return n;
}

/* Gives up the COPY: nothing of it stays. Does nothing after end(). */
pub void CopyIn.abort(u8[] why = "aborted by the client")
{
    if (self.done) { return; }
    Conn *c = self.c;
    defer self.finish();
    if (c.dead) { return; }
    try {
        c.begin('f');
        c.putStr(why);
        c.end();
        c.flush();
        while (c.recv() != 'Z') { }
        c.txStatus = c.msg.len ? c.msg[0] : 'I';
    } catch { }
}

/* Starts a COPY ... TO STDOUT (or COPY (query) TO STDOUT) */
pub CopyOut Conn.copyOut(u8[] sql)
{
    CopyOut co;
    mem.set(&co, 0, sizeof(CopyOut));
    co.c = self;
    co.req = self.copyStart(sql, 'H');
    return co;
}

static void CopyOut.finish()
{
    self.done = true;
    self.data = "";
    if (self.req) {
        self.c.leave(self.req);
        mem.free(self.req);
        self.req = null;
    }
}

/* The next chunk into data: true while there is one */
pub bool CopyOut.next()
{
    if (self.done) { return false; }
    Conn *c = self.c;
    while (true) {
        u8 t;
        try {
            t = c.recv();
        } catch (Error e) {
            self.finish();
            throw(e);
        }
        if (t == 'd') {
            self.data = c.msg;
            return true;
        }
        if (t == 'C') { self.count = tagCount(cstrAt(c.msg, 0)); continue; }
        if (t == 'Z') {
            c.txStatus = c.msg.len ? c.msg[0] : 'I';
            self.finish();
            return false;
        }
        if (t == 'E') {
            ServerError e = serverError(c.msg);
            defer e.message.free();
            try { c.drain(); } catch { }
            self.finish();
            throw(e.code, "%s", e.message);
        }
        /* 'c' CopyDone */
    }
    return false;
}

/* The rows copied, once next() returned false */
pub i64 CopyOut.rows()
{
    return self.count;
}

/* Reads and drops what is left */
pub void CopyOut.free()
{
    if (!self.done) {
        try {
            while (self.next()) { }
        } catch { }
    }
    self.finish();
}
