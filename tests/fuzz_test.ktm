import "github.com/ozzenerol/ktm-pg";
import atomic, date, libc, net, str, t, thread;

/* Fuzzing the protocol reader: a fake server replays a valid transcript
 * (start-up, a prepared query, two executions) with random damage: bit
 * flips, truncation, broken lengths, inserted bytes, unknown messages.
 * Every round must end in a clean error or a success: no crash, no hang,
 * nothing leaked (-Memsafe). */

const i32 PORT = 58432;
String gBlob;                  /* what the fake server sends next */
i64 gStop = 0;
i64 gServed = 0;

/* ---- the transcript ---- */

void put32(String *s, i64 v)
{
    s.appendByte((v >> 24) & 0xFF);
    s.appendByte((v >> 16) & 0xFF);
    s.appendByte((v >> 8) & 0xFF);
    s.appendByte(v & 0xFF);
}

void put16(String *s, i64 v)
{
    s.appendByte((v >> 8) & 0xFF);
    s.appendByte(v & 0xFF);
}

/* A message: its type, its length, body */
void msg(String *out, u8 type, u8[] body)
{
    out.appendByte(type);
    put32(out, body.len + 4);
    out.append(body);
}

void cstrz(String *s, u8[] v)
{
    s.append(v);
    s.appendByte(0);
}

void column(String *s, u8[] name, i64 oid)
{
    cstrz(s, name);
    put32(s, 0);        /* table */
    put16(s, 0);        /* attnum */
    put32(s, oid);
    put16(s, -1);       /* typlen */
    put32(s, -1);       /* typmod */
    put16(s, 0);        /* format */
}

void dataRow(String *out, i64 id, u8[] text, bool nulls)
{
    scoped String b = str.new();
    put16(&b, 2);
    if (nulls) {
        put32(&b, -1);
        put32(&b, -1);
    } else {
        put32(&b, 8);
        for (i64 i = 7; i >= 0; i--) { b.appendByte((id >> (i * 8)) & 0xFF); }
        put32(&b, text.len);
        b.append(text);
    }
    msg(out, 'D', b);
}

/* kind 0: trust; 1: md5 first; 2: SCRAM (whose nonce can't match:
 * the client must refuse it cleanly) */
String transcript(i64 kind = 0)
{
    String out = str.new();
    scoped String b = str.new();
    if (kind == 1) {
        put32(&b, 5);
        b.append("salt");
        msg(&out, 'R', b);
        b.clear();
    } else if (kind == 2) {
        put32(&b, 10);
        cstrz(&b, "SCRAM-SHA-256-PLUS");
        cstrz(&b, "SCRAM-SHA-256");
        b.appendByte(0);
        msg(&out, 'R', b);
        b.clear();
        put32(&b, 11);
        b.append("r=abcdefghijklmnopqrstuvwxyz,s=c2FsdHNhbHQ=,i=4096");
        msg(&out, 'R', b);
        b.clear();
        put32(&b, 12);
        b.append("v=c2lnbmF0dXJl");
        msg(&out, 'R', b);
        b.clear();
    }
    put32(&b, 0);
    msg(&out, 'R', b);                              /* AuthenticationOk */
    b.clear(); cstrz(&b, "server_version"); cstrz(&b, "17.0");
    msg(&out, 'S', b);
    b.clear(); cstrz(&b, "client_encoding"); cstrz(&b, "UTF8");
    msg(&out, 'S', b);
    b.clear(); put32(&b, 1234); put32(&b, 5678);
    msg(&out, 'K', b);
    msg(&out, 'Z', "I");
    /* Parse + Describe + Sync */
    msg(&out, '1', "");
    b.clear(); put16(&b, 1); put32(&b, 20);
    msg(&out, 't', b);
    b.clear(); put16(&b, 2); column(&b, "id", 20); column(&b, "name", 25);
    msg(&out, 'T', b);
    msg(&out, 'Z', "I");
    /* two executions: Bind + Execute + Sync */
    for (i64 k = 0; k < 2; k++) {
        msg(&out, '2', "");
        dataRow(&out, 42, "Ada", false);
        dataRow(&out, 0, "", true);
        b.clear(); cstrz(&b, "SELECT 2");
        msg(&out, 'C', b);
        b.clear(); cstrz(&b, "NOTICE");
        msg(&out, 'N', "SNOTICE\0Mhello\0\0");
        msg(&out, 'Z', "I");
    }
    return out;
}

/* ---- damage ---- */

u64 gRand = 0x9E3779B97F4A7C15;

u64 rnd()
{
    gRand ^= gRand << 13;
    gRand ^= gRand >> 7;
    gRand ^= gRand << 17;
    return gRand;
}

i64 rndIn(i64 n)
{
    if (n <= 0) { return 0; }
    return (i64)(rnd() % (u64)n);
}

/* The start of each message of a transcript */
i64 starts(u8[] s, i64 *at, i64 max)
{
    i64 n = 0, i = 0;
    while (i + 5 <= s.len && n < max) {
        at[n++] = i;
        i64 len = (i64)s[i + 1] << 24 | (i64)s[i + 2] << 16 | (i64)s[i + 3] << 8 | s[i + 4];
        i += 1 + len;
    }
    return n;
}

