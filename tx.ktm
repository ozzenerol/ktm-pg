module pg;
import reflect, str;

/* A transaction. Make one with c.begin() (or db.begin() on a pool), run
 * statements on it, and end it with commit() or rollback(). rollback()
 * after commit() does nothing, so `defer tx.rollback();` right after
 * begin() covers every way out. */
pub struct Tx : Db
{
    private Conn *c;
    private bool done;
    private bool own;           /* the Tx gives its connection back to the pool when it ends */
};

/* Runs a statement with the simple protocol; true when its
 * CommandComplete tag is want */
static bool Conn.simpleTag(u8[] sql, u8[] want)
{
    Req r;
    self.join(&r);
    defer self.leave(&r);
    self.begin('Q');
    self.putStr(sql);
    self.end();
    self.flush();
    self.turn(&r);
    bool same = false;
    while (true) {
        u8 t = self.recv();
        if (t == 'C') { same = cstrAt(self.msg, 0).equals(want); }
        else if (t == 'E') { self.throwServerError(); }
        else if (t == 'Z') { self.txStatus = self.msg.len ? self.msg[0] : 'I'; break; }
    }
    return same;
}

/* Starts a transaction. mode goes after BEGIN: "ISOLATION LEVEL
 * SERIALIZABLE", "READ ONLY" ... */
pub Tx Conn.begin(u8[] mode = "")
{
    if (mode.len) {
        ad String sql = str.format("BEGIN %s", mode);
        self.simpleTag(sql, "BEGIN");
    } else {
        self.simpleTag("BEGIN", "BEGIN");
    }
    Tx tx;
    tx.open = &txOpen;
    tx.c = self;
    tx.done = false;
    tx.own = false;
    return tx;
}

static Conn *Tx.conn_()
{
    if (self.done) { throw(ERR_CLOSED, "pg: the transaction has ended"); }
    return self.c;
}

static void Tx.end_()
{
    self.done = true;
    if (self.own) { self.c.close(); }
}

/* Commits. A transaction that failed earlier is rolled back by the
 * server instead: commit() then throws IN_FAILED_TRANSACTION. */
pub void Tx.commit()
{
    Conn *c = self.conn_();
    bool committed;
    try {
        committed = c.simpleTag("COMMIT", "COMMIT");
    } catch (Error e) {
        if (c.txStatus != 'I' && !c.dead) {
            try { c.simpleTag("ROLLBACK", "ROLLBACK"); } catch { }
        }
        self.end_();
        throw(e);
    }
    self.end_();
    if (!committed) {
        throw(IN_FAILED_TRANSACTION, "pg: commit: the transaction had failed, the server rolled it back");
    }
}

/* Rolls back; does nothing after commit() or rollback() */
pub void Tx.rollback()
{
    if (self.done) { return; }
    if (!self.c.dead) {
        try {
            if (self.c.inUse()) { throw(ERR_CLOSED, "pg: rollback with open rows"); }
            self.c.simpleTag("ROLLBACK", "ROLLBACK");
        } catch { }
    }
    self.end_();
}

static Rows txOpen(Db *db, u8[] sql, reflect.Value[] args, bool shared)
{
    Tx *tx = db;
    return tx.conn_().queryArgs(sql, args);
}

/* The connection the transaction runs on */
pub Conn *Tx.conn()
{
    return self.conn_();
}

pub i64 Tx.exec(u8[] sql, any ...args) { return self.conn_().execArgs(sql, args); }
pub i64 Tx.execArgs(u8[] sql, reflect.Value[] args) { return self.conn_().execArgs(sql, args); }
pub i64 Tx.execScript(u8[] sql) { return self.conn_().execScript(sql); }
pub Rows Tx.query(u8[] sql, any ...args) { return self.conn_().queryArgs(sql, args); }
pub Rows Tx.queryArgs(u8[] sql, reflect.Value[] args) { return self.conn_().queryArgs(sql, args); }
