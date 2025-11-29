#!/usr/bin/env bash
# Capture the plan and latency of each benchmark query (M4).
#
#   scripts/explain.sh data/benchmarks/m4/before scripts/bench/*.sql
#
# Every query in scripts/bench/ is run three times through EXPLAIN (ANALYZE, BUFFERS); the
# third run's plan — warm cache, the steady state an API sees — is written to OUT_DIR/<name>.txt
# under a header carrying all three execution times. sizes.sql is run as a plain query.
# The outputs are committed, so a plan can be compared across the milestone's stages and
# re-derived on any database with the same schema.
#
# PSQL overrides how psql is reached; the default is the compose postgres (`make up`).
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "usage: $0 OUT_DIR QUERY.sql [QUERY.sql ...]" >&2
  exit 2
fi
out_dir=$1; shift
mkdir -p "$out_dir"

PSQL=${PSQL:-"docker compose exec -T postgres psql -U shelf -d shelf"}
psql_run() { $PSQL -X -q -v ON_ERROR_STOP=1 -P pager=off "$@"; }

for file in "$@"; do
  name=$(basename "$file" .sql)
  out="$out_dir/$name.txt"
  if [ "$name" = "sizes" ]; then
    { echo "-- $name  $(date -u +%Y-%m-%dT%H:%M:%SZ)  $(psql_run -At -c 'select version()')"; echo;
      psql_run -f - < "$file"; } > "$out"
    echo "$out"
    continue
  fi
  query=$(sed -e 's/--.*$//' "$file" | tr '\n' ' ' | sed -e 's/;[[:space:]]*$//')
  times=()
  plan=""
  for i in 1 2 3; do
    plan=$(psql_run -At -c "explain (analyze, buffers, format text) $query")
    times+=("$(printf '%s\n' "$plan" | sed -n 's/^Execution Time: \([0-9.]*\) ms$/\1/p')")
  done
  { echo "-- $name  $(date -u +%Y-%m-%dT%H:%M:%SZ)  $(psql_run -At -c 'select version()')"
    echo "-- execution time, three consecutive runs: ${times[0]} ms, ${times[1]} ms, ${times[2]} ms (plan below is run 3)"
    echo "-- partitions in the plan: $(printf '%s\n' "$plan" | grep -Eo 'price_observations_[0-9]{4}_[0-9]{2}' | sort -u | tr '\n' ' ')"
    echo
    printf '%s\n' "$plan"; } > "$out"
  echo "$out: ${times[*]} ms"
done
