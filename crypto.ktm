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

/* ---- SHA-512 and SHA-384 (FIPS 180-4), for channel binding to a
 * certificate signed with them ---- */

const u64 sha512K[80] = {
    0x428a2f98d728ae22, 0x7137449123ef65cd, 0xb5c0fbcfec4d3b2f, 0xe9b5dba58189dbbc, 0x3956c25bf348b538,
    0x59f111f1b605d019, 0x923f82a4af194f9b, 0xab1c5ed5da6d8118, 0xd807aa98a3030242, 0x12835b0145706fbe,
    0x243185be4ee4b28c, 0x550c7dc3d5ffb4e2, 0x72be5d74f27b896f, 0x80deb1fe3b1696b1, 0x9bdc06a725c71235,
    0xc19bf174cf692694, 0xe49b69c19ef14ad2, 0xefbe4786384f25e3, 0x0fc19dc68b8cd5b5, 0x240ca1cc77ac9c65,
    0x2de92c6f592b0275, 0x4a7484aa6ea6e483, 0x5cb0a9dcbd41fbd4, 0x76f988da831153b5, 0x983e5152ee66dfab,
    0xa831c66d2db43210, 0xb00327c898fb213f, 0xbf597fc7beef0ee4, 0xc6e00bf33da88fc2, 0xd5a79147930aa725,
    0x06ca6351e003826f, 0x142929670a0e6e70, 0x27b70a8546d22ffc, 0x2e1b21385c26c926, 0x4d2c6dfc5ac42aed,
    0x53380d139d95b3df, 0x650a73548baf63de, 0x766a0abb3c77b2a8, 0x81c2c92e47edaee6, 0x92722c851482353b,
    0xa2bfe8a14cf10364, 0xa81a664bbc423001, 0xc24b8b70d0f89791, 0xc76c51a30654be30, 0xd192e819d6ef5218,
    0xd69906245565a910, 0xf40e35855771202a, 0x106aa07032bbd1b8, 0x19a4c116b8d2d0c8, 0x1e376c085141ab53,
    0x2748774cdf8eeb99, 0x34b0bcb5e19b48a8, 0x391c0cb3c5c95a63, 0x4ed8aa4ae3418acb, 0x5b9cca4f7763e373,
    0x682e6ff3d6b2b8a3, 0x748f82ee5defb2fc, 0x78a5636f43172f60, 0x84c87814a1f0ab72, 0x8cc702081a6439ec,
    0x90befffa23631e28, 0xa4506cebde82bde9, 0xbef9a3f7b2c67915, 0xc67178f2e372532b, 0xca273eceea26619c,
    0xd186b8c721c0c207, 0xeada7dd6cde0eb1e, 0xf57d4f7fee6ed178, 0x06f067aa72176fba, 0x0a637dc5a2c898a6,
    0x113f9804bef90dae, 0x1b710b35131c471b, 0x28db77f523047d84, 0x32caab7b40c72493, 0x3c9ebe0a15c9bebc,
    0x431d67c49c100d4c, 0x4cc5d4becb3e42b6, 0x597f299cfc657e2a, 0x5fcb6fab3ad6faec, 0x6c44198c4a475817,
};

static u64 rotr64(u64 x, i64 n)
{
    return (x >> n) | (x << (64 - n));
}

static void sha512Block(u64 *h, u8 *p)
{
    u64 w[80];
    for (i64 i = 0; i < 16; i++) {
        u64 v = 0;
        for (i64 j = 0; j < 8; j++) { v = (v << 8) | p[i * 8 + j]; }
        w[i] = v;
    }
    for (i64 i = 16; i < 80; i++) {
        u64 s0 = rotr64(w[i - 15], 1) ^ rotr64(w[i - 15], 8) ^ (w[i - 15] >> 7);
        u64 s1 = rotr64(w[i - 2], 19) ^ rotr64(w[i - 2], 61) ^ (w[i - 2] >> 6);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    u64 a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
    for (i64 i = 0; i < 80; i++) {
        u64 S1 = rotr64(e, 14) ^ rotr64(e, 18) ^ rotr64(e, 41);
        u64 ch = (e & f) ^ (~e & g);
        u64 t1 = hh + S1 + ch + sha512K[i] + w[i];
        u64 S0 = rotr64(a, 28) ^ rotr64(a, 34) ^ rotr64(a, 39);
        u64 mj = (a & b) ^ (a & c) ^ (b & c);
        u64 t2 = S0 + mj;
        hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
}

/* SHA-512 (or SHA-384 when is384) of data into out (64 or 48 bytes) */
static void sha512(u8[] data, u8 *out, bool is384)
{
    u64 h[8];
    if (is384) {
        h[0] = 0xcbbb9d5dc1059ed8; h[1] = 0x629a292a367cd507; h[2] = 0x9159015a3070dd17; h[3] = 0x152fecd8f70e5939;
        h[4] = 0x67332667ffc00b31; h[5] = 0x8eb44a8768581511; h[6] = 0xdb0c2e0d64f98fa7; h[7] = 0x47b5481dbefa4fa4;
    } else {
        h[0] = 0x6a09e667f3bcc908; h[1] = 0xbb67ae8584caa73b; h[2] = 0x3c6ef372fe94f82b; h[3] = 0xa54ff53a5f1d36f1;
        h[4] = 0x510e527fade682d1; h[5] = 0x9b05688c2b3e6c1f; h[6] = 0x1f83d9abfb41bd6b; h[7] = 0x5be0cd19137e2179;
    }
    i64 i = 0;
    while (i + 128 <= data.len) {
        sha512Block(h, data.ptr + i);
        i += 128;
    }
    u8 block[256];
    i64 rest = data.len - i;
    mem.set(block, 0, 256);
    mem.copy(block, data.ptr + i, rest);
    block[rest] = 0x80;
    i64 total = rest + 17 <= 128 ? 128 : 256;
    u64 bits = (u64)data.len << 3;
    for (i64 j = 0; j < 8; j++) { block[total - 1 - j] = (bits >> (j * 8)) & 0xFF; }
    sha512Block(h, block);
    if (total == 256) { sha512Block(h, block + 128); }
    i64 words = is384 ? 6 : 8;
    for (i64 j = 0; j < words; j++) {
        for (i64 k = 0; k < 8; k++) { out[j * 8 + k] = (h[j] >> (56 - k * 8)) & 0xFF; }
    }
}

/* The tls-server-end-point hash of a certificate (RFC 5929): the hash
 * of its signature, or SHA-256 for MD5 and SHA-1. md: mbedTLS's id
 * (10 SHA-384, 11 SHA-512). Returns its length. */
static i64 certHash(u8[] der, i32 md, u8 *out)
{
    if (md == 10) { sha512(der, out, true); return 48; }
    if (md == 11) { sha512(der, out, false); return 64; }
    hash.sha256(der, out[0:32]);
    return 32;
}
