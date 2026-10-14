#!/bin/bash
# The benchmark against Go pgx, on the LXC (in a directory holding
# pgbench, pgbench-go and setup.sql). Postgres 17 runs pinned to PG_CPUS,
# the client to CLI_CPUS, the same for both clients; every mode x
# concurrency x client REPS times for DUR seconds. One JSON line per run,
# with the client's CPU seconds. Holds /tmp/ktm-lxc.lock throughout and
# stops Postgres at the end.
#   ./lxc.sh > results.jsonl
# Env: MODES="select insert select1000", CONCS="1 64", REPS=3, DUR=10
set -u
PG_CPUS=${PG_CPUS:-3,11,7,15,8,14}
CLI_CPUS=${CLI_CPUS:-1,2}
NCLI=$(echo "$CLI_CPUS" | tr ',' '\n' | wc -l)
MODES=(${MODES:-select insert select1000})
CONCS=(${CONCS:-1 64})
REPS=${REPS:-3}
DUR=${DUR:-10}
URL="postgres://postgres:bench@127.0.0.1:5432/bench?sslmode=disable"

until mkdir /tmp/ktm-lxc.lock 2>/dev/null; do sleep 5; done
trap 'pg_ctlcluster 17 main stop >/dev/null 2>&1; rm -rf /tmp/ktm-lxc.lock' EXIT

taskset -c $PG_CPUS pg_ctlcluster 17 main start
su postgres -c "psql -q -c \"ALTER USER postgres PASSWORD 'bench'\"" >/dev/null
su postgres -c "psql -q -tc \"SELECT 1 FROM pg_database WHERE datname = 'bench'\"" | grep -q 1 || su postgres -c "createdb bench"
su postgres -c "psql -q bench" < setup.sql >/dev/null 2>&1

# the CPU seconds of a subshell's children, from bash's `times`
cpuOf() { awk 'NR == 2 { split($1, u, /[ms]/); split($2, s, /[ms]/); print u[1] * 60 + u[2] + s[1] * 60 + s[2] }'; }

run() {   # client mode conc
    local out
    if [ "$1" = ktm ]; then
        out=$( ( taskset -c $CLI_CPUS ./pgbench "$URL" $2 $3 $DUR $NCLI; times ) 2>&1 )
    else
        out=$( ( GOMAXPROCS=$NCLI taskset -c $CLI_CPUS ./pgbench-go "$URL" $2 $3 $DUR; times ) 2>&1 )
    fi
    local line=$(echo "$out" | grep '^{')
    local cpu=$(echo "$out" | grep -v '^{' | cpuOf)
    echo "${line%\}},\"cpu_s\":${cpu:-0}}"
}

for mode in "${MODES[@]}"; do
    for conc in "${CONCS[@]}"; do
        for rep in $(seq $REPS); do
            for client in ktm pgx; do
                su postgres -c "psql -q bench -c 'TRUNCATE bench_ins'" >/dev/null
                run $client $mode $conc
            done
        done
    done
done
