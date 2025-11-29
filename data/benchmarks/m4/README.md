# M4 benchmark evidence — the deal query before and after rollups

Plans captured by `scripts/explain.sh` (EXPLAIN (ANALYZE, BUFFERS), three runs, the warm
third kept with all three timings) on the local Postgres 16, Apple Silicon, default
`postgresql.conf` (`shared_buffers` 128 MB, `work_mem` 4 MB). Re-derive any stage with:

    scripts/explain.sh <out-dir> scripts/bench/*.sql

| stage | schema | `price_observations` | what it shows |
|---|---|---|---|
| `before/` | as M3 left it (V1–V3) | 37,780 observed rows, 4 crawl days | the floor, captured before any M4 DDL |
| `backfilled/` | V4, no new index | 37,780 observed + 2,757,940 synthetic | the raw query at scale |
| `trial-covering-index/` | V4 + a covering index, later dropped | same | the index that was measured and declined |
| `after/` | V4 | same, `price_rollups` computed | the rollup-backed query beside the raw one |

`scripts/bench/deal-query-raw.sql` is identical across every stage. `deal-query-rollup.sql`
is the same question read from `price_rollups`; on the same data both return the same sixteen
products in the same order with the same percentiles (checked by diffing their output).

## Headline (from the files)

| query | before (38 k rows) | backfilled (2.8 M rows) | after (2.8 M rows) |
|---|---|---|---|
| deal query, raw observations | 9.5 ms | 321 ms | 356 ms (unchanged: no index was added) |
| deal query, via `price_rollups` | — | — | **0.10 ms** |
| one offer's full history | 0.10 ms | 0.28 ms | 0.46 ms |
| one offer's trailing 90 days | 0.09 ms | 0.14 ms | 0.13 ms |

Sizes (`after/sizes.txt`, after `VACUUM FULL`): `price_observations` 2,795,720 rows in 13
populated monthly partitions, 225 MB heap + 104 MB indexes (the primary key and
`price_observations_run_idx`, ~118 bytes a row all in); `price_rollups` 7,606 rows, 9.6 MB —
about 1.2 KB a row, most of it the year's sale windows as jsonb.

Partition pruning: the 90-day window lists only `2026_06`–`2026_11` in `after/offer-window-90d.txt`
(the two future months survive because the query has no upper bound — the rollup statement's
`observed_at <= as_of` prunes those too, as `Subplans Removed` in `PartitionPruningTest`).

## The passes (`shelf` CLI, same machine, times printed by the commands)

    shelf backfill --category keyboards            2,757,940 rows for 7,556 offers   15.6 s (COPY, one transaction)
    vacuum analyze price_observations               (after the bulk load)            ~0.4 s
    shelf rollup --category keyboards               7,556 offer + 50 product rows     6.4 s, 6.9 s, 6.9 s  (three runs)
    shelf rollup --category keyboards --run 5       same rows (the run saw every offer) 6.3 s

The full recompute is the cost a materialized-view refresh would pay every cycle; the
statement is a sequential scan of the trailing year's partitions (the right plan when every
row is needed) followed by the window functions and aggregates. Every pass rewrites every row
it touches, so `price_rollups` grows by one copy of itself per pass (7,606 dead tuples, ~9 MB)
until autovacuum reclaims it — which the default thresholds (50 rows + 20%) trigger after every
pass. Three passes without it: 35 MB; after `VACUUM FULL`: 9.6 MB.

## The covering index, measured and declined

    create index on price_observations (offer_id, observed_at) include (price_cents, in_stock, source);

| | without | with |
|---|---|---|
| deal query, raw | 320 ms | 222 ms (index-only scans, 0 heap fetches) |
| one offer's history | 0.28 ms | 0.31 ms |
| rollup recompute, whole category | 6.4 s | 6.4 s (still a sequential scan — correctly) |
| indexes on `price_observations_2026_09` | 6.3 MB | 12 MB |

A third off a query the rollup table replaces, nothing for the recompute or the history
query, and roughly double the index footprint (+110 MB at this size). Dropped. `work_mem` is
the other lever on the raw query — 64 MB takes it to 209 ms by keeping its two sorts in memory —
and is a server setting, not schema; noted, not changed. Revisit both at M6 under k6, when
a working set larger than `shared_buffers` may make index-only scans pay.
