#!/bin/sh
# Start the test Postgres containers: ktm-pg-14 .. ktm-pg-18 on ports 55414 .. 55418.
# PGVER=17 tests/pg-up.sh starts one. tests/pg-up.sh down removes them.
set -e
dir=$(cd "$(dirname "$0")" && pwd)
versions=${PGVER:-"14 15 16 17 18"}
for v in $versions; do
    name=ktm-pg-$v
    if [ "$1" = down ]; then docker rm -f $name >/dev/null 2>&1 || true; continue; fi
    if docker ps --format '{{.Names}}' | grep -qx $name; then continue; fi
    if docker ps -a --format '{{.Names}}' | grep -qx $name; then docker start $name >/dev/null; continue; fi
    docker run -d --name $name -p 554$v:5432 -e POSTGRES_PASSWORD=ktm \
        -v "$dir/pg-init.sh:/docker-entrypoint-initdb.d/pg-init.sh:ro" \
        --shm-size=64m postgres:$v >/dev/null
done
[ "$1" = down ] && exit 0
for v in $versions; do
    i=0
    until docker exec ktm-pg-$v pg_isready -h 127.0.0.1 -U postgres -q 2>/dev/null; do
        i=$((i + 1)); [ $i -gt 120 ] && { echo "ktm-pg-$v did not start" >&2; exit 1; }
        sleep 0.5
    done
done
