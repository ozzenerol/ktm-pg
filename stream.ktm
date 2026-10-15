module pg;
import coro, libc, net, net.tls, os, str;

/* Every byte to and from the server goes through a Stream: plain TCP (or
 * a Unix socket) now, TLS later as a second implementation of the same
 * three functions. In a loop coroutine (coro.go, coro.serve, net.http
 * handlers) the socket is non-blocking and a wait pauses only the
 * coroutine; elsewhere it is an ordinary blocking socket. */
struct Stream
{
    i32 fd;
    bool loop;          /* coro.read / coro.write, else blocking calls */
    i64 timeoutMs;      /* per read or write, -1: none */
    tls.Conn *impl;     /* the TLS session, null for plain TCP */
    fn(Stream *, u8[]) i64 read;    /* some bytes into buf: > 0, 0 at the end, -1 on an error */
    fn(Stream *, u8[]) bool write;  /* all of data, false on an error */
    fn(Stream *) void close;
};

static i64 tcpRead(Stream *s, u8[] buf)
{
    if (s.loop) { return coro.read(s.fd, buf, s.timeoutMs); }
    if (s.timeoutMs >= 0) {
        libc.pollfd p;
        p.fd = s.fd;
        p.events = libc.POLLIN;
        p.revents = 0;
        while (true) {
            i32 r = libc.poll(&p, 1, s.timeoutMs > 0x7FFFFFFF ? 0x7FFFFFFF : s.timeoutMs);
            if (r > 0) { break; }
            if (r == 0) { os.setErrno(libc.ETIMEDOUT); return -1; }
            if (os.errno() != libc.EINTR) { return -1; }
        }
    }
    while (true) {
        i64 n = libc.read(s.fd, buf.ptr, buf.len);
        if (n >= 0 || os.errno() != libc.EINTR) { return n; }
    }
    return -1;
}

static bool tcpWrite(Stream *s, u8[] data)
{
    if (s.loop) { return coro.write(s.fd, data, s.timeoutMs) == data.len; }
    i64 done = 0;
    while (done < data.len) {
        i64 n = libc.write(s.fd, data.ptr + done, data.len - done);
        if (n < 0) {
            if (os.errno() == libc.EINTR) { continue; }
            return false;
        }
        done += n;
    }
    return true;
}

static void tcpClose(Stream *s)
{
    if (s.fd < 0) { return; }
    if (s.loop) { coro.close(s.fd); } else { libc.close(s.fd); }
    s.fd = -1;
}

/* The stream is used in a loop coroutine now (or no longer): switch
 * between non-blocking and blocking I/O. A connection made in main()
 * and used in coroutines then pauses only its coroutine. */
static void Stream.setLoop(bool loop)
{
    if (loop == self.loop || self.fd < 0) { return; }
    if (loop) { net.setNonBlocking(self.fd); } else { net.setBlocking(self.fd); }
    self.loop = loop;
}

static i64 tlsRead(Stream *s, u8[] buf)
{
    i64 n = s.impl.read(buf, s.timeoutMs);
    if (n < 0) { os.setErrno(str.contains(s.impl.error(), "timed out") ? libc.ETIMEDOUT : libc.EIO); }
    return n;
}

static bool tlsWrite(Stream *s, u8[] data)
{
    if (s.impl.write(data, s.timeoutMs) == data.len) { return true; }
    os.setErrno(str.contains(s.impl.error(), "timed out") ? libc.ETIMEDOUT : libc.EIO);
    return false;
}

static void tlsClose(Stream *s)
{
    if (!s.impl) { return; }
    s.impl.close();             /* closes the fd too */
    s.impl = null;
    s.fd = -1;
}

/* Ask the server for TLS (SSLRequest) and do the handshake. false when
 * the server says it has none ('N'): the stream stays plain. Throws
 * ERR_TLS when the handshake fails. */
static bool Stream.startTls(tls.Context *ctx, u8[] host)
{
    u8 req[8] = {0, 0, 0, 8, 0x04, 0xD2, 0x16, 0x2F};   /* 80877103 */
    if (!self.write(self, req[0:8])) { return false; }
    u8 b[1];
    /* exactly one byte: anything after it would be unencrypted data
     * slipped in before the handshake (CVE-2021-23222) */
    i64 n = self.read(self, b[0:1]);
    if (n < 0 && os.errno() == libc.ETIMEDOUT) {
        throw(ERR_CONNECT, "pg: no answer to the TLS request within %d ms", self.timeoutMs);
    }
    if (n != 1) {
        throw(ERR_TLS, "pg: the server closed the connection at the TLS request");
    }
    if (b[0] != 'S') { return false; }
    tls.Conn *t;
    try {
        t = ctx.connect(self.fd, host);
    } catch (Error e) {
        throw(ERR_TLS, "pg: %s", e.message);
    }
    self.impl = t;
    self.read = &tlsRead;
    self.write = &tlsWrite;
    self.close = &tlsClose;
    return true;
}

/* A plain stream to host:port, or to the Unix socket in directory host
 * (a host starting with /); false with os.errno() set when it can't
 * connect within timeoutMs (0: no limit) */
static bool streamDial(Stream *s, u8[] host, i32 port, i64 timeoutMs)
{
    s.loop = coro.inLoop();
    s.timeoutMs = -1;
    s.impl = null;
    s.read = &tcpRead;
    s.write = &tcpWrite;
    s.close = &tcpClose;
    if (host.len && host[0] == '/') {
        scoped String path = str.format("%s/.s.PGSQL.%d", host, port);
        s.fd = net.unixDial(path);
        if (s.fd < 0) { return false; }
        if (s.loop) { net.setNonBlocking(s.fd); }
        return true;
    }
    if (s.loop) {
        s.fd = coro.dial(host, port, timeoutMs > 0 ? timeoutMs : -1);
    } else {
        s.fd = net.dial(host, port, false, timeoutMs);
    }
    if (s.fd < 0) { return false; }
    net.setNoDelay(s.fd);
    return true;
}
