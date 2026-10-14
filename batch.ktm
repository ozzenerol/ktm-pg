module pg;
import json, mem, reflect, str;

/* A batch: several statements sent at once, in one round trip, and run
 * as one implicit transaction: all of them or, when one fails, none.
 *
 *   pg.Batch b;
 *   defer b.free();
 *   for (auto u : users) { b.add("INSERT INTO users (name) VALUES ($1)", u.name); }
 *   i64[] counts = c.execBatch(&b);      // rows each one changed; yours: counts.free()
 *
 * add() copies its arguments, so they may go away right after it. */
pub struct Batch
{
    private BatchItem *items;
    private i64 n;
    private i64 cap;
};

struct BatchItem
{
    String sql;
    BatchArg *args;
    i64 nargs;
};

/* A copy of an argument: its bytes and type, or for a list or a struct
 * its JSON and (lists) its array text, chosen when the parameter's type
 * is known */
struct BatchArg
{
    reflect.Type *type;         /* null: NULL */
    u8 *data;                   /* a copy of the value (no pointers inside) */
    String text;                /* bytes: the bytes; a list or struct: its JSON */
    String array;               /* a list: the server's array text */
    bool bytes;
    bool list;
    u8[] view;                  /* what a u8[] value points at */
};

static void BatchArg.free()
{
    mem.free(self.data);
    self.text.free();
    self.array.free();
}

/* Adds a statement and its parameters to the batch */
pub void Batch.add(u8[] sql, any ...args)
{
    if (self.n == self.cap) {
        self.cap = self.cap ? self.cap * 2 : 16;
        self.items = mem.realloc(self.items, sizeof(BatchItem) * self.cap);
    }
    BatchItem *it = &self.items[self.n];
    mem.set(it, 0, sizeof(BatchItem));
    it.sql = str.from(sql);
    it.args = mem.allocZero(sizeof(BatchArg) * (args.len + 1));
    it.nargs = args.len;
    self.n++;
    for (i64 i = 0; i < args.len; i++) {
        BatchArg *a = &it.args[i];
        reflect.Value v = argValue(args[i]);
        reflect.Type *t = v.type;
        if (!t || !v.ptr || (t.kind == reflect.Kind.pointer && !*(void **)v.ptr)) { continue; }
        u8[] b;
        u8 *la;
        i64 ln;
        reflect.Type *le;
        a.type = t;
        if (argBytes(v, &b)) {
            a.bytes = true;
            a.text = str.from(b);
        } else if (listOf(v, &la, &ln, &le)) {
            a.list = true;
            a.text = json.encode(v);
            a.array = str.new();
            arrayText(v, &a.array, i);
        } else if (t.kind == reflect.Kind.structType && !isDate(t) && !isInterval(t)) {
            a.list = true;          /* JSON only */
            a.text = json.encode(v);
        } else {
            a.data = mem.alloc(t.size + 1);
            mem.copy(a.data, v.ptr, t.size);
        }
    }
}

/* Statements in the batch */
pub i64 Batch.len()
{
    return self.n;
}

/* Empties the batch (it can be filled again) */
pub void Batch.clear()
{
    for (i64 i = 0; i < self.n; i++) {
        BatchItem *it = &self.items[i];
        it.sql.free();
        for (i64 j = 0; j < it.nargs; j++) { it.args[j].free(); }
        mem.free(it.args);
    }
    self.n = 0;
}

pub void Batch.free()
{
    self.clear();
    mem.free(self.items);
    self.items = null;
    self.cap = 0;
}

static reflect.Value BatchArg.value(u32 oid)
{
    reflect.Value v;
    v.ptr = null;
    v.type = null;
    if (!self.type) { return v; }
    if (self.bytes || self.list) {
        if (self.list && self.array.len && arrayElem(oid)) { self.view = self.array; }
        else { self.view = self.text; }
        return reflect.of(self.view);
    }
    v.ptr = self.data;
    v.type = self.type;
    return v;
}

/* Runs the batch: every statement in one round trip and one implicit
 * transaction. Returns the rows each one changed (yours to free); a
 * server error is thrown (saying which statement) and nothing of the
 * batch stays. */
pub i64[] Conn.execBatch(Batch *b)
{
    i64[] none = {null, 0};
    if (!b.n) { return none; }
    Stmt **sts = mem.allocZero(sizeof(Stmt *) * b.n);
    defer mem.free(sts);
    Req r;
    for (i64 i = 0; i < b.n; i++) {
        sts[i] = self.prepare(b.items[i].sql, &r);
        sts[i].refs++;
    }
    defer {
        for (i64 i = 0; i < b.n; i++) { if (sts[i]) { sts[i].refs--; } }
    }
    self.join(&r);
    defer self.leave(&r);
    i64 start = self.outLen;
    try {
        for (i64 i = 0; i < b.n; i++) {
            BatchItem *it = &b.items[i];
            Stmt *st = sts[i];
            if (it.nargs != st.nparams) {
                throw(ERR_ARG, "pg: batch statement %d has %d parameters, %d given", i + 1, st.nparams, it.nargs);
            }
            reflect.Value *vals = mem.alloc(sizeof(reflect.Value) * (it.nargs + 1));
            defer mem.free(vals);
            for (i64 j = 0; j < it.nargs; j++) { vals[j] = it.args[j].value(st.paramOids[j]); }
            self.bindExecute(st, vals[0:it.nargs], false);
        }
    } catch (Error e) {
        self.outLen = start;
        throw(e);
    }
    self.begin('S');
    self.end();
    self.flush();
    self.turn(&r);
    i64 *counts = mem.allocZero(sizeof(i64) * b.n);
    i64 k = 0;
    bool failed = false;
    ServerError err;
    while (true) {
        u8 t = self.recv();
        if (t == 'C') {
            if (k < b.n) { counts[k] = tagCount(cstrAt(self.msg, 0)); }
            k++;
        } else if (t == 'E' && !failed) {
            err = self.serverErr();
            failed = true;
        } else if (t == 'Z') {
            self.txStatus = self.msg.len ? self.msg[0] : 'I';
            break;
        }
    }
    if (failed) {
        mem.free(counts);
        defer err.message.free();
        throw(err.code, "pg: batch statement %d: %s", k + 1, err.message);
    }
    return counts[0:b.n];
}

pub i64[] Pool.execBatch(Batch *b)
{
    Conn *c = self.acquire();
    defer c.release();
    return c.execBatch(b);
}

pub i64[] Tx.execBatch(Batch *b)
{
    return self.conn_().execBatch(b);
}
