# ktm-pg

A PostgreSQL client for [ktm](https://github.com/ozzenerol/ktm-lang), written in ktm.
It speaks the PostgreSQL wire protocol itself (no libpq, no C) and works with ktm's
coroutines: a query waits without blocking the thread, so one `net.http` server runs
thousands of queries at once.

```c
import "github.com/ozzenerol/ktm-pg";      // the package is named pg
```

- Connect with a URL and the `PG*` environment, like libpq.
- `exec`, `query`, and `pg.queryAll<T>` / `pg.queryOne<T>` that scan rows into structs;
  `pg.queryResult<T>` for big results, freed at once.
- `COPY` in and out, and binary `COPY` from structs (`c.copyInto<T>(table)`).
- Transactions, a pool safe across `coro.serve` / `net.http` threads, query timeouts with
  server-side cancel, automatic pipelining.
- TLS (`sslmode` like libpq), SCRAM-SHA-256 and SCRAM-SHA-256-PLUS, md5, cleartext,
  client certificates.
- Tested against PostgreSQL 14, 15, 16, 17 and 18.

## A first program

```c
import "github.com/ozzenerol/ktm-pg";
import json, reflect, vec;

@json struct User { i64 id; String name; String email; };

void main()
{
    pg.Conn *c = pg.connect("postgres://ann:secret@localhost:5432/app");
    defer c.close();

    c.exec("CREATE TABLE IF NOT EXISTS users (id bigserial PRIMARY KEY, name text, email text UNIQUE)");
    i64 id = pg.queryOne<i64>(c, "INSERT INTO users (name, email) VALUES ($1, $2) "
                                 "ON CONFLICT (email) DO UPDATE SET name = excluded.name RETURNING id", "Ada", "ada@example.com");

    vec.Vec<User> users = pg.queryAll<User>(c, "SELECT id, name, email FROM users WHERE id >= $1", 1);
    defer pg.free(reflect.of(users));
    for (auto u : users) { printf("%d %s %s\n", u.id, u.name, u.email); }
}
```

## Connecting

`pg.connect(url)` returns a `pg.Conn *` or throws. The URL is
`postgres://user:password@host:port/database?key=value&...` (also `postgresql://`); percent
escapes work in every part, and an IPv6 host goes in brackets (`[::1]`). A host that starts
with `/` is the directory of a Unix socket.

What the URL leaves out comes from `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`, `PGDATABASE`,
`PGAPPNAME`, `PGCONNECT_TIMEOUT`, `PGSSLMODE`, `PGSSLROOTCERT`, `PGSSLCERT`, `PGSSLKEY` and
`PGCHANNELBINDING`, then the defaults: localhost, 5432, `$USER`, a database named after the user.
`pg.connect()` with no URL uses only these.

| Key | Meaning |
| --- | --- |
| `connect_timeout` | seconds to connect and log in, all of it (0: no limit; a pool's connects then get what is left of `acquireTimeoutMs`) |
| `sslmode` | `disable`, `allow`, `prefer` (the default), `require`, `verify-ca`, `verify-full` |
| `sslrootcert` | CAs to trust, a PEM file (`system`: the system's) |
| `sslcert`, `sslkey`, `sslpassword` | a client certificate, its key and the key's password |
| `channel_binding` | `prefer` (the default), `require`, `disable` |
| anything else | sent to the server as a setting: `application_name`, `search_path`, `statement_timeout`, `TimeZone`... |

`~/.postgresql/root.crt`, `postgresql.crt` and `postgresql.key` are used when they exist, as
libpq does.

A connection is used by one coroutine at a time, or shared by the coroutines of one thread's
loop (their queries are pipelined, see below). Plain threads may take turns with a connection;
one that has been used in a coroutine loop stays with that loop's thread, and another thread
gets `ERR_CLOSED` (as does a second thread while the first one is using it). Share connections
between threads through a `pg.Pool`.

## Running statements

```c
i64 n = c.exec("UPDATE users SET name = $1 WHERE id = $2", name, id);   // rows changed
c.execScript("CREATE TABLE a (x int); CREATE INDEX ON a (x);");        // several, no parameters
```

Parameters are `$1`, `$2` ... and the arguments can be of any type. They always travel
separately from the SQL, never formatted into it.

| ktm value | sent as |
| --- | --- |
| integers, `bool`, floats | numbers (binary when the column's type takes them) |
| `String`, `u8[]`, `u8 *`, string literals | text; to a `bytea` parameter: the raw bytes |
| a `u8[16]` to a `uuid` | the 16 bytes |
| an enum | its member's name |
| `date.Date` | a timestamp, timestamptz or date |
| a slice, a fixed array or a `vec.Vec` | an array for an array parameter (`$1::int8[]`), JSON for `json` / `jsonb` |
| a struct | JSON |
| `pg.Interval` | an interval |
| a pointer | what it points to; a null pointer is `NULL` |

## Reading rows

```c
scoped pg.Rows r = c.query("SELECT id, name, born FROM users WHERE id > $1", 10);
while (r.next()) {
    i64 id = r.int(0);
    u8[] name = r.text(1);       // a view, valid until the next row
    if (r.isNull(2)) { ... }
}
```

`r.int(i)`, `r.float(i)`, `r.bool(i)`, `r.text(i)`, `r.bytes(i)` (bytea), `r.micros(i)`
(timestamps as microseconds since 1970, UTC) and `r.date(i)` read column `i`; `r.cols`,
`r.name(i)`, `r.typeOid(i)` and `r.column("name")` describe them. `r.free()` (or `scoped`) reads
and drops the rows that are left and gives the connection back; the connection runs nothing
else of the same coroutine until then.

### Into structs

```c
vec.Vec<User> all = pg.queryAll<User>(db, "SELECT * FROM users");
User u = pg.queryOne<User>(db, "SELECT * FROM users WHERE id = $1", 7);   // ERR_NO_ROWS if none
i64 count = pg.queryOne<i64>(db, "SELECT count(*) FROM users");          // not a struct: column 0
```

Columns fill fields by name: a field's `@key("column")`, else its name (exactly, then ignoring
case). `@skip` fields are left alone. A column with no field is ignored and a field with no
column stays zero, unless `r.scan(reflect.of(x), pg.STRICT)` is used. `db` is a `pg.Conn *`, a
`pg.Tx *` or a `pg.Pool *`.

Free what they allocate with `pg.free(reflect.of(x))`, or `x.freeDecoded()` for an `@json`
struct: Strings, slices, pointed-to values, the same as `json.decode`.

| Postgres | ktm field |
| --- | --- |
| `int2`, `int4`, `int8`, `oid` | any integer (ERR_SCAN when it doesn't fit) |
| `numeric` | an integer (exact: ERR_SCAN for a fraction or overflow), `String` (exact), `f64` (the nearest float) |
| `float4`, `float8` | `f64`, `f32` |
| `bool` | `bool` |
| `text`, `varchar`, `char`, `name`, anything as text | `String`, `u8[]`, `u8 *` |
| `bytea` | `u8[]`, a `u8` array |
| `uuid` | `u8[16]` (the bytes) or `String` (the text) |
| `timestamp`, `timestamptz`, `date` | `date.Date`, `i64` (microseconds since 1970, UTC) |
| `json`, `jsonb` | a struct, slice or `vec.Vec` (decoded), or `String` |
| arrays (`int8[]`, `text[]`, `float8[]`, `bool[]`, `uuid[]`...) | a slice, a fixed array or a `vec.Vec` of matching elements (NULL elements: 0, `""`, `false`; null in a pointer element) |
| `interval` | `pg.Interval` (months, days, micros) or `String` |
| `inet`, `cidr`, anything else | `String` (its text) |
| an enum's name | an enum |

Timestamps are read as UTC: `text()` of a timestamptz ends in `+00`.

### Big results: `queryResult`

`pg.queryResult<T>` reads the same rows as `queryAll`, into a `pg.Result<T>` that keeps the
rows' text and bytes (their `String`, `u8[]` and `u8 *` fields) in a few large blocks instead
of a malloc each, and frees them all at once:

```c
scoped pg.Result<User> users = pg.queryResult<User>(db, "SELECT id, name, email FROM users");
for (auto u : users) { printf("%d %s\n", u.id, u.name); }      // users.len, users.data, users.at(i)
String keep = users.at(0).name.clone();                          // a copy that outlives users
```

- Those `String`s and slices are views into the `Result`: read them while it lives, `clone()`
  what you keep, and don't free or append to them.
- Other fields that allocate (a pointer, a `vec.Vec`, a `json` column into a struct) are their
  own allocations, as with `queryAll`; `res.free()` (or `scoped`) frees them too.
- A 1000-row query of three columns is about 20% faster than `queryAll` from end to end
  (1 malloc per few thousand values instead of one per value).

### NULL

A NULL becomes 0, `""`, `false` or an empty slice in a value field, so a NULL and a real 0 look
the same there. To tell them apart:

- make the field a pointer (`i64 *boss;`, `String *nick;`, also in `@json` structs): NULL leaves
  it null, a value is allocated;
- or scan with `pg.STRICT`: a NULL into a non-pointer field is `ERR_SCAN`;
- or check `r.isNull(i)` when reading by hand.

```c
struct Employee { i64 id; String name; i64 *boss; };
vec.Vec<Employee> e = pg.queryAll<Employee>(db, "SELECT id, name, boss FROM employees");
if (e.at(0).boss) { printf("reports to %d\n", *e.at(0).boss); }
```

## Transactions

```c
pg.Tx tx = c.begin();              // or db.begin() on a pool, c.begin("ISOLATION LEVEL SERIALIZABLE")
defer tx.rollback();               // does nothing after commit()
tx.exec("UPDATE accounts SET balance = balance - $1 WHERE id = $2", 100, from);
tx.exec("UPDATE accounts SET balance = balance + $1 WHERE id = $2", 100, to);
tx.commit();
```

A failed transaction makes `commit()` throw `pg.IN_FAILED_TRANSACTION` (the server rolled it
back). A transaction keeps its connection to itself until it ends.

## The pool

```c
pg.Options o;              // every field 0: its default
o.maxConns = 32;
pg.Pool *db = pg.open("postgres://...", &o);
defer db.close();
db.exec(...); db.query(...); pg.queryAll<T>(db, ...); db.begin();
pg.Conn *c = db.acquire(); ...; c.release();   // a connection to yourself (SET, temp tables, LISTEN)
```

| Option | Default |
| --- | --- |
| `maxConns` | 4 per CPU, 8 to 64 |
| `maxIdleMs` | 30 minutes |
| `maxLifetimeMs` | 1 hour |
| `acquireTimeoutMs` | 30 s, then `ERR_POOL`; it bounds the health ping and connecting too |
| `healthCheckMs` | a connection idle this long (1 s) is pinged before use; no answer within 1 s and it is dropped |
| `queryTimeoutMs` | none |

The pool is safe from every thread of `coro.serve` and `net.http`. Each thread has its share of
the connections, because a socket belongs to the coroutine loop of its thread; idle ones of a
quiet thread go to busy ones. A broken connection is never handed out: after a server restart
the next query gets a fresh connection. `pg.open` connects lazily; `db.ping()` checks at once.

### Pipelining

When every connection of a thread is busy, `exec`, `queryAll` and `queryOne` on a pool share
one: their messages go out without waiting for the answers before them, and each takes its
answer in turn. The same works on one `pg.Conn` shared by the coroutines of a loop. Errors stay
with their own query. Transactions, `query()` (Rows) and `acquire()` get a connection to
themselves.

## Batches

```c
pg.Batch b;
defer b.free();
for (auto u : users) { b.add("INSERT INTO users (name, email) VALUES ($1, $2)", u.name, u.email); }
i64[] counts = db.execBatch(&b);     // one round trip; yours: counts.free()
```

The statements of a batch go out together and run as one implicit transaction: when one
fails, its error is thrown (`batch statement 3: ...`) and none of them stays. `add` copies its
arguments.

## COPY

```c
pg.CopyIn in = c.copyIn("COPY users (name, email) FROM STDIN");
for (auto u : users) { in.row(u.name, u.email); }      // or in.write(text in COPY's format)
i64 n = in.end();                                     // in.abort() gives up

pg.CopyOut out = c.copyOut("COPY users TO STDOUT (FORMAT csv)");
defer out.free();
while (out.next()) { fs.write(f, out.data); }
```

`row()` writes COPY's text format (tab-separated, escaped, NULL as `\N`); bytea goes as its
`\x...` hex text there. A bad row fails `end()` with the server's error and nothing is copied.
From a pool, use `db.acquire()`.

### Binary COPY from structs

```c
pg.CopyInto<Item> in = c.copyInto<Item>("items");    // the columns Item's fields name
for (auto x : items) { in.put(x); }
i64 n = in.end();                                    // in.abort() gives up
```

`copyInto<T>` reads the table's column types once and sends each row in PostgreSQL's binary
format: nothing to format or parse, about 2.5x faster than `row()` (100,000 rows of five
columns: 55 ms against 136 ms).

- Fields name columns as when scanning (`@key`, else the name; `@skip` and private fields left
  out); the other columns get their defaults. A null pointer field is NULL.
- Column types: the integers, `float4`/`float8`, `bool`, `bytea`, `uuid` (16 bytes), `timestamp`,
  `timestamptz` and `date` (from `date.Date`), `interval` (`pg.Interval`), `text`, `varchar`,
  `char`, `name`, `json`, `jsonb` (text, or a struct encoded as JSON) and enums.
- A field with no column, or a column of another type (`numeric`, arrays, `inet`...), is
  `ERR_ARG` from `copyInto`; use `copyIn` and `row()` for those tables. A value that can't go in
  its column (a `String` into an `int8`) is `ERR_ARG` from `put`, before any of the row is sent.

## LISTEN and NOTIFY

```c
pg.Conn *l = db.acquire();         // a connection of its own for listening
l.listen("jobs");
pg.Notification n;
while (l.waitNotification(&n, 5000)) {      // false after 5 s with none
    printf("%s: %s\n", n.channel, n.payload);
    n.free();
}
db.notify("jobs", "payload");      // or c.notify, or NOTIFY in SQL
```

Notifications that arrive during queries are kept until `waitNotification` reads them. In a
loop coroutine the wait pauses only the coroutine.

## Timeouts and cancelling

```c
c.setQueryTimeout(2000);       // or Options.queryTimeoutMs for a pool
c.cancel();                    // from another coroutine or thread
```

A query past its time is cancelled on the server and throws `pg.QUERY_CANCELED`, and the
connection stays usable. When the server doesn't answer the cancel within 5 s, the connection
is closed (`ERR_IO`), so on a server that froze every call ends within its limit + 5 s.
`statement_timeout=...` in the URL sets the server's own limit.

## TLS

`sslmode` works as in libpq: `prefer` (the default) uses TLS when the server has it; `require`
needs it but checks nothing (the chain, when `sslrootcert` is given); `verify-ca` checks the
chain; `verify-full` the chain and the host name. With TLS, SCRAM uses channel binding
(SCRAM-SHA-256-PLUS); `channel_binding=require` refuses any login without it.

```c
pg.connect("postgres://app@db.example.com/app?sslmode=verify-full&sslrootcert=/etc/ssl/db-ca.pem");
```

## Performance

Against Go's pgx (v5, pgxpool) on the same queries, both clients pinned to the same two CPUs
and Postgres 17 to six others (`bench/`: `lxc.sh` runs it, `table.py` prints this; median of
3 x 10 s, 2026-10-14):

| query | concurrent | ktm-pg ops/s | pgx ops/s | ktm-pg / pgx | client CPU per query, ktm-pg / pgx |
| --- | --- | --- | --- | --- | --- |
| select a row by key | 1 | 35,894 | 29,999 | 1.20x | 12.8 / 26.1 us |
| select a row by key | 64 | 162,569 | 144,844 | 1.12x | 9.4 / 11.6 us |
| insert | 1 | 1,543 | 1,513 | 1.02x | 18.8 / 47.0 us |
| insert | 64 | 50,281 | 51,050 | 0.98x | 10.2 / 13.5 us |
| select 1000 rows into structs | 1 | 3,279 | 3,998 | 0.82x | 255 / 204 us |
| select 1000 rows into structs | 64 | 8,267 | 9,596 | 0.86x | 242 / 185 us |

Point queries, the bulk of a REST API's work, are faster with about half the client CPU;
inserts wait for the server's fsync either way. pgx still wins on big results scanned into
structs: each row's Strings are a malloc and later a free here, while Go's allocator and
garbage collector make the same allocations cheaper (and its collector runs on the second CPU).
Reading such results with `pg.queryResult<T>` (one allocation per block of rows, not per
value) or `rows.text(i)` views, which allocate nothing, avoids that cost.

## Errors

Everything throws. A server error's `e.code` is its SQLSTATE, so

```c
try { ... } catch (Error e) {
    if (e.code == pg.UNIQUE_VIOLATION) { ... }         // or pg.state("23505")
}
```

and `e.message` reads `duplicate key value violates unique constraint "users_email_key"
(SQLSTATE 23505); detail: Key (email)=(ada@example.com) already exists.`
`pg.isServerError(code)` and `pg.stateText(code)` tell them apart. Named ones:
`UNIQUE_VIOLATION`, `FOREIGN_KEY_VIOLATION`, `NOT_NULL_VIOLATION`, `CHECK_VIOLATION`,
`SERIALIZATION_FAILURE`, `DEADLOCK_DETECTED`, `UNDEFINED_TABLE`, `UNDEFINED_COLUMN`,
`SYNTAX_ERROR`, `QUERY_CANCELED`, `INVALID_PASSWORD`, `DIVISION_BY_ZERO`,
`IN_FAILED_TRANSACTION`.

The client's own: `ERR_CONNECT`, `ERR_AUTH`, `ERR_TLS`, `ERR_IO` (the connection broke and is
closed), `ERR_NO_ROWS`, `ERR_SCAN`, `ERR_POOL`, `ERR_PROTOCOL`, `ERR_ARG`, `ERR_CLOSED`.

## Tests

Beyond the suites: `tests/fuzz_test.ktm` replays damaged server messages (20000 rounds a run),
and `bench/soak/` ran a `net.http` users API on ktm-pg for an hour under wrk (2.7 to 4.9 million
requests a minute) with a Postgres restart at minute 30: errors only during the restart, none
after it, the server's memory flat at 5.6 MB (`bench/soak/results-2026-10-14.jsonl`).

`tests/run.sh` starts PostgreSQL 14 to 18 in Docker (ports 55414 to 55418, `tests/pg-up.sh`,
with TLS from the test CA in `tests/tls`) and runs every test against each version, compiled and
in the JIT with `-Memsafe`. `PGVER=17 tests/run.sh` runs one version.

## License

BSD-2-Clause.
