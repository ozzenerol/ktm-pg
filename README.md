# ktm-pg

A PostgreSQL client for [ktm](https://github.com/ozzenerol/ktm-lang), written in ktm.
It speaks the PostgreSQL wire protocol directly (no libpq) and works with ktm's
coroutines, so one `net.http` server can run thousands of queries at once.

Work in progress.

```c
import "github.com/ozzenerol/ktm-pg";
```

The package is named `pg`.
