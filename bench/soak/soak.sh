#!/bin/bash
# The soak test, on the LXC (with server, soak.lua): the REST API under wrk
# load for MINUTES minutes, one wrk run per minute, Postgres restarted at
# RESTART_AT. One JSON line per minute: wrk's result, the server's RSS and
# CPU. Postgres 17 is stopped at the end. Takes /tmp/ktm-lxc.lock only for
# setup and teardown (tell ktm-lang-36 before an hour-long run).
#   ./soak.sh > soak.jsonl
set -u
MINUTES=${MINUTES:-60}
RESTART_AT=${RESTART_AT:-30}
PORT=9310
URL="postgres://postgres:bench@127.0.0.1:5432/bench?sslmode=disable"
trap 'kill -INT $SPID 2>/dev/null; sleep 1; pg_ctlcluster 17 main stop >/dev/null 2>&1' EXIT
taskset -c 7,15,8,14 pg_ctlcluster 17 main start 2>/dev/null
su postgres -c "psql -q bench -c 'DROP TABLE IF EXISTS users'" >/dev/null
su postgres -c "psql -q bench -c \"CREATE TABLE users (id bigserial PRIMARY KEY, name text NOT NULL, email text NOT NULL UNIQUE); INSERT INTO users (name, email) SELECT 'u' || i, 'u' || i || '@x' FROM generate_series(1, 10000) i\"" >/dev/null
taskset -c 1,2 ./server $PORT "$URL" > server.log 2>&1 &
SPID=$!
sleep 1
HZ=$(getconf CLK_TCK)
for m in $(seq $MINUTES); do
    if [ $m -eq $RESTART_AT ]; then ( sleep 20; taskset -c 7,15,8,14 pg_ctlcluster 17 main restart ) & fi
    t0=$(awk '{print $14 + $15}' /proc/$SPID/stat)
    line=$(taskset -c 3,11 wrk -t2 -c64 -d60s --timeout 5s -s soak.lua http://127.0.0.1:$PORT/ 2>/dev/null | grep '^{')
    t1=$(awk '{print $14 + $15}' /proc/$SPID/stat 2>/dev/null || echo $t0)
    rss=$(awk '/VmRSS/{print $2}' /proc/$SPID/status 2>/dev/null || echo 0)
    line=${line#\{}
    echo "{\"minute\":$m,${line%\}},\"rss_kb\":$rss,\"cpu\":$(echo "($t1 - $t0) / $HZ / 60" | bc -l | cut -c1-5)}"
done
