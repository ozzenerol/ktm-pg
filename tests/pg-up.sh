#!/bin/sh
# Start the test Postgres containers: ktm-pg-14 .. ktm-pg-18 on ports 55414 .. 55418 (host network).
# PGVER=17 tests/pg-up.sh starts one. tests/pg-up.sh down removes them.
# T_PG_NAME=x T_PG_PORT=564 makes x-14 .. x-18 on 56414 .. 56418 instead.
set -e
dir=$(cd "$(dirname "$0")" && pwd)
versions=${PGVER:-"14 15 16 17 18"}
prefix=${T_PG_NAME:-ktm-pg}
ports=${T_PG_PORT:-554}
# a container made from an older pg-init.sh is made again
sum=$(cat "$dir/pg-init.sh" "$dir/pg-up.sh" "$dir/tls/server.pem" | md5sum | cut -c1-12)
for v in $versions; do
    name=$prefix-$v
    if [ "$1" = down ]; then docker rm -f $name >/dev/null 2>&1 || true; continue; fi
    if docker ps -a --format '{{.Names}}' | grep -qx $name &&
       [ "$(docker inspect -f '{{index .Config.Labels "ktm-pg-init"}}' $name)" != "$sum" ]; then
        docker rm -f $name >/dev/null
    fi
    if docker ps --format '{{.Names}}' | grep -qx $name; then continue; fi
    if docker ps -a --format '{{.Names}}' | grep -qx $name; then docker start $name >/dev/null; continue; fi
    # host networking: Postgres listens on 554NN itself, no docker-proxy in between
    docker run -d --name $name --network host -e PGPORT=$ports$v -e POSTGRES_PASSWORD=ktm --label ktm-pg-init=$sum \
        -v "$dir/pg-init.sh:/docker-entrypoint-initdb.d/pg-init.sh:ro" -v "$dir/tls:/tls:ro" \
        --shm-size=64m postgres:$v >/dev/null
done
[ "$1" = down ] && exit 0
for v in $versions; do
    i=0
    until docker exec $prefix-$v pg_isready -h 127.0.0.1 -p $ports$v -U postgres -q 2>/dev/null; do
        i=$((i + 1)); [ $i -gt 120 ] && { echo "$prefix-$v did not start" >&2; exit 1; }
        sleep 0.5
    done
done
