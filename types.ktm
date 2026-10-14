module pg;
import cstr, json, libc, reflect, str;

/* Type OIDs of the server */
pub const u32 BOOL = 16;
pub const u32 BYTEA = 17;
pub const u32 CHAR = 18;
pub const u32 NAME = 19;
pub const u32 INT8 = 20;
pub const u32 INT2 = 21;
pub const u32 INT4 = 23;
pub const u32 TEXT = 25;
pub const u32 OID = 26;
pub const u32 JSON = 114;
pub const u32 FLOAT4 = 700;
pub const u32 FLOAT8 = 701;
pub const u32 BPCHAR = 1042;
pub const u32 VARCHAR = 1043;
pub const u32 DATE = 1082;
pub const u32 TIME = 1083;
pub const u32 TIMESTAMP = 1114;
pub const u32 TIMESTAMPTZ = 1184;
pub const u32 NUMERIC = 1700;
pub const u32 UUID = 2950;
pub const u32 JSONB = 3802;

/* The format Bind asks for a result column of type oid: 0 text, 1 binary */
static i16 resultFormat(u32 oid)
{
    return 0;
}

/* The format a parameter is sent in */
static i16 paramFormat(u32 oid, reflect.Value v)
{
    return 0;
}

/* ---- parameters ---- */

static void Conn.putDecimal(i64 n)
{
    u8 buf[24];
    i64 i = 24;
    bool neg = n < 0;
    u64 u = neg ? (u64)(0 - n) : (u64)n;
    do {
        buf[--i] = '0' + u % 10;
        u /= 10;
    } while (u);
    if (neg) { buf[--i] = '-'; }
    self.put32(24 - i);
    self.putBytes(buf[i:24]);
}

/* A value given as text: its length and bytes */
static void Conn.putText(u8[] t)
{
    self.put32(t.len);
    self.putBytes(t);
}

/* Parameter i, of the server's type oid, from v */
static void Conn.putArg(u32 oid, reflect.Value v, i64 i)
{
    reflect.Type *t = v.type;
    if (!t || !v.ptr) { self.put32(-1); return; }
    switch (t.kind) {
        case reflect.Kind.int:
            self.putDecimal(reflect.getInt(v));
            return;
        case reflect.Kind.boolean:
            if (reflect.getInt(v)) { self.putText("t"); } else { self.putText("f"); }
            return;
        case reflect.Kind.float: {
            u8 buf[32];
            i64 n = libc.snprintf(buf, sizeof(buf), "%.17g", reflect.getFloat(v));
            self.putText(buf[0:n]);
            return;
        }
        case reflect.Kind.enumType:
            self.putText(reflect.nameOf(t, reflect.getInt(v)));
            return;
        case reflect.Kind.string: {
            String *s = v.ptr;
            self.putText(*s);
            return;
        }
        case reflect.Kind.slice:
            if (t.elem.kind == reflect.Kind.int && t.elem.size == 1) {
                u8[] *s = v.ptr;
                self.putText(*s);
                return;
            }
        case reflect.Kind.array:
            if (t.kind == reflect.Kind.array && t.elem.kind == reflect.Kind.int && t.elem.size == 1) {
                self.putText(((u8 *)v.ptr)[0:t.len]);
                return;
            }
        case reflect.Kind.pointer: {
            if (t.kind == reflect.Kind.pointer) {
                void *p = *(void **)v.ptr;
                if (!p) { self.put32(-1); return; }
                if (t.elem.kind == reflect.Kind.int && t.elem.size == 1) {
                    self.putText(cstr.view(p));
                    return;
                }
                self.putArg(oid, reflect.__value(p, t.elem), i);
                return;
            }
        }
        default:
            break;
    }
    if (t.kind == reflect.Kind.structType || t.kind == reflect.Kind.slice || t.kind == reflect.Kind.array) {
        ad String j = json.encode(v);
        self.putText(j);
        return;
    }
    throw(ERR_ARG, "pg: parameter $%d: a %s can't be sent", i + 1, t.name);
}

/* ---- reading values ---- */

static i64 parseInt(u8[] s, bool *ok)
{
    i64 n;
    *ok = str.toInt(s, &n);
    return n;
}

/* Column i as an integer: 0 for NULL. Throws ERR_SCAN for a value that
 * isn't one. */
pub i64 Rows.int(i64 i)
{
    self.check(i);
    i64 len = self.off[i * 2 + 1];
    if (len < 0) { return 0; }
    u8[] b = self.c.msg[self.off[i * 2]:self.off[i * 2] + len];
    bool ok;
    i64 n = parseInt(b, &ok);
    if (!ok) {
        if (b.equals("t")) { return 1; }
        if (b.equals("f")) { return 0; }
        throw(ERR_SCAN, "pg: column %s: \"%s\" isn't an integer", self.st.cols[i].name, b);
    }
    return n;
}

/* Column i as a float: 0 for NULL */
pub f64 Rows.float(i64 i)
{
    self.check(i);
    i64 len = self.off[i * 2 + 1];
    if (len < 0) { return 0; }
    u8[] b = self.c.msg[self.off[i * 2]:self.off[i * 2] + len];
    f64 x;
    if (!str.toFloat(b, &x)) {
        throw(ERR_SCAN, "pg: column %s: \"%s\" isn't a number", self.st.cols[i].name, b);
    }
    return x;
}

/* Column i as a bool: false for NULL */
pub bool Rows.bool(i64 i)
{
    self.check(i);
    i64 len = self.off[i * 2 + 1];
    if (len < 0) { return false; }
    u8[] b = self.c.msg[self.off[i * 2]:self.off[i * 2] + len];
    if (b.equals("t")) { return true; }
    if (b.equals("f")) { return false; }
    throw(ERR_SCAN, "pg: column %s: \"%s\" isn't a bool", self.st.cols[i].name, b);
    return false;
}

/* Column i as text, a view valid until the next row: "" for NULL */
pub u8[] Rows.text(i64 i)
{
    return self.raw(i);
}
