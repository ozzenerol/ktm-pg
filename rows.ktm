module pg;
import mem, reflect;

/* The rows of a query, read one at a time from the connection as they
 * come. The values of a row are views valid until the next call to
 * next(). Free the Rows with rows.free() (or declare them ad): it reads
 * and drops what is left and gives the connection back. */
pub struct Rows
{
    i64 cols;                   /* columns per row */
    private Conn *c;
    private Stmt *st;
    private i32 *off;           /* per column: where its value starts in the row, its length (-1: NULL) */
    private bool done;
    private bool row;           /* a row is there to read */
    private i64 count;
    private u8 *fmt;            /* 48 bytes per column: text() of a binary value */
    private i32 *fieldOf;       /* scan: per column, the field it fills (-1: none) */
    private reflect.Type *scanType;
    private bool own;           /* the connection goes back to its pool when the rows end */
    private Req *req;           /* its place in the connection's queue, until the rows end */
    private bool started;       /* its turn came: it reads */
    private u8 *base;           /* the current row's message: off[] counts from here */
    private Arena *arena;       /* queryResult: text and bytes go here, not one malloc each */
};

static void Rows.finish()
{
    self.done = true;
    self.row = false;
    if (self.req) {
        self.c.leave(self.req);
        mem.free(self.req);
        self.req = null;
        self.st.refs--;
    }
    if (self.own) {
        self.own = false;
        self.c.close();
    }
}

/* The next row: true when there is one. Throws the server's error when
 * the query fails half way (the connection stays usable). */
pub bool Rows.next()
{
    if (self.done) { return false; }
    if (self.started && self.quickRow()) { return true; }
    Conn *c = self.c;
    try {
        if (!self.started) {
            self.started = true;
            c.turn(self.req);
        }
        return self.read();
    } catch (Error e) {
        if (!self.done) { self.finish(); }
        if (e.code == ERR_PROTOCOL || e.code == ERR_BOUNDS) { c.rethrow(&e); }
        throw(e);
    }
    return false;
}

/* The next row without a try frame (a setjmp per row): only when a
 * whole, well-formed DataRow is in the buffer already; anything else
 * (more to read, another message, a bad row) goes the careful way */
static bool Rows.quickRow()
{
    Conn *c = self.c;
    i64 have = c.inEnd - c.inStart;
    if (have < 7) { return false; }
    u8 *p = c.in + c.inStart;
    if (p[0] != 'D') { return false; }
    i64 len = (i64)p[1] << 24 | (i64)p[2] << 16 | (i64)p[3] << 8 | p[4];
    if (len < 6 || 1 + len > have) { return false; }
    u8 *m = p + 5;
    i64 mlen = len - 4;
    i64 n = (i16)((u16)m[0] << 8 | m[1]);
    if (n != self.cols) { return false; }
    i64 at = 2;
    i32 *off = self.off;
    for (i64 i = 0; i < n; i++) {
        if (at + 4 > mlen) { return false; }
        i64 vl = (i32)((u32)m[at] << 24 | (u32)m[at + 1] << 16 | (u32)m[at + 2] << 8 | m[at + 3]);
        at += 4;
        if (vl < -1 || (vl > 0 && at + vl > mlen)) { return false; }
        off[i * 2] = at;
        off[i * 2 + 1] = vl;
        if (vl > 0) { at += vl; }
    }
    c.msgType = 'D';
    self.base = m;              /* not c.msg: a slice copy costs more than the row */
    c.inStart += 1 + len;
    self.row = true;
    return true;
}

static bool Rows.read()
{
    Conn *c = self.c;
    while (true) {
        u8 t = c.recv();
        if (t == 'D') {
            u8 *m = c.msg.ptr;
            i64 mlen = c.msg.len;
            self.base = m;
            if (mlen < 2) { throw(ERR_PROTOCOL, "pg: a row too short"); }
            i64 n = (i16)((u16)m[0] << 8 | m[1]);
            if (n != self.cols) { throw(ERR_PROTOCOL, "pg: a row of %d columns, expected %d", n, self.cols); }
            i64 at = 2;
            i32 *off = self.off;
            for (i64 i = 0; i < n; i++) {
                if (at + 4 > mlen) { throw(ERR_PROTOCOL, "pg: a row shorter than its columns"); }
                i64 len = (i32)((u32)m[at] << 24 | (u32)m[at + 1] << 16 | (u32)m[at + 2] << 8 | m[at + 3]);
                at += 4;
                if (len < -1 || (len > 0 && at + len > mlen)) { throw(ERR_PROTOCOL, "pg: a value longer than its row"); }
                off[i * 2] = at;
                off[i * 2 + 1] = len;
                if (len > 0) { at += len; }
            }
            self.row = true;
            return true;
        }
        if (t == 'C') { self.count = tagCount(cstrAt(c.msg, 0)); continue; }
        if (t == 'Z') {
            c.txStatus = c.msg.len ? c.msg[0] : 'I';
            self.finish();
            return false;
        }
        if (t == 'E') {
            ServerError e = c.serverErr();
            defer e.message.free();
            try { c.drain(); } catch { }
            self.finish();
            throw(e.code, "%s", e.message);
        }
        /* '2' BindComplete, 'n' NoData, 's' PortalSuspended, 'I' EmptyQueryResponse */
    }
    return false;
}

/* Reads and drops the rows that are left, and frees the Rows */
pub void Rows.free()
{
    if (self.c && !self.done) {
        try {
            while (self.next()) { }
        } catch { }
    }
    mem.free(self.off);
    mem.free(self.fmt);
    mem.free(self.fieldOf);
    self.off = null;
    self.fmt = null;
    self.fieldOf = null;
    self.done = true;
    self.row = false;
}

static void Rows.check(i64 i)
{
    if (!self.row) { throw(ERR_CLOSED, "pg: no current row: call rows.next() first"); }
    if (i < 0 || i >= self.cols) { throw(ERR_BOUNDS, "pg: column %d of %d", i, self.cols); }
}

/* Whether column i of the row is NULL */
pub bool Rows.isNull(i64 i)
{
    self.check(i);
    return self.off[i * 2 + 1] < 0;
}

/* Column i's raw bytes as the server sent them (text, or binary for the
 * types read in binary): "" for NULL */
pub u8[] Rows.raw(i64 i)
{
    self.check(i);
    i64 len = self.off[i * 2 + 1];
    if (len <= 0) { return ""; }
    return (self.base + self.off[i * 2])[0:len];
}

/* Column i's name */
pub u8[] Rows.name(i64 i)
{
    if (i < 0 || i >= self.cols) { throw(ERR_BOUNDS, "pg: column %d of %d", i, self.cols); }
    return self.st.cols[i].name;
}

/* Column i's type, as the server's OID (pg.INT8, pg.TEXT ...) */
pub u32 Rows.typeOid(i64 i)
{
    if (i < 0 || i >= self.cols) { throw(ERR_BOUNDS, "pg: column %d of %d", i, self.cols); }
    return self.st.cols[i].oid;
}

/* The index of the column called name, -1 if none */
pub i64 Rows.column(u8[] name)
{
    for (i64 i = 0; i < self.cols; i++) {
        if (self.st.cols[i].name.equals(name)) { return i; }
    }
    return -1;
}

/* The rows the statement changed or returned, once next() returned false */
pub i64 Rows.affected()
{
    return self.count;
}

static Column *Rows.col(i64 i)
{
    return &self.st.cols[i];
}
