module pg;
import cstr, date, json, libc, mem, reflect, str;

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

/* scan flags */
pub const i64 STRICT = 1;   /* a field with no column, a column with no field, or NULL into a non-pointer: ERR_SCAN */

/* Days from 1970-01-01 to 2000-01-01, the server's epoch */
const i64 PG_EPOCH_DAYS = 10957;
const i64 DAY_US = 86400000000;

/* The format Bind asks for a result column of type oid: 0 text, 1 binary.
 * Binary where it is cheaper to read; text where the text is the value. */
static i16 resultFormat(u32 oid)
{
    switch (oid) {
        case BOOL: case BYTEA: case INT8: case INT2: case INT4: case OID:
        case FLOAT4: case FLOAT8: case DATE: case TIMESTAMP: case TIMESTAMPTZ: case UUID:
            return 1;
        default:
            return 0;
    }
    return 0;
}

/* ---- dates ---- */

/* Days since 1970-01-01 of a civil date (proleptic Gregorian) */
static i64 daysFromCivil(i64 y, i64 m, i64 d)
{
    if (m <= 2) { y--; }
    i64 era = (y >= 0 ? y : y - 399) / 400;
    i64 yoe = y - era * 400;
    i64 mp = m > 2 ? m - 3 : m + 9;
    i64 doy = (153 * mp + 2) / 5 + d - 1;
    i64 doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + doe - 719468;
}

static void civilFromDays(i64 z, i64 *y, i64 *m, i64 *d)
{
    z += 719468;
    i64 era = (z >= 0 ? z : z - 146096) / 146097;
    i64 doe = z - era * 146097;
    i64 yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    i64 doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    i64 mp = (5 * doy + 2) / 153;
    *d = doy - (153 * mp + 2) / 5 + 1;
    *m = mp < 10 ? mp + 3 : mp - 9;
    *y = yoe + era * 400 + (*m <= 2);
}

static i64 floorDiv(i64 a, i64 b)
{
    i64 q = a / b;
    if ((a % b != 0) && ((a < 0) != (b < 0))) { q--; }
    return q;
}

/* Microseconds since 1970 (UTC) as a date.Date (to 1/10000 s) */
pub date.Date dateFromMicros(i64 us)
{
    i64 days = floorDiv(us, DAY_US);
    i64 rem = us - days * DAY_US;
    i64 y, m, d;
    civilFromDays(days, &y, &m, &d);
    date.Parts p;
    mem.set(&p, 0, sizeof(date.Parts));
    p.year = y;
    p.mon = m;
    p.dayOfMon = d;
    p.hour = rem / 3600000000;
    p.min = rem / 60000000 % 60;
    p.sec = rem / 1000000 % 60;
    p.sec100 = rem / 10000 % 100;
    p.sec10000 = rem / 100 % 100;
    return date.fromParts(&p);
}

/* A date.Date as microseconds since 1970 (UTC). The time of day is
 * rounded to the nearest 1/10000 s here: date.toParts adds one unit to
 * a time that was exact. */
pub i64 microsFromDate(date.Date t)
{
    date.Parts p;
    date.toParts(&p, t);
    i64 days = daysFromCivil(p.year, p.mon, p.dayOfMon);
    i64 tick = ((i64)t.time * 421875 + (1 << 20)) >> 21;     /* 1/10000 s since midnight */
    return days * DAY_US + tick * 100;
}

/* ---- parameters ---- */

/* v with pointers followed: what is sent for it (a null pointer: NULL,
 * returned as a Value with a null ptr). u8 * stays: it is text. */
static reflect.Value argValue(reflect.Value v)
{
    while (v.type && v.ptr && v.type.kind == reflect.Kind.pointer && !isByte(v.type.elem)) {
        void *p = *(void **)v.ptr;
        if (!p) { v.ptr = null; return v; }
        v = reflect.__value(p, v.type.elem);
    }
    return v;
}

static bool isByte(reflect.Type *t)
{
    return t && t.kind == reflect.Kind.int && t.size == 1;
}

