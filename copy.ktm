module pg;
import json, mem, reflect, str;

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
    return c.readCount();
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
        if (t == 'C') {
            try { self.count = tagCount(cstrAt(c.msg, 0)); } catch (Error e) { self.finish(); c.rethrow(&e); }
            continue;
        }
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

/* ---- COPY in binary format, from structs ----
 *
 *   pg.CopyInto<User> in = c.copyInto<User>("users");
 *   for (auto u : users) { in.put(u); }
 *   i64 n = in.end();
 *
 * The struct's fields name the columns (a field's @key, else its name;
 * @skip and private fields left out, as when scanning); the other columns
 * get their defaults. The values go in PostgreSQL's binary format, by the
 * columns' types read from the table once: no text to make or parse. */

pub struct CopyInto<T>
{
    private CopyIn in;
    private i64 cols;
    private i32 *field;         /* per column: the field of T */
    private u32 *oid;           /* per column: its type */
    private bool *jsonb;        /* per column: jsonb (its binary form has a version byte) */
};

/* Can a column of type oid (typtype: 'e' for an enum) go in binary */
static bool copyBinaryOid(u32 oid, u8 typtype)
{
    if (typtype == 'e') { return true; }        /* an enum's binary form is its label */
    switch (oid) {
        case INT2: case INT4: case INT8: case OID: case BOOL: case FLOAT4: case FLOAT8:
        case BYTEA: case UUID: case TIMESTAMP: case TIMESTAMPTZ: case DATE: case INTERVAL:
        case TEXT: case VARCHAR: case BPCHAR: case NAME: case CHAR: case JSON: case JSONB:
            return true;
        default:
            return false;
    }
    return false;
}

/* Is oid a type whose binary form isn't its text (the value must be
 * sent the binary way, paramFormat) */
static bool copyNumericOid(u32 oid)
{
    switch (oid) {
        case INT2: case INT4: case INT8: case OID: case BOOL: case FLOAT4: case FLOAT8:
        case BYTEA: case UUID: case TIMESTAMP: case TIMESTAMPTZ: case DATE: case INTERVAL:
            return true;
        default:
            return false;
    }
    return false;
}

/* "name" with its quotes doubled, appended to out */
static void quoteIdent(String *out, u8[] name)
{
    out.appendByte('"');
    for (i64 i = 0; i < name.len; i++) {
        if (name[i] == '"') { out.appendByte('"'); }
        out.appendByte(name[i]);
    }
    out.appendByte('"');
}

/* Starts a binary COPY into table (as SQL names it: "users",
 * "app.users") of the columns T's fields name. Throws ERR_ARG for a
 * field with no column, or a column whose type has no binary form here
 * (numeric, arrays, inet...: use copyIn's text rows for those). */
pub CopyInto<T> Conn.copyInto<T>(u8[] table)
{
    T zero;
    reflect.Type *t = reflect.of(zero).type;
    if (t.kind != reflect.Kind.structType) {
        throw(ERR_ARG, "pg: copyInto<%s>: rows are structs", t.name);
    }
    CopyInto<T> ci;
    mem.set(&ci, 0, sizeof(CopyInto<T>));
    ci.field = mem.alloc(sizeof(i32) * (t.fields.len + 1));
    ci.oid = mem.alloc(sizeof(u32) * (t.fields.len + 1));
    ci.jsonb = mem.alloc(sizeof(bool) * (t.fields.len + 1));
    ad String sql = str.from("COPY ");
    sql.append(table);
    sql.append(" (");
    try {
        ad Rows r = self.query("SELECT a.attname::text, a.atttypid::int8, t.typtype::text FROM pg_attribute a"
                               " JOIN pg_type t ON t.oid = a.atttypid"
                               " WHERE a.attrelid = $1::regclass AND a.attnum > 0 AND NOT a.attisdropped",
                               table);
        /* each field's column: its key exactly, else ignoring case */
        i64 nrows = 0;
        String *names = null;
        u32 *oids = null;
        u8 *kinds = null;
        defer {
            for (i64 k = 0; k < nrows; k++) { names[k].free(); }
            mem.free(names);
            mem.free(oids);
            mem.free(kinds);
        }
        while (r.next()) {
            names = mem.realloc(names, sizeof(String) * (nrows + 1));
            oids = mem.realloc(oids, sizeof(u32) * (nrows + 1));
            kinds = mem.realloc(kinds, nrows + 1);
            names[nrows] = str.from(r.text(0));
            oids[nrows] = r.int(1);
            u8[] k = r.text(2);
            kinds[nrows] = k.len ? k[0] : 'b';
            nrows++;
        }
        for (i64 j = 0; j < t.fields.len; j++) {
            reflect.Field *f = &t.fields[j];
            if (f.skip || f.isPrivate) { continue; }
            i64 found = -1;
            for (i64 k = 0; k < nrows && found < 0; k++) { if (names[k].equals(f.key)) { found = k; } }
            for (i64 k = 0; k < nrows && found < 0; k++) { if (str.equalsFold(names[k], f.key)) { found = k; } }
            if (found < 0) {
                throw(ERR_ARG, "pg: copyInto<%s>: field %s: %s has no column %s", t.name, f.name, table, f.key);
            }
            if (!copyBinaryOid(oids[found], kinds[found])) {
                throw(ERR_ARG, "pg: copyInto<%s>: column %s is a %s, which a binary COPY can't take here: "
                      "use copyIn and row() for it", t.name, names[found], typeName(oids[found]));
            }
            if (ci.cols) { sql.append(", "); }
            quoteIdent(&sql, names[found]);
            ci.field[ci.cols] = j;
            ci.oid[ci.cols] = oids[found];
            ci.jsonb[ci.cols] = oids[found] == JSONB;
            ci.cols++;
        }
        if (!ci.cols) { throw(ERR_ARG, "pg: copyInto<%s>: no field names a column", t.name); }
        sql.append(") FROM STDIN (FORMAT binary)");
        ci.in = self.copyIn(sql);
    } catch (Error e) {
        mem.free(ci.field);
        mem.free(ci.oid);
        mem.free(ci.jsonb);
        throw(e);
    }
    /* the header: the signature, no flags, no extension */
    Conn *c = self;
    u8 sig[11] = {'P', 'G', 'C', 'O', 'P', 'Y', '\n', 0xff, '\r', '\n', 0};
    c.begin('d');
    c.putBytes(sig[0:11]);
    c.put32(0);
    c.put32(0);
    c.end();
    return ci;
}

