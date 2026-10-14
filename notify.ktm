module pg;
import coro, date, libc, mem, str;

/* LISTEN and NOTIFY. Notifications come on the connection that listens,
 * between queries or while it waits for one with waitNotification. Use
 * a connection of its own for listening (from a pool: db.acquire() and
 * keep it), since the pool drops an idle connection that has unread
 * input. */
pub struct Notification
{
    i64 pid;            /* the server process that sent it */
    String channel;
    String payload;
};

pub void Notification.free()
{
    self.channel.free();
    self.payload.free();
}

/* A notification waiting to be read */
struct Note
{
    Notification n;
    Note *next;
};

/* A NotificationResponse just read: queue it */
static void Conn.queueNote()
{
    Note *e = mem.allocZero(sizeof(Note));
    e.n.pid = be32At(self.msg, 0);
    u8[] channel = cstrAt(self.msg, 4);
    e.n.channel = str.from(channel);
    e.n.payload = str.from(cstrAt(self.msg, 4 + channel.len + 1));
    if (self.noteTail) { self.noteTail.next = e; } else { self.noteHead = e; }
    self.noteTail = e;
}

static bool Conn.popNote(Notification *out)
{
    Note *e = self.noteHead;
    if (!e) { return false; }
    self.noteHead = e.next;
    if (!self.noteHead) { self.noteTail = null; }
    *out = e.n;
    mem.free(e);
    return true;
}

static void Conn.freeNotes()
{
    Notification n;
    while (self.popNote(&n)) { n.free(); }
}

/* name as a quoted identifier: "name", inner quotes doubled */
static String quoteIdent(u8[] name)
{
    String s = str.from("\"");
    for (i64 i = 0; i < name.len; i++) {
        if (name[i] == '"') { s.appendByte('"'); }
        s.appendByte(name[i]);
    }
    s.appendByte('"');
    return s;
}

/* Listen on a channel (any name: it is quoted) */
pub void Conn.listen(u8[] channel)
{
    ad String q = quoteIdent(channel);
    ad String sql = str.format("LISTEN %s", q);
    self.execScript(sql);
}

/* Stop listening on a channel, or on all with "*" */
pub void Conn.unlisten(u8[] channel)
{
    if (channel.equals("*")) {
        self.execScript("UNLISTEN *");
        return;
    }
    ad String q = quoteIdent(channel);
    ad String sql = str.format("UNLISTEN %s", q);
    self.execScript(sql);
}

/* Send a notification (pg_notify, so any channel name and payload) */
pub void Conn.notify(u8[] channel, u8[] payload = "")
{
    self.exec("SELECT pg_notify($1, $2)", channel, payload);
}

pub void Pool.notify(u8[] channel, u8[] payload = "")
{
    self.exec("SELECT pg_notify($1, $2)", channel, payload);
}

/* The next notification into *n (yours: n.free()), waiting up to ms
 * milliseconds for it (-1: no limit, 0: only what has come). false when
 * none came. In a loop coroutine the wait pauses only the coroutine. */
pub bool Conn.waitNotification(Notification *n, i64 ms = -1)
{
    if (self.popNote(n)) { return true; }
    Req r;
    self.join(&r);
    defer self.leave(&r);
    self.turn(&r);
    self.deadline = 0;              /* no query timeout while listening */
    self.s.timeoutMs = -1;
    i64 until = ms < 0 ? -1 : date.monoMs() + ms;
    while (true) {
        if (self.popNote(n)) { return true; }
        if (self.inEnd == self.inStart && !(self.s.impl && self.s.impl.pending() > 0)) {
            i64 left = until < 0 ? -1 : until - date.monoMs();
            if (until >= 0 && left <= 0) { return false; }
            if (self.s.loop) {
                if (coro.wait(self.s.fd, libc.POLLIN, left) <= 0) {
                    if (until >= 0 && date.monoMs() >= until) { return false; }
                    continue;
                }
            } else {
                libc.pollfd p;
                p.fd = self.s.fd;
                p.events = libc.POLLIN;
                p.revents = 0;
                if (libc.poll(&p, 1, left > 0x7FFFFFFF ? 0x7FFFFFFF : left) == 0) { return false; }
            }
        }
        u8 t = self.readRaw();
        if (t == 'A') { self.queueNote(); continue; }
        if (t == 'S' || t == 'N') { continue; }
        if (t == 'E') {
            ServerError e = serverError(self.msg);
            defer e.message.free();
            throw(e.code, "%s", e.message);
        }
        self.dead = true;
        self.s.close(&self.s);
        throw(ERR_PROTOCOL, "pg: unexpected message '%c' while waiting for notifications", t);
    }
    return false;
}
