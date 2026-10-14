import "github.com/ozzenerol/ktm-pg";
import cstr, json, libc, net.http, reflect, str, vec;

/* The soak test's REST API: users on ktm-pg behind net.http.
 *   server PORT URL */

@json struct User { i64 id; String name; String email; };

void getUser(http.Writer *w, http.Request *r)
{
    pg.Pool *db = r.app;
    i64 id;
    if (!str.toInt(r.param("id"), &id)) { w.error(400, "bad id"); return; }
    try {
        User u = pg.queryOne<User>(db, "SELECT id, name, email FROM users WHERE id = $1", id);
        defer u.freeDecoded();
        w.json(200, reflect.of(u));
    } catch (Error e) {
        if (e.code != pg.ERR_NO_ROWS) { throw(e); }
        w.error(404, "no such user");
    }
}

void listUsers(http.Writer *w, http.Request *r)
{
    pg.Pool *db = r.app;
    vec.Vec<User> users = pg.queryAll<User>(db, "SELECT id, name, email FROM users ORDER BY id LIMIT 50");
    defer pg.free(reflect.of(users));
    w.json(200, reflect.of(users));
}

void createUser(http.Writer *w, http.Request *r)
{
    pg.Pool *db = r.app;
    User u = http.decode<User>(r);
    try {
        u.id = pg.queryOne<i64>(db, "INSERT INTO users (name, email) VALUES ($1, $2) RETURNING id", u.name, u.email);
    } catch (Error e) {
        if (e.code != pg.UNIQUE_VIOLATION) { throw(e); }
        w.error(409, "that email is taken");
        return;
    }
    w.json(201, reflect.of(u));
}

void deleteUser(http.Writer *w, http.Request *r)
{
    pg.Pool *db = r.app;
    i64 id;
    if (!str.toInt(r.param("id"), &id)) { w.error(400, "bad id"); return; }
    if (db.exec("DELETE FROM users WHERE id = $1", id) == 0) { w.error(404, "no such user"); return; }
    w.status(204);
}

i32 main(i32 argc, u8 **argv)
{
    if (argc < 3) { printf("usage: server PORT URL\n"); return 2; }
    i64 port;
    str.toInt(cstr.view(argv[1]), &port);
    pg.Pool *db = pg.open(cstr.view(argv[2]));
    db.exec("CREATE TABLE IF NOT EXISTS users (id bigserial PRIMARY KEY, name text NOT NULL, email text NOT NULL UNIQUE)");
    http.Mux mux;
    mux.handle("GET /users", &listUsers);
    mux.handle("GET /users/{id}", &getUser);
    mux.handle("POST /users", &createUser);
    mux.handle("DELETE /users/{id}", &deleteUser);
    i32 rc = http.listen(port, &mux, db);
    db.close();
    return rc;
}
