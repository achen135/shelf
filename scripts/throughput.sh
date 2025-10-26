#!/usr/bin/env bash
#
# Throughput at N workers: one crawl cycle over a category's live retailers.
#
#   scripts/throughput.sh 1            # one worker process
#   scripts/throughput.sh 4            # four
#
# Starts N `shelf worker` processes and one `shelf coordinator --once`, waits for the cycle to
# close, and reports what the database recorded. Needs the local Postgres up and migrated
# (`make up && make migrate`); builds the CLI if it is not built. Results also land under
# build/benchmarks/throughput-<N>/ with every process's log.
#
# Two windows are reported. "cycle" is crawl_runs.started_at → finished_at, which includes up
# to one coordinator poll (5s) of latency after the last page settles. "work" is the first
# task's claim → the last task's settle — the window the workers were actually busy — and is
# the one pages/sec and offers/sec are computed over.
set -euo pipefail

N=${1:?usage: scripts/throughput.sh <workers> [category]}
CATEGORY=${2:-keyboards}
BIN=build/install/shelf/bin/shelf
OUT=build/benchmarks/throughput-$N
PSQL=(docker compose exec -T postgres psql -U shelf -d shelf -At -F ' | ')

[ -x "$BIN" ] || ./gradlew --no-daemon -q installDist
mkdir -p "$OUT"

pids=()
for i in $(seq 1 "$N"); do
  "$BIN" worker --id "bench-w$i" > "$OUT/worker-$i.log" 2>&1 &
  pids+=($!)
done
trap 'kill "${pids[@]}" 2>/dev/null || true' EXIT

echo "workers=$N category=$CATEGORY: crawling..."
"$BIN" coordinator --category "$CATEGORY" --id bench --once | tee "$OUT/coordinator.log"

"${PSQL[@]}" <<SQL | tee "$OUT/result.txt"
with run as (select * from crawl_runs where id = (select max(id) from crawl_runs)),
     tasks as (select t.* from crawl_tasks t join run on t.crawl_run_id = run.id)
select
  'run ' || run.id,
  'workers ' || run.worker_count,
  'pages ' || run.pages,
  'offers ' || sum(tasks.offers_written),
  'observations ' || sum(tasks.observations_written),
  'errors ' || run.errors,
  'cycle_s ' || round(extract(epoch from (run.finished_at - run.started_at))::numeric, 1),
  'work_s ' || round(extract(epoch from (max(tasks.finished_at) - min(tasks.started_at)))::numeric, 1),
  'pages_per_s ' || round((run.pages / extract(epoch from (max(tasks.finished_at) - min(tasks.started_at))))::numeric, 3),
  'offers_per_s ' || round((sum(tasks.offers_written) / extract(epoch from (max(tasks.finished_at) - min(tasks.started_at))))::numeric, 1)
from run, tasks
group by run.id, run.worker_count, run.pages, run.errors, run.started_at, run.finished_at;
SQL
