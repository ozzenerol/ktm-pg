import "github.com/ozzenerol/ktm-pg";
import atomic, coro, cstr, date, json, libc, reflect, str, thread, vec;

/* ktm-pg's side of the benchmark against Go pgx (bench/go): the same
 * three queries for a fixed time, CONC coroutines over THREADS threads
 * sharing one pool of CONC connections. Prints one JSON line.
 *   pgbench URL MODE CONC SECONDS THREADS
 * MODE: select (a row by its key), insert, select1000 (1000 rows) */

@json struct User { i64 id; String name; String email; };

pg.Pool *gDb;
u8[] gMode;
i64 gStop;
i64 gOps;
i64 gErrors;

void *worker(void *arg)
{
    i64 seed = (i64)arg * 7919 + 1;
    i64 ops = 0;
    while (date.monoMs() < gStop) {
        try {
            if (gMode.equals("select")) {
                seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF;
                User u = pg.queryOne<User>(gDb, "SELECT id, name, email FROM bench_users WHERE id = $1", 1 + seed % 10000);
                u.freeDecoded();
            } else if (gMode.equals("insert")) {
                gDb.exec("INSERT INTO bench_ins (name, n) VALUES ($1, $2)", "someone", ops);
            } else {
                vec.Vec<User> us = pg.queryAll<User>(gDb, "SELECT id, name, email FROM bench_users WHERE id <= 1000");
                pg.free(reflect.of(us));
            }
            ops++;
        } catch (Error e) {
            atomic.add(&gErrors, 1);
        }
    }
    atomic.add(&gOps, ops);
    return null;
}

i64 gPerThread;

void thread_(void *priv, void *arg)
{
    for (i64 i = 0; i < gPerThread; i++) { coro.go(&worker, (void *)((i64)arg * 1000 + i)); }
    coro.run();
}

i32 main(i32 argc, u8 **argv)
{
    if (argc < 6) {
        printf("usage: pgbench URL MODE CONC SECONDS THREADS\n");
        return 2;
    }
    u8[] url = cstr.view(argv[1]);
    gMode = cstr.view(argv[2]);
    i64 conc, secs, threads;
    str.toInt(cstr.view(argv[3]), &conc);
    str.toInt(cstr.view(argv[4]), &secs);
    str.toInt(cstr.view(argv[5]), &threads);
    if (threads > conc) { threads = conc; }
    pg.Options o;
    o.maxConns = conc;
    gDb = pg.open(url, &o);
    gDb.ping();
    gPerThread = conc / threads;
    i64 start = date.monoNs();
    gStop = date.monoMs() + secs * 1000;
    thread.Pool *tp = thread.poolNew(threads);
    for (i64 t = 0; t < threads; t++) { thread.poolEnqueue(tp, (void *)t, &thread_); }
    thread.poolWait(tp);
    thread.poolStop(tp);
    thread.poolRelease(tp);
    f64 took = (date.monoNs() - start) / 1e9;
    printf("{\"client\":\"ktm-pg\",\"mode\":\"%s\",\"conc\":%d,\"ops\":%d,\"errors\":%d,\"secs\":%.3f,\"ops_s\":%.0f}\n",
           gMode.ptr, conc, gOps, gErrors, took, gOps / took);
    gDb.close();
    return 0;
}
