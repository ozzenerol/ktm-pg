#!/usr/bin/env python3
"""The benchmark's table: the median of the runs of each mode, concurrency
and client; ops/s, client CPU per op, and ktm-pg against pgx.
  python3 table.py results.jsonl"""
import json, statistics, sys
from collections import defaultdict

runs = defaultdict(list)
for line in open(sys.argv[1]):
    line = line.strip()
    if not line.startswith("{"):
        continue
    r = json.loads(line)
    runs[(r["mode"], r["conc"], r["client"])].append(r)

def med(rs, f):
    return statistics.median(f(r) for r in rs)

print("| query | conc | ktm-pg ops/s | pgx ops/s | ktm-pg / pgx | ktm-pg CPU us/op | pgx CPU us/op | errors |")
print("| --- | --- | --- | --- | --- | --- | --- | --- |")
for mode in ("select", "insert", "select1000"):
    for conc in sorted({k[1] for k in runs if k[0] == mode}):
        k, g = runs.get((mode, conc, "ktm-pg"), []), runs.get((mode, conc, "pgx"), [])
        if not k or not g:
            continue
        ko, go = med(k, lambda r: r["ops_s"]), med(g, lambda r: r["ops_s"])
        kc = med(k, lambda r: r["cpu_s"] / max(r["ops"], 1) * 1e6)
        gc = med(g, lambda r: r["cpu_s"] / max(r["ops"], 1) * 1e6)
        errs = sum(r["errors"] for r in k + g)
        print(f"| {mode} | {conc} | {ko:,.0f} | {go:,.0f} | {ko / go:.2f}x | {kc:.1f} | {gc:.1f} | {errs} |")
