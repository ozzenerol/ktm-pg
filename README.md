# ktm-pg

A PostgreSQL client for [ktm](https://github.com/ozzenerol/ktm-lang), written in ktm.
It speaks the PostgreSQL wire protocol directly (no libpq) and works with ktm's
coroutines, so one `net.http` server can run thousands of queries at once.

Work in progress.

- Authentication: SCRAM-SHA-256, md5, cleartext and trust.
- No TLS yet: ktm has none so far, so only `sslmode=disable` (or `allow`, `prefer`, or no
  sslmode at all) works; `require` and `verify-*` are refused. Run it on a trusted network or
  through a local socket.

```c
import "github.com/ozzenerol/ktm-pg";
```

The package is named `pg`.

## Tests

`tests/run.sh` starts Postgres 14 to 18 in Docker (ports 55414 to 55418, `tests/pg-up.sh`) and
runs every test against each version, compiled and in the JIT with `-Memsafe`.
`PGVER=17 tests/run.sh` runs one version.
