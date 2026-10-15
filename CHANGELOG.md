# Changelog

## v0.2.1 (2026-10-15)

- The code, tests and examples declare with `scoped` instead of `ad` (ktm v0.24.0 renamed it;
  v0.25.0 removes `ad`).
- Needs ktm 0.24 (`ktm.mod`).

## v0.2.0 (2026-10-15)

- `pg.queryResult<T>(db, sql, ...)`: every row in a `pg.Result<T>` (`len`, `data`, `at(i)`,
  range-for), the rows' text and bytes in a few large blocks freed together by `res.free()`.
  About 20% faster than `queryAll` on a 1000-row result.
- `c.copyInto<T>(table)` and `put(x)`: COPY in PostgreSQL's binary format from structs, the
  columns' types read from the table; about 2.5x faster than text `row()`s.
- Needs ktm 0.22 (`ktm.mod`).
- A null pool (`r.app` when `http.listen` got none) throws `ERR_ARG` saying so instead of
  crashing: `queryOne`, `queryAll` and the `Pool` calls.

## v0.1.1 (2026-10-14)

- A frozen server (`docker pause`, a hung host) no longer holds `Pool.acquire()`: the health
  ping of an idle connection gets at most 1 s and drops the connection without a cancel round,
  and a new connection must connect within what is left of `acquireTimeoutMs` (when the URL
  has no `connect_timeout`). `acquire()` throws `ERR_CONNECT` within its limit.
- `connect_timeout` bounds the whole connect (dial, TLS request and log-in), not each read.
- The 5 s a timed-out query gives the server to answer its cancel include the cancel's own
  connection, so a query ends within its limit + 5 s.
- Tests: `T_PG_NAME` and `T_PG_PORT` run the suite on other containers; a frozen-server test.

## v0.1.0 (2026-10-14)

The first release: a PostgreSQL client for ktm, written in ktm, speaking the wire protocol
itself.

- Connecting: URLs and the `PG*` environment like libpq, Unix sockets, `connect_timeout`,
  runtime parameters (`application_name`, `search_path`, `statement_timeout`...).
- Authentication: SCRAM-SHA-256, SCRAM-SHA-256-PLUS (channel binding), md5, cleartext, trust,
  client certificates.
- TLS: `sslmode` disable, allow, prefer (the default), require, verify-ca and verify-full;
  `sslrootcert`, `sslcert`, `sslkey`, `sslpassword`, `channel_binding`.
- Statements: `exec` and `query` with `$1` parameters of any type, the extended protocol with a
  statement cache, `execScript`, batches (`pg.Batch`, one round trip, one transaction).
- Results: binary formats for the fixed-size types, `pg.queryAll<T>` and `pg.queryOne<T>` into
  `@json` structs by column name, `rows.scan`, arrays, `pg.Interval`, exact `numeric` into
  integers, NULLs as zero values, pointer fields or `pg.STRICT`.
- Transactions, `COPY` in and out, `LISTEN` / `NOTIFY`.
- A pool safe across the threads of `coro.serve` and `net.http`: per-thread shares of
  `maxConns`, FIFO hand-off, health checks, lifetimes, recovery after a server restart.
- Automatic pipelining: coroutines of a loop share a connection when all are busy.
- Query timeouts with a server-side cancel; `Conn.cancel()`.
- Errors carry the SQLSTATE in `Error.code` (`pg.UNIQUE_VIOLATION`, `pg.state("23505")`).
- Tested on PostgreSQL 14 to 18, compiled and in the JIT with `-Memsafe`; fuzzed against
  damaged server messages; a 1-hour soak under load with a server restart; benchmarked
  against Go pgx (see the README).
