module pg;
import json, mem, reflect;

/* ---- queryResult: every row in one place, freed at once ----
 * queryAll gives each String, u8[] and u8 * of each row a malloc of its
 * own: a 1000-row query of two text columns is 2000 of them, and as many
 * frees. A Result keeps those bytes in a few large blocks (an arena)
 * instead, and frees them together. They are views into the Result:
 * read them while it lives, clone what you keep (s.clone()), and don't
 * free or grow them. Fields of other kinds (a pointer, a vec.Vec, a
 * JSON column into a struct) are allocated as with queryAll and freed
 * by Result.free too. */

/* A block of the arena: the bytes follow the header */
struct Arena
{
    Arena *next;
    i64 used;
    i64 cap;
};

const i64 ARENA_FIRST = 16384;
const i64 ARENA_MAX = 1048576;

/* n bytes from the arena (a new block when the current one is full) */
static u8 *arenaAlloc(Arena *a, i64 n)
{
    Arena *b = a.next;
    if (!b || b.cap - b.used < n) {
        i64 cap = b ? b.cap * 2 : ARENA_FIRST;
        if (cap > ARENA_MAX) { cap = ARENA_MAX; }
        if (cap < n) { cap = n; }
        Arena *grown = mem.alloc(sizeof(Arena) + cap);
        grown.next = b;
        grown.used = 0;
        grown.cap = cap;
        a.next = grown;
        b = grown;
    }
    u8 *p = (u8 *)(b + 1) + b.used;
    b.used += n;
    return p;
}

/* A String of the len bytes at at, written into the zeroed String at
 * to, its bytes in the arena (see setString) */
static void arenaString(Arena *a, u8 *to, u8 *at, i64 len)
{
    u8 *p = arenaAlloc(a, len + 1);
    for (i64 i = 0; i < len; i++) { p[i] = at[i]; }
    p[len] = 0;
    u64 *w = (u64 *)to;
    w[0] = (u64)p;
    w[1] = (u64)len;
    w[2] = (u64)len;
}

/* Every block after the head */
static void arenaFree(Arena *a)
{
    Arena *b = a.next;
    while (b) {
        Arena *next = b.next;
        mem.free(b);
        b = next;
    }
    a.next = null;
}

/* The rows of a query, all of them, as a T each: len and data, so
 *   for (auto u : res) { ... }
 * walks them, and res.at(i) is the i-th. Free it with res.free() (or
 * declare it scoped). */
pub struct Result<T>
{
    i64 len;
    T *data;
    private i64 cap;
    private Arena arena;
};

/* Are a value's bytes in the arena (scanValue puts them there): a
 * String, a u8[] or a u8 *; the rest that allocates is its own */
static bool inArena(reflect.Type *t)
{
    if (t.kind == reflect.Kind.string) { return true; }
    if (t.kind == reflect.Kind.slice && isByte(t.elem)) { return true; }
    return t.kind == reflect.Kind.pointer && isByte(t.elem);
}

/* Frees what isn't in the arena of the value v: each field of a struct
 * (as scan fills them), or v itself */
static void freeOwn(reflect.Value v)
{
    reflect.Type *t = v.type;
    if (t.kind == reflect.Kind.structType && !isDate(t) && !isVec(t) && !isInterval(t)) {
        for (i64 j = 0; j < t.fields.len; j++) {
            reflect.Field *f = &t.fields[j];
            if (f.skip || f.isPrivate || inArena(f.type)) { continue; }
            json.free(reflect.__value((u8 *)v.ptr + f.offset, f.type));
        }
        return;
    }
    if (!inArena(t)) { json.free(v); }
}

/* Frees the rows and everything they hold, and empties res */
pub void Result<T>.free()
{
    if (self.len) {
        reflect.Type *t = reflect.of(self.data[0]).type;
        bool scalar = t.kind == reflect.Kind.int || t.kind == reflect.Kind.float ||
                      t.kind == reflect.Kind.boolean || t.kind == reflect.Kind.enumType;
        for (i64 i = 0; i < self.len && !scalar; i++) {
            freeOwn(reflect.__value(&self.data[i], t));
        }
    }
    mem.free(self.data);
    arenaFree(&self.arena);
    self.data = null;
    self.len = 0;
    self.cap = 0;
}

/* The i-th row; throws ERR_BOUNDS past the end */
pub T *Result<T>.at(i64 i)
{
    if (i < 0 || i >= self.len) {
        throw(ERR_BOUNDS, "pg: Result.at(%d): it has %d rows", i, self.len);
    }
    return &self.data[i];
}

/* Every row of the query in a Result:
 *   scoped pg.Result<User> users = pg.queryResult<User>(db, "SELECT id, name FROM users");
 *   for (auto u : users) { printf("%d %s\n", u.id, u.name); }
 * As queryAll, with the rows' text and bytes in one arena: far fewer
 * allocations for a big result. db is a Conn *, a Tx * or a Pool *. */
pub Result<T> queryResult<T>(Db *db, u8[] sql, any ...args)
{
    if (!db) { dbNull("queryResult"); }
    scoped Rows r = db.open(db, sql, args, true);
    Result<T> res;
    mem.set(&res, 0, sizeof(Result<T>));
    r.arena = &res.arena;
    try {
        while (r.next()) {
            if (res.len == res.cap) {
                res.cap = res.cap ? res.cap * 2 : 16;
                res.data = mem.realloc(res.data, sizeof(T) * res.cap);
            }
            T *x = &res.data[res.len];
            mem.set(x, 0, sizeof(T));
            res.len++;
            reflect.Value xv = reflect.__value(x, reflect.of(*x).type);
            if (r.fieldOf && r.scanType == xv.type) { r.scanFields(x, xv.type, 0); } else { r.scan(xv); }
        }
    } catch (Error e) {
        r.arena = null;
        res.free();
        throw(e);
    }
    r.arena = null;
    return res;
}