static bool isDate(reflect.Type *t)
{
    return t && t.name.equals("date.Date");     /* an i64 struct: its kind is int */
}

/* Bytes v holds as text or binary data (String, u8[], u8 array, u8 *):
 * true and *out, else false */
static bool argBytes(reflect.Value v, u8[] *out)
{
    reflect.Type *t = v.type;
    switch (t.kind) {
        case reflect.Kind.string: {
            String *s = v.ptr;
            *out = *s;
            return true;
        }
        case reflect.Kind.slice:
            if (!isByte(t.elem)) { return false; }
            *out = *(u8[] *)v.ptr;
            return true;
        case reflect.Kind.array:
            if (!isByte(t.elem)) { return false; }
            *out = ((u8 *)v.ptr)[0:t.len];
            return true;
        case reflect.Kind.pointer:
            if (!isByte(t.elem)) { return false; }
            u8 *p = *(u8 **)v.ptr;
            if (!p) { return false; }
            *out = cstr.view(p);
            return true;
        default:
            return false;
    }
    return false;
}

/* The format a parameter goes in, for the server's type oid */
static i16 paramFormat(u32 oid, reflect.Value v)
{
    v = argValue(v);
    reflect.Type *t = v.type;
    if (!t || !v.ptr) { return 0; }
    if (isDate(t)) { return oid == TIMESTAMP || oid == TIMESTAMPTZ || oid == DATE ? 1 : 0; }
    reflect.Kind k = t.kind;
    bool num = k == reflect.Kind.int || k == reflect.Kind.boolean;
    switch (oid) {
        case INT2: case INT4: case INT8: case OID: case BOOL:
            return num ? 1 : 0;
        case FLOAT4: case FLOAT8:
            return num || k == reflect.Kind.float ? 1 : 0;
        case BYTEA: {
            u8[] b;
            return argBytes(v, &b) ? 1 : 0;
        }
        case UUID: {
            /* 16 raw bytes (a u8[16] arrives as a u8[]); text is longer */
            u8[] b;
            return k != reflect.Kind.string && argBytes(v, &b) && b.len == 16 ? 1 : 0;
        }
        case TIMESTAMP: case TIMESTAMPTZ: case DATE:
            return isDate(t) ? 1 : 0;
        default:
            return 0;
    }
    return 0;
}

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

static void Conn.putBe64(i64 v)
{
    self.put32(v >> 32);
    self.put32(v & 0xFFFFFFFF);
}

static void argRange(i64 n, i64 lo, i64 hi, i64 i, u8 *type)
{
    if (n < lo || n > hi) { throw(ERR_ARG, "pg: parameter $%d: %d doesn't fit in %s", i + 1, n, type); }
}

/* Parameter i, of the server's type oid, from v, in the format
 * paramFormat chose */
