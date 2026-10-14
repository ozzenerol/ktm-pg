module pg;
import hash, libc, mem;

/* What the auth methods need and the standard library doesn't have yet:
 * MD5 (md5 passwords), HMAC-SHA-256 and PBKDF2 (SCRAM-SHA-256), base64
 * and random bytes (the SCRAM nonce). */

/* ---- MD5 (RFC 1321) ---- */

const u32 md5K[64] = {
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
};

const u8 md5S[64] = {
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
};

static void md5Block(u32 *h, u8 *p)
{
    u32 m[16];
    for (i64 i = 0; i < 16; i++) {
        m[i] = p[i * 4] | p[i * 4 + 1] << 8 | p[i * 4 + 2] << 16 | p[i * 4 + 3] << 24;
    }
    u32 a = h[0], b = h[1], c = h[2], d = h[3];
    for (i64 i = 0; i < 64; i++) {
        u32 f;
        i64 g;
        if (i < 16)      { f = (b & c) | (~b & d); g = i; }
        else if (i < 32) { f = (d & b) | (~d & c); g = (5 * i + 1) & 15; }
        else if (i < 48) { f = b ^ c ^ d;          g = (3 * i + 5) & 15; }
        else             { f = c ^ (b | ~d);       g = (7 * i) & 15; }
        u32 t = d;
        d = c;
        c = b;
        u32 x = a + f + md5K[i] + m[g];
        b = b + ((x << md5S[i]) | (x >> (32 - md5S[i])));
        a = t;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d;
}

/* The MD5 of the parts one after the other, as 32 lowercase hex digits
 * into out[0:32] */
static void md5Hex(u8[][] parts, u8 *out)
{
    u32 h[4] = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476};
    u8 block[64];
    i64 fill = 0;
    u64 total = 0;
    for (auto part : parts) {
        for (i64 i = 0; i < part.len; i++) {
            block[fill++] = part.ptr[i];
            if (fill == 64) { md5Block(h, block); fill = 0; }
        }
        total += part.len;
    }
    block[fill++] = 0x80;
    if (fill > 56) {
        while (fill < 64) { block[fill++] = 0; }
        md5Block(h, block);
        fill = 0;
    }
    while (fill < 56) { block[fill++] = 0; }
    u64 bits = total << 3;
    for (i64 i = 0; i < 8; i++) { block[56 + i] = (bits >> (i * 8)) & 0xFF; }
    md5Block(h, block);
    u8[] digits = "0123456789abcdef";
    for (i64 i = 0; i < 16; i++) {
        u8 byte = (h[i / 4] >> ((i % 4) * 8)) & 0xFF;
        out[i * 2] = digits[byte >> 4];
        out[i * 2 + 1] = digits[byte & 15];
    }
}

/* The md5 password message: "md5" + md5(md5(password + user) + salt) */
static void md5Password(u8[] user, u8[] password, u8[] salt, u8 *out)
{
    u8 inner[32];
    u8[][] a = {password, user};
    md5Hex(a, inner);
    u8[][] b = {inner[0:32], salt};
    out[0] = 'm'; out[1] = 'd'; out[2] = '5';
    md5Hex(b, out + 3);
}

/* ---- HMAC-SHA-256 and PBKDF2 ---- */

/* An HMAC-SHA-256 key, ready to sign any number of messages: the inner
 * and outer hashes with their padded keys already fed in */
struct Hmac
{
    hash.Sha256 inner;
    hash.Sha256 outer;
};

static Hmac hmacNew(u8[] key)
{
    u8 k[64];
    mem.set(k, 0, 64);
    if (key.len > 64) {
        hash.sha256(key, k[0:32]);
    } else {
        mem.copy(k, key.ptr, key.len);
    }
    u8 pad[64];
    Hmac h;
    h.inner = hash.sha256New();
    h.outer = hash.sha256New();
    for (i64 i = 0; i < 64; i++) { pad[i] = k[i] ^ 0x36; }
    h.inner.update(pad[0:64]);
    for (i64 i = 0; i < 64; i++) { pad[i] = k[i] ^ 0x5c; }
    h.outer.update(pad[0:64]);
    return h;
}

