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
    Conn *c = self.c;
    if (!self.started) {
        self.started = true;
        try {
            c.turn(self.req);
        } catch (Error e) {
            self.finish();
            throw(e);
        }
    }
    while (true) {
        u8 t;
        try {
            t = c.recv();
        } catch (Error e) {
            self.finish();
            throw(e);
        }
        if (t == 'D') {
            u8[] m = c.msg;
            i64 n = be16At(m, 0);
            if (n != self.cols) { self.finish(); throw(ERR_PROTOCOL, "pg: a row of %d columns, expected %d", n, self.cols); }
            i64 at = 2;
            for (i64 i = 0; i < n; i++) {
                i64 len = be32At(m, at);
                at += 4;
                self.off[i * 2] = at;
                self.off[i * 2 + 1] = len;
                if (len > 0) { at += len; }
            }
            if (at > m.len) { self.finish(); throw(ERR_PROTOCOL, "pg: a row longer than its message"); }
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
    return self.c.msg[self.off[i * 2]:self.off[i * 2] + len];
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