/* One row: x's fields into their columns, in binary. A field whose
 * value can't go into its column's type (a String into an int8) is
 * ERR_ARG; a null pointer is NULL. */
pub void CopyInto<T>.put(T x)
{
    Conn *c = self.in.conn_();
    reflect.Type *t = reflect.of(x).type;
    /* every value checked first: a row is written whole or not at all */
    for (i64 i = 0; i < self.cols; i++) {
        reflect.Field *f = &t.fields[self.field[i]];
        reflect.Value v = argValue(reflect.__value((u8 *)&x + f.offset, f.type));
        if (!v.type || !v.ptr) { continue; }
        u32 oid = self.oid[i];
        u8[] b;
        bool ok = copyNumericOid(oid) ? paramFormat(oid, v) != 0
                : v.type.kind == reflect.Kind.enumType || argBytes(v, &b) || oid == JSON || oid == JSONB;
        if (!ok) {
            throw(ERR_ARG, "pg: copyInto: field %s: a %s can't go into a %s column", f.name, v.type.name,
                  typeName(oid));
        }
    }
    c.begin('d');
    c.put16(self.cols);
    for (i64 i = 0; i < self.cols; i++) {
        reflect.Field *f = &t.fields[self.field[i]];
        reflect.Value v = argValue(reflect.__value((u8 *)&x + f.offset, f.type));
        u32 oid = self.oid[i];
        if (!v.type || !v.ptr) {
            c.put32(-1);
            continue;
        }
        if (copyNumericOid(oid)) {
            c.putArg(oid, v, i);
            continue;
        }
        /* text, varchar, json, an enum...: the binary form is the text
         * (jsonb's after a version byte) */
        u8[] b;
        ad String j = str.new();
        if (v.type.kind == reflect.Kind.enumType) {
            b = reflect.nameOf(v.type, reflect.getInt(v));
        } else if (!argBytes(v, &b)) {
            j = json.encode(v);
            b = j[0:];
        }
        if (self.jsonb[i]) {
            c.put32(b.len + 1);
            c.put8(1);
            c.putBytes(b);
        } else {
            c.putText(b);
        }
    }
    c.end();
    if (c.outLen >= COPY_FLUSH) { c.flush(); }
}

static void CopyInto<T>.release()
{
    mem.free(self.field);
    mem.free(self.oid);
    mem.free(self.jsonb);
    self.field = null;
    self.oid = null;
    self.jsonb = null;
}

/* Ends the COPY: the rows copied. A server error is thrown then, and
 * nothing of the COPY stays. */
pub i64 CopyInto<T>.end()
{
    Conn *c = self.in.conn_();
    defer self.release();
    c.begin('d');
    c.put16(-1);
    c.end();
    return self.in.end();
}

/* Gives up the COPY: nothing of it stays. Does nothing after end(). */
pub void CopyInto<T>.abort(u8[] why = "aborted by the client")
{
    self.in.abort(why);
    self.release();
}

/* As abort(), for ad: frees what end() didn't */
pub void CopyInto<T>.free()
{
    if (!self.in.done) { self.in.abort(); }
    self.release();
}