static void Conn.putArg(u32 oid, reflect.Value v, i64 i)
{
    v = argValue(v);
    reflect.Type *t = v.type;
    if (!t || !v.ptr || (t.kind == reflect.Kind.pointer && !*(void **)v.ptr)) { self.put32(-1); return; }
    if (paramFormat(oid, v)) {
        switch (oid) {
            case INT2: {
                i64 n = reflect.getInt(v);
                argRange(n, -32768, 32767, i, "int2");
                self.put32(2);
                self.put16(n);
                return;
            }
            case INT4: {
                i64 n = reflect.getInt(v);
                argRange(n, I32_MIN, I32_MAX, i, "int4");
                self.put32(4);
                self.put32(n);
                return;
            }
            case OID: {
                i64 n = reflect.getInt(v);
                argRange(n, 0, U32_MAX, i, "oid");
                self.put32(4);
                self.put32(n);
                return;
            }
            case INT8:
                self.put32(8);
                self.putBe64(reflect.getInt(v));
                return;
            case BOOL:
                self.put32(1);
                self.put8(reflect.getInt(v) ? 1 : 0);
                return;
            case FLOAT4: {
                f32 x = t.kind == reflect.Kind.float ? reflect.getFloat(v) : (f64)reflect.getInt(v);
                self.put32(4);
                self.put32(*(u32 *)&x);
                return;
            }
            case FLOAT8: {
                f64 x = t.kind == reflect.Kind.float ? reflect.getFloat(v) : (f64)reflect.getInt(v);
                self.put32(8);
                self.putBe64(*(i64 *)&x);
                return;
            }
            case BYTEA: case UUID: {
                u8[] b;
                argBytes(v, &b);
                self.putText(b);
                return;
            }
            case TIMESTAMP: case TIMESTAMPTZ: {
                i64 us = microsFromDate(*(date.Date *)v.ptr);
                self.put32(8);
                self.putBe64(us - PG_EPOCH_DAYS * DAY_US);
                return;
            }
            case DATE: {
                i64 us = microsFromDate(*(date.Date *)v.ptr);
                self.put32(4);
                self.put32(floorDiv(us, DAY_US) - PG_EPOCH_DAYS);
                return;
            }
        }
    }
    if (isDate(t)) {
        u8 buf[40];
        i64 n = formatTimestamp(microsFromDate(*(date.Date *)v.ptr), buf, true);
        self.putText(buf[0:n]);
        return;
    }
    u8[] b;
    if (argBytes(v, &b)) { self.putText(b); return; }
    switch (t.kind) {
        case reflect.Kind.int:
            self.putDecimal(reflect.getInt(v));
            return;
        case reflect.Kind.boolean:
            if (reflect.getInt(v)) { self.putText("t"); } else { self.putText("f"); }
            return;
        case reflect.Kind.float: {
            u8 buf[40];
            i64 n = formatFloat(reflect.getFloat(v), buf);
            self.putText(buf[0:n]);
            return;
        }
        case reflect.Kind.enumType:
            self.putText(reflect.nameOf(t, reflect.getInt(v)));
            return;
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

/* ---- formatting values as text ---- */

/* The shortest text that reads back as x (at most 25 bytes) */
static i64 formatFloat(f64 x, u8 *buf)
{
    if (x != x) { cstr.copy(buf, "NaN"); return 3; }
    if (x > 1.7976931348623157e308) { cstr.copy(buf, "Infinity"); return 8; }
    if (x < -1.7976931348623157e308) { cstr.copy(buf, "-Infinity"); return 9; }
    for (i64 prec = 15; prec <= 17; prec++) {
        i64 n = libc.snprintf(buf, 32, "%.*g", prec, x);
        f64 back;
        if (str.toFloat(buf[0:n], &back) && back == x) { return n; }
    }
    return libc.snprintf(buf, 32, "%.17g", x);
}

/* us since 1970 as the server writes a timestamp (tz: with +00) */
static i64 formatTimestamp(i64 us, u8 *buf, bool tz)
{
    if (us == I64_MAX) { cstr.copy(buf, "infinity"); return 8; }
    if (us == I64_MIN) { cstr.copy(buf, "-infinity"); return 9; }
    i64 days = floorDiv(us, DAY_US);
    i64 rem = us - days * DAY_US;
    i64 y, m, d;
    civilFromDays(days, &y, &m, &d);
    i64 n = libc.snprintf(buf, 40, "%04d-%02d-%02d %02d:%02d:%02d", y, m, d,
                          rem / 3600000000, rem / 60000000 % 60, rem / 1000000 % 60);
    i64 frac = rem % 1000000;
    if (frac) {
        n += libc.snprintf(buf + n, 40 - n, ".%06d", frac);
        while (buf[n - 1] == '0') { n--; }
    }
    if (tz) { buf[n++] = '+'; buf[n++] = '0'; buf[n++] = '0'; }
    buf[n] = 0;
    return n;
}

static i64 formatDate(i64 days, u8 *buf)
{
    if (days == I32_MAX) { cstr.copy(buf, "infinity"); return 8; }
    if (days == I32_MIN) { cstr.copy(buf, "-infinity"); return 9; }
    i64 y, m, d;
    civilFromDays(days, &y, &m, &d);
    return libc.snprintf(buf, 40, "%04d-%02d-%02d", y, m, d);
}

static i64 formatUuid(u8[] b, u8 *buf)
{
    u8[] digits = "0123456789abcdef";
    i64 n = 0;
    for (i64 i = 0; i < 16; i++) {
        if (i == 4 || i == 6 || i == 8 || i == 10) { buf[n++] = '-'; }
        buf[n++] = digits[b[i] >> 4];
        buf[n++] = digits[b[i] & 15];
    }
    return n;
}

/* ---- reading values ---- */

static i64 binInt(u8[] b)
{
    if (b.len == 8) { return (i64)bytesBe64(b); }
    if (b.len == 4) { return sx32(bytesBe32(b)); }
    if (b.len == 2) { return sx16((u16)b[0] << 8 | b[1]); }
    if (b.len == 1) { return b[0]; }
    return 0;
}

static u64 bytesBe64(u8[] b)
{
    return (u64)bytesBe32(b) << 32 | bytesBe32(b[4:8]);
}

static u32 bytesBe32(u8[] b)
{
    return (u32)b.ptr[0] << 24 | (u32)b.ptr[1] << 16 | (u32)b.ptr[2] << 8 | b.ptr[3];
}

/* Column i's value: its bytes, its type and format; false for NULL */
static bool Rows.value(i64 i, u8[] *b, Column **col)
{
    self.check(i);
    *col = &self.st.cols[i];
    i64 len = self.off[i * 2 + 1];
    if (len < 0) { *b = ""; return false; }
    *b = self.c.msg[self.off[i * 2]:self.off[i * 2] + len];
    return true;
}

/* A binary timestamp, timestamptz or date as us since 1970 */
static i64 binMicros(u32 oid, u8[] b)
{
    if (oid == DATE) {
        i64 d = sx32(bytesBe32(b));
        if (d == I32_MAX) { return I64_MAX; }
        if (d == I32_MIN) { return I64_MIN; }
        return (d + PG_EPOCH_DAYS) * DAY_US;
    }
    i64 us = (i64)bytesBe64(b);
    if (us == I64_MAX || us == I64_MIN) { return us; }
    return us + PG_EPOCH_DAYS * DAY_US;
}

/* Column i as an integer: 0 for NULL. A bool is 0 or 1, a timestamp or
 * date its microseconds since 1970 (UTC). Throws ERR_SCAN for a value
 * that isn't a whole number. */
pub i64 Rows.int(i64 i)
{
    u8[] b;
    Column *col;
    if (!self.value(i, &b, &col)) { return 0; }
    if (col.format) {
        switch (col.oid) {
            case INT2: case INT4: case INT8: case BOOL: return binInt(b);
            case OID: return bytesBe32(b);
            case TIMESTAMP: case TIMESTAMPTZ: case DATE: return binMicros(col.oid, b);
            case FLOAT4: case FLOAT8: {
                f64 x = self.float(i);
                if (x == (f64)(i64)x) { return (i64)x; }
            }
        }
        throw(ERR_SCAN, "pg: column %s: a %s isn't an integer", col.name, typeName(col.oid));
    }
    i64 n;
    if (str.toInt(b, &n)) { return n; }
    f64 x;
    if (str.toFloat(b, &x) && x == (f64)(i64)x) { return (i64)x; }   /* numeric 12.00 */
    throw(ERR_SCAN, "pg: column %s: \"%s\" isn't an integer", col.name, b);
    return 0;
}

/* Column i as a float: 0 for NULL */
pub f64 Rows.float(i64 i)
{
    u8[] b;
    Column *col;
    if (!self.value(i, &b, &col)) { return 0; }
    if (col.format) {
        if (col.oid == FLOAT8) { u64 u = bytesBe64(b); return *(f64 *)&u; }
        if (col.oid == FLOAT4) { u32 u = bytesBe32(b); return *(f32 *)&u; }
        return self.int(i);
    }
    f64 x;
    if (str.toFloat(b, &x)) { return x; }
    if (b.equals("NaN")) { return 0.0 / 0.0; }
    if (b.equals("Infinity")) { return 1.0 / 0.0; }
    if (b.equals("-Infinity")) { return -1.0 / 0.0; }
    throw(ERR_SCAN, "pg: column %s: \"%s\" isn't a number", col.name, b);
    return 0;
}

/* Column i as a bool: false for NULL */
pub bool Rows.bool(i64 i)
{
    u8[] b;
    Column *col;
    if (!self.value(i, &b, &col)) { return false; }
    if (col.format) {
        if (col.oid == BOOL) { return b[0] != 0; }
        return self.int(i) != 0;
    }
    if (b.equals("t") || b.equals("true")) { return true; }
    if (b.equals("f") || b.equals("false")) { return false; }
    throw(ERR_SCAN, "pg: column %s: \"%s\" isn't a bool", col.name, b);
    return false;
}

/* Column i as text, as the server would write it (timestamps in UTC), a
 * view valid until the next row: "" for NULL. A bytea gives its bytes. */
pub u8[] Rows.text(i64 i)
{
    u8[] b;
    Column *col;
    if (!self.value(i, &b, &col)) { return ""; }
    if (!col.format || col.oid == BYTEA) { return b; }
    if (!self.fmt) { self.fmt = mem.alloc(48 * self.cols); }
    u8 *buf = self.fmt + 48 * i;
    i64 n;
    switch (col.oid) {
        case BOOL:
            buf[0] = b[0] ? 't' : 'f';
            n = 1;
        case INT2: case INT4: case INT8: case OID:
            n = libc.snprintf(buf, 48, "%lld", self.int(i));
        case FLOAT4: case FLOAT8:
            n = formatFloat(self.float(i), buf);
        case TIMESTAMP:
            n = formatTimestamp(binMicros(col.oid, b), buf, false);
        case TIMESTAMPTZ:
            n = formatTimestamp(binMicros(col.oid, b), buf, true);
        case DATE:
            n = formatDate(binMicros(DATE, b) == I64_MAX ? I32_MAX : binMicros(DATE, b) == I64_MIN ? I32_MIN
                           : binMicros(DATE, b) / DAY_US, buf);
        case UUID:
            n = formatUuid(b, buf);
        default:
            return b;
    }
    return buf[0:n];
}

/* Column i's bytes: a bytea's data, a uuid's 16 bytes, else its text */
pub u8[] Rows.bytes(i64 i)
{
    u8[] b;
    Column *col;
    if (!self.value(i, &b, &col)) { return ""; }
    if (col.oid == BYTEA || col.oid == UUID || !col.format) { return b; }
    return self.text(i);
}

/* Column i (a timestamp, timestamptz or date) as microseconds since 1970
 * UTC: 0 for NULL, I64_MAX and I64_MIN for infinity and -infinity */
pub i64 Rows.micros(i64 i)
{
    u8[] b;
    Column *col;
    if (!self.value(i, &b, &col)) { return 0; }
    if (col.format && (col.oid == TIMESTAMP || col.oid == TIMESTAMPTZ || col.oid == DATE)) {
        return binMicros(col.oid, b);
    }
    throw(ERR_SCAN, "pg: column %s: a %s isn't a timestamp", col.name, typeName(col.oid));
    return 0;
}

/* Column i (a timestamp, timestamptz or date) as a date.Date (UTC) */
pub date.Date Rows.date(i64 i)
{
    if (self.isNull(i)) { date.Date z = 0; return z; }
    return dateFromMicros(self.micros(i));
}

static u8 *typeName(u32 oid)
{
    switch (oid) {
        case BOOL: return "bool";
        case BYTEA: return "bytea";
        case INT2: return "int2";
        case INT4: return "int4";
        case INT8: return "int8";
        case FLOAT4: return "float4";
        case FLOAT8: return "float8";
        case DATE: return "date";
        case TIMESTAMP: return "timestamp";
        case TIMESTAMPTZ: return "timestamptz";
        case UUID: return "uuid";
        case OID: return "oid";
        default: return "value";
    }
    return "value";
}
