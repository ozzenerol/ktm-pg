#!/bin/sh
# Runs every tests/*_test.ktm, AOT and JIT (-Memsafe), against each Postgres
# version: 14 15 16 17 18 (PGVER=17 tests/run.sh for one). Starts the
# containers with pg-up.sh. Exit code 1 when anything failed.
dir=$(cd "$(dirname "$0")" && pwd)
versions=${PGVER:-"14 15 16 17 18"}
PGVER="$versions" "$dir/pg-up.sh" || exit 1
# the tests import "github.com/ozzenerol/ktm-pg": tests/ktm.mod points it at this checkout
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
ulimit -v 4000000
fail=0
total=0
for f in "$dir"/*_test.ktm; do
    name=$(basename "$f" .ktm)
    if ! (cd "$dir" && timeout 120 ktm "$f" -o "$work/$name" > "$work/$name.log" 2>&1); then
        echo "$name: AOT compile FAILED"; cat "$work/$name.log"; fail=$((fail + 1)); continue
    fi
    for v in $versions; do
        for mode in aot jit; do
            total=$((total + 1))
            if [ $mode = aot ]; then
                out=$(cd "$dir" && PGVER=$v timeout 120 "$work/$name" 2>&1)
            else
                out=$(cd "$dir" && PGVER=$v timeout 120 ktm -jit -Memsafe "$f" 2>&1)
            fi
            code=$?
            line=$(printf '%s\n' "$out" | grep -a "^Test - " | tail -1)
            if [ $code -ne 0 ] || printf '%s\n' "$out" | grep -aq -e FAIL -e "double free" -e "leak" -e "wild free"; then
                echo "pg $v $mode $name: FAILED (exit $code)"
                printf '%s\n' "$out" | tail -20
                fail=$((fail + 1))
            else
                echo "pg $v $mode ${line#Test - }"
            fi
        done
    done
done
echo "$((total - fail))/$total runs passed"
[ $fail -eq 0 ]
