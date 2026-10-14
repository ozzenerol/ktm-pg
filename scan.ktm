module pg;
import date, json, mem, reflect, str, vec;

/* Rows into ktm values: a struct by column name (a field's @key, else
 * its name; @skip fields are left alone), or one value from the first
 * column. What it allocates (Strings, slices, pointed-to values) is
 * freed with pg.free(reflect.of(x)), or x.freeDecoded() for an @json
 * struct, the same as for json.decode. */

/* Frees what scan, queryAll or queryOne allocated in v, and zeroes it */
pub void free(reflect.Value v)
{
    json.free(v);
}

static bool isVec(reflect.Type *t)
{
    return t.kind == reflect.Kind.structType && str.startsWith(t.name, "vec.Vec<");
}

/* Fills v, a zeroed value, from the current row: a struct (not a
 * date.Date or vec.Vec) by column name, anything else from column 0.
 * flags: pg.STRICT makes a column with no field, a field with no column
 * and a NULL for a non-pointer ERR_SCAN. */
pub void Rows.scan(reflect.Value v, i64 flags = 0)
{
    reflect.Type *t = v.type;
    if (!t || !v.ptr) { throw(ERR_SCAN, "pg: scan into nothing"); }
    if (self.fieldOf && self.scanType == t) {    /* the same struct as the last row: mapped */
        self.scanFields(v.ptr, t, flags);
        return;
    }
    if (t.kind != reflect.Kind.structType || isDate(t) || isVec(t) || isInterval(t)) {
        if (!self.cols) { throw(ERR_SCAN, "pg: scan: the query returns no columns"); }
        if ((flags & STRICT) && self.cols != 1) {
            throw(ERR_SCAN, "pg: scan: %d columns into one %s", self.cols, t.name);
        }
        self.scanValue(v, 0, flags);
        return;
    }
    self.mapFields(t, flags);
    self.scanFields(v.ptr, t, flags);
}

/* A row into the struct at dst, mapped by mapFields */
static void Rows.scanFields(void *dst, reflect.Type *t, i64 flags)
{
    if (!self.row) { throw(ERR_CLOSED, "pg: no current row: call rows.next() first"); }
    u8 *msg = self.base;
    for (i64 i = 0; i < self.cols; i++) {
        i64 f = self.fieldOf[i * 2];
        if (f < 0) { continue; }
        reflect.Field *fd = &t.fields[f];
        u8 *to = (u8 *)dst + fd.offset;
        i64 len = self.off[i * 2 + 1];
        if (len >= 0) {
            u8 *at = msg + self.off[i * 2];
            switch (self.fieldOf[i * 2 + 1]) {     /* the common cases, without reflection */
                case HOW_STRING:
                    setString(to, at, len);
                    continue;
                case HOW_I64:
                    if (len == 8) { *(i64 *)to = (i64)bytesBe64(at[0:8]); continue; }
                    if (len == 4) { *(i64 *)to = (i32)bytesBe32(at[0:4]); continue; }
                case HOW_F64:
                    if (len == 8) { u64 u = bytesBe64(at[0:8]); *(f64 *)to = *(f64 *)&u; continue; }
                case HOW_BOOL:
                    if (len == 1) { *(bool *)to = at[0] != 0; continue; }
                default:
                    break;
            }
        }
        self.scanValue(reflect.__value(to, fd.type), i, flags);
    }
}

/* A String of exactly len bytes at to, a zeroed String field: one
 * malloc, and its three words written directly (a String is a pointer,
 * a length and a capacity) instead of a 24-byte struct copy per value */
static void setString(u8 *to, u8 *at, i64 len)
{
    u8 *p = mem.alloc(len + 1);
    for (i64 i = 0; i < len; i++) { p[i] = at[i]; }
    p[len] = 0;
    u64 *w = (u64 *)to;
    w[0] = (u64)p;
    w[1] = (u64)len;
    w[2] = (u64)len;
}

/* How a column goes into its field (mapFields): the fast ways */
const i32 HOW_GENERAL = 0;
const i32 HOW_STRING = 1;      /* a text-format column into a String */
const i32 HOW_I64 = 2;         /* a binary int8 or int4 into an i64 */
const i32 HOW_F64 = 3;         /* a binary float8 into an f64 */
const i32 HOW_BOOL = 4;        /* a binary bool into a bool */