void damage(String *s, u8[] good)
{
    s.clear();
    s.append(good);
    i64 at[64];
    i64 n = starts(good, at, 64);
    i64 kinds = 1 + rndIn(3);
    for (i64 k = 0; k < kinds && s.len; k++) {
        i64 m = at[rndIn(n)];
        switch (rndIn(8)) {
            case 0: { i64 i = rndIn(s.len); s.ptr[i] ^= (u8)(1 << rndIn(8)); }       /* a bit */
            case 1: { s.ptr[rndIn(s.len)] = (u8)rnd(); }                             /* a byte */
            case 2: { i64 cut = rndIn(s.len); s.remove(cut, s.len); }               /* truncated */
            case 3: { u8 b[1]; b[0] = (u8)rnd(); s.insert(rndIn(s.len), b[0:1]); }  /* one more byte */
            case 4: {                                                                /* a message's length */
                if (m + 5 > s.len) { break; }
                i64 lens[7] = {0, 3, 4, 5, 0x7FFFFFFF, -1, 0x40000001};
                i64 v = rndIn(2) ? lens[rndIn(7)] : rndIn(64);
                s.ptr[m + 1] = (v >> 24) & 0xFF; s.ptr[m + 2] = (v >> 16) & 0xFF;
                s.ptr[m + 3] = (v >> 8) & 0xFF; s.ptr[m + 4] = v & 0xFF;
            }
            case 5: { if (m < s.len) { s.ptr[m] = 'A' + rndIn(58); } }               /* the type */
            case 6: {                                                                /* a field inside */
                if (m + 6 < s.len) {
                    i64 i = m + 5 + rndIn(8);
                    if (i < s.len) { s.ptr[i] = rndIn(2) ? 0xFF : 0; }
                }
            }
            default: {                                                               /* a message twice */
                if (m + 5 > s.len) { break; }
                u8 *p = s.ptr;
                i64 len = (i64)p[m + 1] << 24 | (i64)p[m + 2] << 16 | (i64)p[m + 3] << 8 | p[m + 4];
                if (len < 4 || m + 1 + len > s.len) { break; }
                scoped String dup = str.from(p[m:m + 1 + len]);
                s.insert(m, dup);
            }
        }
    }
}

/* ---- the fake server, on a thread of its own ---- */

void server(void *priv, void *arg)
{
    i32 lfd = net.listen(PORT, "127.0.0.1", 64);
    if (lfd < 0) { return; }
    while (!atomic.load(&gStop)) {
        i32 fd = net.accept(lfd);
        if (fd < 0) { continue; }
        if (atomic.load(&gStop)) { libc.close(fd); break; }
        u8[] out = gBlob;
        i64 done = 0;
        while (done < out.len) {
            i64 w = libc.write(fd, out.ptr + done, out.len - done);
            if (w <= 0) { break; }
            done += w;
        }
        libc.shutdown(fd, libc.SHUT_WR);   /* all of it: then the end of the stream */
        net.setRecvTimeout(fd, 3000);
        u8 buf[4096];
        while (libc.read(fd, buf, 4096) > 0) { }
        libc.close(fd);
        atomic.add(&gServed, 1);
    }
    libc.close(lfd);
}

/* ---- the client ---- */

i64 gOk = 0;
i64 gErrors = 0;
i64 gStrange = 0;
i64 gOther = 0;

void round(u8[] url, bool check)
{
    pg.Conn *c = null;
    try {
        c = pg.connect(url);
        for (i64 k = 0; k < 2; k++) {
            scoped pg.Rows r = c.query("SELECT $1::int8, 'x'::text", 1);
            i64 n = 0;
            while (r.next()) {
                n++;
                i64 id = r.int(0);
                u8[] name = r.text(1);
                if (check && n == 1 && (id != 42 || !name.equals("Ada"))) { gStrange++; }
                r.isNull(1);
            }
            if (check && n != 2) { gStrange++; }
        }
        gOk++;
    } catch (Error e) {
        gErrors++;
        bool ours = pg.isServerError(e.code) || (e.code >= pg.ERR_CONNECT && e.code <= pg.ERR_TLS);
        if (!ours) {
            if (gOther < 5) { printf("\n  not a pg error: %x %s", e.code, e.message); }
            gOther++;
        }
        if (check) { printf("\n  the good transcript failed: %s", e.message); gStrange++; }
    }
    if (c) { c.close(); }
}

i32 main()
{
    t.start("fuzz");
    scoped String good = transcript();
    scoped String md5 = transcript(1);
    scoped String scram = transcript(2);
    gBlob = str.from(good);
    thread.Pool *tp = thread.poolNew(1);
    thread.poolEnqueue(tp, null, &server);
    date.sleepMs(100);
    scoped String url = str.format("postgres://fuzz:pw@127.0.0.1:%d/db?sslmode=disable&connect_timeout=5", PORT);
    round(url, true);
    t.ok(gOk == 1 && gStrange == 0, "the transcript as it is works");
    i64 rounds = 20000;
    i64 start = date.monoMs();
    scoped String bad = str.new();
    for (i64 i = 0; i < rounds; i++) {
        if (i % 3 == 0) { damage(&bad, good); }
        else if (i % 3 == 1) { damage(&bad, md5); }
        else { damage(&bad, scram); }
        gBlob.free();
        gBlob = str.from(bad);
        round(url, false);
    }
    i64 took = date.monoMs() - start;
    printf("\n  %d rounds in %d ms: %d ran, %d failed cleanly", rounds, took, gOk - 1, gErrors);
    t.ok(gOk + gErrors == rounds + 1, "every damaged round ended, with a result or an error");
    t.ok(gErrors > rounds / 2, "most damage is noticed");
    t.ok(gOther == 0, "every error is one of pg's");
    atomic.store(&gStop, 1);
    i32 fd = net.dial("127.0.0.1", PORT);
    if (fd >= 0) { libc.close(fd); }
    thread.poolWait(tp);
    thread.poolStop(tp);
    thread.poolRelease(tp);
    gBlob.free();
    return t.done();
}
