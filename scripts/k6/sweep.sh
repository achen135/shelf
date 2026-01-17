#!/usr/bin/env bash
# Sweep the API's concurrency: one closed-loop k6 run per VU count, summaries under OUT, and a
# table of throughput and latency per step so the knee is visible before a number is pinned.
#
#   scripts/k6/sweep.sh [out-dir] [duration]        # defaults: data/benchmarks/m6/sweep, 30s
#   BASE=http://localhost:8080 VUS="1 2 4 8 16 32 64" scripts/k6/sweep.sh
set -euo pipefail
OUT="${1:-data/benchmarks/m6/sweep}"
DURATION="${2:-30s}"
BASE="${BASE:-http://localhost:8080}"
VUS="${VUS:-1 2 4 8 16 32 64}"
mkdir -p "$OUT"
for n in $VUS; do
  echo "== $n VU(s), $DURATION" >&2
  k6 run --quiet -e BASE="$BASE" -e VUS="$n" -e DURATION="$DURATION" \
    --summary-export "$OUT/vus-$n.json" scripts/k6/api.js > "$OUT/vus-$n.txt" 2>&1 || true
done
scripts/k6/tabulate.py "$OUT"/vus-*.json | tee "$OUT/sweep.txt"