/* HMAC(key, msg) into out[0:32] */
static void Hmac.sign(u8[] msg, u8[] out)
{
    hash.Sha256 in = self.inner;
    hash.Sha256 o = self.outer;
    u8 d[32];
    in.update(msg);
    in.final(d[0:32]);
    o.update(d[0:32]);
    o.final(out);
}

static void hmacSha256(u8[] key, u8[] msg, u8[] out)
{
    Hmac h = hmacNew(key);
    h.sign(msg, out);
}

/* PBKDF2-HMAC-SHA-256 with one 32-byte block (all SCRAM needs) */
static void pbkdf2Sha256(u8[] password, u8[] salt, i64 iterations, u8[] out)
{
    Hmac h = hmacNew(password);
    u8 first[128];
    if (salt.len > 124) { throw(ERR_PROTOCOL, "pg: SCRAM salt of %d bytes", salt.len); }
    mem.copy(first, salt.ptr, salt.len);
    first[salt.len] = 0; first[salt.len + 1] = 0; first[salt.len + 2] = 0; first[salt.len + 3] = 1;
    u8 u[32];
    h.sign(first[0:salt.len + 4], u[0:32]);
    mem.copy(out.ptr, u, 32);
    for (i64 i = 1; i < iterations; i++) {
        h.sign(u[0:32], u[0:32]);
        for (i64 j = 0; j < 32; j++) { out[j] ^= u[j]; }
    }
}

/* ---- base64 ---- */

const u8[] b64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static void base64Append(String *out, u8[] data)
{
    i64 i = 0;
    while (i + 3 <= data.len) {
        u32 v = data[i] << 16 | data[i + 1] << 8 | data[i + 2];
        out.appendByte(b64Chars[(v >> 18) & 63]);
        out.appendByte(b64Chars[(v >> 12) & 63]);
        out.appendByte(b64Chars[(v >> 6) & 63]);
        out.appendByte(b64Chars[v & 63]);
        i += 3;
    }
    i64 rest = data.len - i;
    if (rest) {
        u32 v = data[i] << 16;
        if (rest == 2) { v |= data[i + 1] << 8; }
        out.appendByte(b64Chars[(v >> 18) & 63]);
        out.appendByte(b64Chars[(v >> 12) & 63]);
        out.appendByte(rest == 2 ? b64Chars[(v >> 6) & 63] : '=');
        out.appendByte('=');
    }
}

static i64 b64Value(u8 c)
{
    if (c >= 'A' && c <= 'Z') { return c - 'A'; }
    if (c >= 'a' && c <= 'z') { return c - 'a' + 26; }
    if (c >= '0' && c <= '9') { return c - '0' + 52; }
    if (c == '+') { return 62; }
    if (c == '/') { return 63; }
    return -1;
}

/* Decode base64 text into out; false on a bad character */
static bool base64Decode(u8[] text, String *out)
{
    u32 acc = 0;
    i64 bits = 0;
    for (i64 i = 0; i < text.len; i++) {
        if (text[i] == '=') { break; }
        i64 v = b64Value(text[i]);
        if (v < 0) { return false; }
        acc = (acc << 6) | v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.appendByte((acc >> bits) & 0xFF);
        }
    }
    return true;
}

/* ---- random bytes ---- */

static void randomBytes(u8[] out)
{
    i32 fd = libc.open("/dev/urandom", 0);
    i64 got = 0;
    if (fd >= 0) {
        while (got < out.len) {
            i64 n = libc.read(fd, out.ptr + got, out.len - got);
            if (n <= 0) { break; }
            got += n;
        }
        libc.close(fd);
    }
    if (got < out.len) { throw(ERR_CONNECT, "pg: can't read /dev/urandom for the SCRAM nonce"); }
}
