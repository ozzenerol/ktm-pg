import "github.com/ozzenerol/ktm-pg";
import atomic, date, libc, net, net.http, str, t, thread;

/* A pool in net.http handlers: 4 server threads, 8 client threads, 200
 * requests, each one a query; the waits pause only the handler's
 * coroutine */

const i32 PORT = 58431;
i64 gOk = 0;
i64 gServed = 0;
i64 gNoDial = 0;
i64 gBad = 0;

void double(http.Writer *w, http.Request *r)
{
    pg.Pool *db = r.app;
    i64 n;
    str.toInt(r.param("n"), &n);
    i64 got = pg.queryOne<i64>(db, "SELECT $1::int8 * 2 FROM pg_sleep(0.005)", n);
    atomic.add(&gServed, 1);
    w.text(200, "%d", got);
}

/* One request on its own connection: the body, as an integer */
i64 get(i64 n)
{
    i32 fd = -1;
    for (i64 i = 0; i < 200 && fd < 0; i++) {
        fd = net.dial("127.0.0.1", PORT);
        if (fd < 0) { date.sleepMs(10); }
    }
    if (fd < 0) { atomic.add(&gNoDial, 1); return -1; }
    ad String req = str.format("GET /double/%d HTTP/1.0\r\n\r\n", n);
    net.send(fd, req);
    ad String resp = str.new();
    u8 buf[4096];
    while (true) {
        i64 got = libc.read(fd, buf, 4096);
        if (got <= 0) { break; }
        resp.append(buf[0:got]);
    }
    libc.close(fd);
    i64 at = str.find(resp, "\r\n\r\n");
    i64 v = -1;
    if (at >= 0 && str.startsWith(resp, "HTTP/1.1 200")) { str.toInt(resp[at + 4:], &v); }
    if (v != 2 * n && atomic.add(&gBad, 1) == 0) { printf("\n  first bad answer for %d: [%s]", n, resp); }
    return v;
}

void client(void *priv, void *arg)
{
    i64 base = (i64)arg;
    i64 good = 0;
    for (i64 i = 0; i < 25; i++) {
        if (get(base + i) == 2 * (base + i)) { good++; }
    }
    atomic.add(&gOk, good);
}

void clients(void *priv, void *arg)
{
    thread.Pool *tp = thread.poolNew(8);
    for (i64 i = 0; i < 8; i++) { thread.poolEnqueue(tp, (void *)(i * 100), &client); }
    thread.poolWait(tp);
    thread.poolStop(tp);
    thread.poolRelease(tp);
    http.shutdown();
}

i32 main()
{
    t.start("http");
    ad String u = t.url();
    pg.Options o;
    o.maxConns = 6;
    pg.Pool *db = pg.open(u, &o);
    http.Mux mux;
    mux.handle("GET /double/{n}", &double);
    thread.Pool *tp = thread.poolNew(1);
    thread.poolEnqueue(tp, null, &clients);
    http.Options ho;
    ho.threads = 4;
    ho.noSignals = true;
    i64 start = date.monoMs();
    i32 r = http.listen(PORT, &mux, db, &ho);
    i64 took = date.monoMs() - start;
    thread.poolWait(tp);
    thread.poolStop(tp);
    thread.poolRelease(tp);
    t.ok(r == 0, "the server ran");
    t.ok(gNoDial == 0, "every request connected");
    t.ok(atomic.load(&gOk) == 200 && atomic.load(&gServed) == 200, "200 answers, all right");
    t.ok(db.openCount() <= 6, "within maxConns");
    t.ok(took < 10000, "in time");
    db.close();
    return t.done();
}
