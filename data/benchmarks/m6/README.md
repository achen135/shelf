# M6 — the query API under k6

Closed-loop load against `shelf api` on the local corpus (50 products, 7,556 offers, 2.8 M
observations), 2026-09-12/13: the API (`--db-pool 8`, Jetty on virtual threads), Postgres 16 in
Docker and k6 v2.2.0 all on one 10-core Apple Silicon laptop, so every number includes the
client and the database competing for the same cores. Script: `scripts/k6/api.js` — each VU
issues one request after another with no think time; an iteration is `GET /products` (50%,
with a random price window, sort and spec filter drawn from the real schema), `GET
/products/{id}?days=365` (35%, a random product) or `GET /deals` (15%).

Re-derive: `shelf api` in one terminal, then `scripts/k6/sweep.sh` (the sweep) and
`k6 run -e VUS=16 -e DURATION=60s --summary-export … scripts/k6/api.js` (the pin). Attended:
this laptop sleeps after one minute idle and a sleeping benchmark reports nonsense (two such
runs were discarded; see `docs/benchmarks/m6-query-api.md`).

## The sweep (`sweep/`, 30 s per step)

| VUs | req/s | p50 | p95 | p99 | list p50 / p99 | detail p50 / p99 | deals p50 / p99 |
|---|---|---|---|---|---|---|---|
| 1 | 481 | 1.6 | 5.2 | 8.5 | 1.7 / 3.2 | 2.1 / 16.9 | 0.4 / 0.8 |
| 2 | 1,041 | 1.5 | 5.1 | 8.1 | 1.6 / 2.8 | 1.7 / 17.0 | 0.3 / 0.7 |
| 4 | 1,781 | 1.7 | 6.0 | 9.9 | 1.8 / 3.3 | 2.0 / 20.3 | 0.4 / 0.8 |
| **8** | **2,178** | 2.9 | 9.0 | 17.9 | 3.0 / 6.8 | 3.5 / 27.5 | 0.7 / 1.6 |
| 16 | 2,042 | 6.9 | 14.6 | 24.1 | 7.0 / 13.3 | 8.0 / 36.6 | 4.2 / 8.5 |
| 32 | 1,778 | 16.9 | 27.8 | 41.0 | 16.9 / 30.6 | 18.5 / 50.6 | 13.7 / 26.4 |
| 64 | 1,555 | 39.6 | 59.3 | 80.1 | 39.5 / 75.1 | 41.8 / 86.7 | 35.6 / 71.3 |

Milliseconds; zero failed requests at every step. Throughput saturates at **8 VUs — the size
of the connection pool** — and latency grows linearly with concurrency from there: the extra
VUs queue on the pool. The detail endpoint is the expensive shape (a 156-listing product's
year is ~15 ms of index scans); the deals query is the cheap one.

## The pin (`pinned-vus-16-60s.*`): 16 VUs, 60 s

Pinned deliberately **past** the knee, at twice the pool, and run for a full minute after the
sweep: **1,529 req/s, p50 9.3 ms, p95 19.2 ms, p99 32.1 ms**, 91,773 requests, 0 failed. Per
endpoint: list p50 9.4 / p99 17.6; detail p50 10.7 / p99 48.0; deals p50 5.7 / p99 11.2.

## Is the pool the ceiling? (`pool/`, 16 VUs, 30 s)

| `--db-pool` | req/s | p50 | p99 | detail p99 |
|---|---|---|---|---|
| 4 | 1,353 | 10.9 | 27.8 | 36.8 |
| 8 (default) | 1,529 (pin) / 2,042 (sweep step) | 9.3 / 6.9 | 32.1 / 24.1 | 48.0 / 36.6 |
| 16 | 1,971 | 6.0 | 41.1 | 60.8 |

Four connections is clearly pool-bound; sixteen lands between the two pool-8 measurements
with a longer tail. **The run-to-run bar on this machine is ±25%** — the two pool-8 rows are
the same configuration — so 8 vs 16 is inside it and 4 is not. Eight stays the default.