static i32 howOf(Column *col, reflect.Type *ft)
{
    if (sizeof(String) != 24) { return HOW_GENERAL; }  /* setString writes three words */
    if (isDate(ft) || isInterval(ft) || arrayElem(col.oid)) { return HOW_GENERAL; }
    if (ft.kind == reflect.Kind.string && !col.format && col.oid != JSON && col.oid != JSONB) { return HOW_STRING; }
    if (!col.format) { return HOW_GENERAL; }
    if (ft.kind == reflect.Kind.int && ft.size == 8 && ft.isSigned && (col.oid == INT8 || col.oid == INT4)) { return HOW_I64; }
    if (ft.kind == reflect.Kind.float && ft.size == 8 && col.oid == FLOAT8) { return HOW_F64; }
    if (ft.kind == reflect.Kind.boolean && ft.size == 1 && col.oid == BOOL) { return HOW_BOOL; }
    return HOW_GENERAL;
}

/* Which field each column fills: its key exactly, else ignoring case */
static void Rows.mapFields(reflect.Type *t, i64 flags)
{
    if (!self.fieldOf) { self.fieldOf = mem.alloc(sizeof(i32) * 2 * (self.cols + 1)); }
    self.scanType = t;
    for (i64 i = 0; i < self.cols; i++) {
        u8[] name = self.st.cols[i].name;
        i64 found = -1;
        for (i64 j = 0; j < t.fields.len && found < 0; j++) {
            reflect.Field *f = &t.fields[j];
            if (!f.skip && !f.isPrivate && f.key.equals(name)) { found = j; }
        }
        for (i64 j = 0; j < t.fields.len && found < 0; j++) {
            reflect.Field *f = &t.fields[j];
            if (!f.skip && !f.isPrivate && str.equalsFold(f.key, name)) { found = j; }
        }
        if (found < 0 && (flags & STRICT)) {
            mem.free(self.fieldOf);
            self.fieldOf = null;
            throw(ERR_SCAN, "pg: column %s: no such field in %s", name, t.name);
        }
        self.fieldOf[i * 2] = found;
        self.fieldOf[i * 2 + 1] = found < 0 ? HOW_GENERAL : howOf(&self.st.cols[i], t.fields[found].type);
    }
    if (flags & STRICT) {
        for (i64 j = 0; j < t.fields.len; j++) {
            reflect.Field *f = &t.fields[j];
            if (f.skip || f.isPrivate) { continue; }
            bool has = false;
            for (i64 i = 0; i < self.cols; i++) { if (self.fieldOf[i * 2] == j) { has = true; } }
            if (!has) {
                mem.free(self.fieldOf);
                self.fieldOf = null;
                throw(ERR_SCAN, "pg: field %s of %s: no such column", f.name, t.name);
            }
        }
    }
}

static void Rows.scanError(i64 i, reflect.Type *t)
{
    Column *col = &self.st.cols[i];
    throw(ERR_SCAN, "pg: column %s: a %s can't go into a %s", col.name, typeName(col.oid), t.name);
}

