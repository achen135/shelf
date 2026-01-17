#!/usr/bin/env python3
"""Tabulate k6 --summary-export files: one row per run, throughput and latency percentiles
overall and per endpoint. Usage: scripts/k6/tabulate.py out/vus-*.json"""
import json
import re
import sys


def vals(m):
    """k6's --summary-export has flattened metrics; a newer export nests them under `values`."""
    if m is None:
        return None
    return m.get("values", m)


def pct(m, key):
    v = vals(m)
    return v.get(key) if v else None


def fmt(v):
    return "—" if v is None else f"{v:8.1f}"


rows = []
for path in sys.argv[1:]:
    with open(path) as f:
        s = json.load(f)
    m = s["metrics"]
    n = int(re.search(r"vus-(\d+)", path).group(1))
    reqs = vals(m["http_reqs"])
    dur = vals(m["http_req_duration"])
    failed = vals(m["http_req_failed"])["value"]
    rows.append(
        (
            n,
            reqs["rate"],
            dur["med"],
            dur["p(95)"],
            dur["p(99)"],
            failed,
            pct(m.get("products_ms"), "med"),
            pct(m.get("products_ms"), "p(99)"),
            pct(m.get("detail_ms"), "med"),
            pct(m.get("detail_ms"), "p(99)"),
            pct(m.get("deals_ms"), "med"),
            pct(m.get("deals_ms"), "p(99)"),
        )
    )
rows.sort()
print(
    f"{'VUs':>4} {'req/s':>8} {'p50 ms':>8} {'p95 ms':>8} {'p99 ms':>8} {'failed':>7}"
    f" | {'list p50':>8} {'list p99':>8} | {'det p50':>8} {'det p99':>8} | {'deal p50':>8} {'deal p99':>8}"
)
for r in rows:
    print(
        f"{r[0]:>4} {r[1]:8.1f} {r[2]:8.1f} {r[3]:8.1f} {r[4]:8.1f} {r[5]:7.2%}"
        f" | {fmt(r[6])} {fmt(r[7])} | {fmt(r[8])} {fmt(r[9])} | {fmt(r[10])} {fmt(r[11])}"
    )