/* Column i into v, a zeroed value */
static void Rows.scanValue(reflect.Value v, i64 i, i64 flags)
{
    reflect.Type *t = v.type;
    if (self.isNull(i)) {
        if ((flags & STRICT) && t.kind != reflect.Kind.pointer) {
            throw(ERR_SCAN, "pg: column %s is NULL, and a %s can't be", self.st.cols[i].name, t.name);
        }
        mem.set(v.ptr, 0, t.size);
        return;
    }
    Column *col = &self.st.cols[i];
    if (isDate(t)) {
        *(date.Date *)v.ptr = self.date(i);
        return;
    }
    if (isInterval(t) && col.oid == INTERVAL) {
        *(Interval *)v.ptr = self.interval(i);
        return;
    }
    u32 elem = arrayElem(col.oid);
    if (elem && t.kind != reflect.Kind.string && !(t.kind == reflect.Kind.slice && isByte(t.elem))) {
        /* an array into a slice, a fixed array or a vec.Vec: through JSON */
        ad String j = str.new();
        reflect.Type *et = listElem(t);
        bool zero = et && et.kind != reflect.Kind.pointer;      /* NULL elements: 0, false, "" */
        if (!arrayToJson(self.text(i), elem, &j, zero)) {
            throw(ERR_SCAN, "pg: column %s: \"%s\" isn't an array", col.name, self.text(i));
        }
        try {
            json.decode(j, v);
        } catch (Error e) {
            throw(ERR_SCAN, "pg: column %s: %s", col.name, e.message);
        }
        return;
    }
    switch (t.kind) {
        case reflect.Kind.int:
            if (!reflect.setInt(v, self.int(i))) {
                throw(ERR_SCAN, "pg: column %s: %d doesn't fit in a %s", col.name, self.int(i), t.name);
            }
            return;
        case reflect.Kind.boolean:
            reflect.setInt(v, self.bool(i));
            return;
        case reflect.Kind.float:
            reflect.setFloat(v, self.float(i));
            return;
        case reflect.Kind.enumType: {
            i64 n;
            if (col.format) {
                n = self.int(i);
            } else if (!reflect.enumValue(t, self.text(i), &n)) {
                if (!str.toInt(self.text(i), &n)) {
                    throw(ERR_SCAN, "pg: column %s: \"%s\" isn't a %s", col.name, self.text(i), t.name);
                }
            }
            reflect.setInt(v, n);
            return;
        }
        case reflect.Kind.string:
            *(String *)v.ptr = str.from(self.text(i));
            return;
        case reflect.Kind.slice:
            if (isByte(t.elem)) {
                u8[] b = self.bytes(i);
                u8 *p = mem.alloc(b.len + 1);
                mem.copy(p, b.ptr, b.len);
                p[b.len] = 0;
                u8[] *s = v.ptr;
                *s = p[0:b.len];
                return;
            }
        case reflect.Kind.array:
            if (t.kind == reflect.Kind.array && isByte(t.elem)) {
                u8[] b = self.bytes(i);
                if (b.len > t.len) {
                    throw(ERR_SCAN, "pg: column %s: %d bytes don't fit in a %s", col.name, b.len, t.name);
                }
                mem.copy(v.ptr, b.ptr, b.len);
                return;
            }
        case reflect.Kind.pointer:
            if (t.kind == reflect.Kind.pointer) {
                if (isByte(t.elem)) {
                    u8[] b = self.text(i);
                    u8 *p = mem.alloc(b.len + 1);
                    mem.copy(p, b.ptr, b.len);
                    p[b.len] = 0;
                    *(u8 **)v.ptr = p;
                    return;
                }
                void *p = mem.allocZero(t.elem.size);
                *(void **)v.ptr = p;
                self.scanValue(reflect.__value(p, t.elem), i, flags);
                return;
            }
        default:
            break;
    }
    if (t.kind == reflect.Kind.structType || t.kind == reflect.Kind.slice || t.kind == reflect.Kind.array) {
        if (col.oid != JSON && col.oid != JSONB && col.format) { self.scanError(i, t); }
        json.decode(self.text(i), v);
        return;
    }
    self.scanError(i, t);
}

/* ---- typed queries ---- */

/* Every row of the query, each scanned into a T:
 *   vec.Vec<User> users = pg.queryAll<User>(db, "SELECT * FROM users WHERE age > $1", 18);
 *   defer pg.free(reflect.of(users));
 * db is a Conn *, a Tx * or a Pool *. */
pub vec.Vec<T> queryAll<T>(Db *db, u8[] sql, any ...args)
{
    if (!db) { dbNull("queryAll"); }
    ad Rows r = db.open(db, sql, args, true);
    return rowsAll<T>(&r);
}

static vec.Vec<T> rowsAll<T>(Rows *r)
{
    vec.Vec<T> out = vec.new<T>();
    T x;
    mem.set(&x, 0, sizeof(T));
    reflect.Value xv = reflect.of(x);
    try {
        while (r.next()) {
            if (r.fieldOf && r.scanType == xv.type) { r.scanFields(&x, xv.type, 0); } else { r.scan(xv); }
            out.push(x);
            mem.set(&x, 0, sizeof(T));
        }
    } catch (Error e) {
        json.free(reflect.of(x));
        json.free(reflect.of(out));
        throw(e);
    }
    return out;
}

/* The first row of the query as a T; throws ERR_NO_ROWS when there is
 * none. A T that isn't a struct is the first column:
 *   i64 n = pg.queryOne<i64>(db, "SELECT count(*) FROM users");
 *   User u = pg.queryOne<User>(db, "SELECT * FROM users WHERE id = $1", 7); */
pub T queryOne<T>(Db *db, u8[] sql, any ...args)
{
    if (!db) { dbNull("queryOne"); }
    ad Rows r = db.open(db, sql, args, true);
    return rowsOne<T>(&r);
}

static T rowsOne<T>(Rows *r)
{
    T x;
    mem.set(&x, 0, sizeof(T));
    if (!r.next()) { throw(ERR_NO_ROWS, "pg: queryOne: no rows"); }
    try {
        r.scan(reflect.of(x));
    } catch (Error e) {
        json.free(reflect.of(x));
        throw(e);
    }
    return x;
}
