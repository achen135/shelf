# Shelf

Price & deal intelligence for enthusiast product categories. Pick a category, set a budget and
spec filters, get a ranked shortlist and a **buy-now / wait** signal grounded in real price
history.

A *category* is a config file — its spec schema, its retailers and how to fetch each, its seed
products — so onboarding one is configuration plus parsers, not new pipeline code. The crawler
never touches the open web: it visits the paths a category file names, and nothing else.

> **Status: M5 complete.** Every product now carries a **buy / wait / neutral** call
> (`deal_signals`) with the reasons behind it — a readable rule over its `price_rollups` row
> (on sale by the rollup's own definition and at or near the year's low or in its cheapest
> fifth → buy; not a deal, on a product that does go on sale → wait; nothing to buy, thin
> history, or a middling sale → no call), refreshed after every crawl cycle as the last step
> of the resolution → rollup → signal chain. `shelf eval backtest` replays the rule over every
> day of the trailing year — each day's rollup row **recomputed as of that day** by the rollup
> statement itself, so nothing after it leaks in — and judges each call by what the price did
> over the next 30 days: **0.744 hit rate at 84.5% coverage vs 0.360 for "always buy" and
> 0.712 for "buy below median"**, on 11,798 product-days of a **labeled synthetic year** (the
> report says so on every line; the real history is two days old). The rule was written and
> committed before the backtest was run; the report sweeps its thresholds and shows that the
> one change that raises the hit rate (to 0.829) cuts what a shopper following it saves from
> 8.2% to 2.5% — so it was not made. Rollups (M4: 2.8 M observations, the deal query 321 ms →
> 0.10 ms), resolution (M3: precision 1.000 / recall 0.976) and the distributed crawler (M2:
> SIGKILL recovery in 13.0 s / 4.9 s) are unchanged. See `docs/Spec.md` §7 for the milestone
> plan, `docs/Sessions.md` for what each one actually did, and `CLAUDE.md` for the working
> agreement.

## Prereqs

- **JDK 21** (Temurin or equivalent). Gradle comes from the committed wrapper — no system
  Gradle needed.
- **Docker** + Docker Compose, for local Postgres and for the Testcontainers-backed tests.

## Quickstart

```
make up        # docker compose up -d postgres, waits for healthy
make migrate   # ./gradlew flywayMigrate  (applies src/main/resources/db/migration)
make test      # ./gradlew check — compile + Error Prone + SpotBugs + Spotless + JUnit
make down
```

`make test` starts a throwaway Postgres via Testcontainers, so Docker must be running. On
macOS with Docker Desktop this works without further setup; see the comment in
`build.gradle.kts` if your Docker socket lives somewhere unusual. Two of the tests launch real
`shelf` subprocesses and SIGKILL them; they take about a minute between them.

### Crawling

```
make crawl     # shelf crawl --category keyboards --once
```

It prints a per-retailer summary — pages, offers, price points, errors — then runs an
entity-resolution pass over what it wrote, a rollup pass over what it touched and a signal
pass over the products that recomputed, and prints all three. Fetched bodies land under `data/raw/<run>/`, content-addressed, and every attempt gets a
`raw_fetches` row.

The crawl is paced by `max_rps` in the category file (one request every five seconds per domain
today), so a full cycle takes about half a minute of mostly waiting. That is deliberate.

### The distributed crawler

```
docker compose up --scale worker=4      # postgres + migrate + 2 coordinators (one leads) + 4 workers
docker kill shelf-worker-2              # its leased page is back in the queue within 15 s + 5 s
docker kill $(docker compose ps -q coordinator | head -1)   # the standby leads within 5 s
```

Or without Docker, against `make up`'s Postgres: `shelf worker` in as many terminals as you like,
then `shelf coordinator --category keyboards --once` to run one cycle and print its summary.
`scripts/throughput.sh N` does exactly that with N workers and reports pages/sec from the
database. Timings (`--lease`, `--heartbeat`, `--poll`, …) are flags; the defaults and what they
were measured at are in `docs/benchmarks/m2-distributed-crawler.md`.

A few queries worth having at `make psql`:

```sql
select state, count(*) from crawl_tasks where crawl_run_id = (select max(id) from crawl_runs) group by 1;
select a.application_name from pg_locks l join pg_stat_activity a on a.pid = l.pid where l.locktype = 'advisory';
select domain, next_allowed_at from domain_rate_limits;
```

### Entity resolution

```
shelf resolve --category keyboards              # one pass over the pending offers (the coordinator does this after every cycle)
shelf resolve --category keyboards --rescore    # reopen the machine's links after a scorer or config change; human decisions stay
shelf review list --category keyboards          # what the resolver was not sure about, best score first
shelf review accept --category keyboards 266    # confirm the proposal (or --product <id> for a different one)
shelf review reject 266                         # not a catalog product; never re-scored
shelf eval resolution --category keyboards --labels data/labels/keyboards-resolution.tsv
```

The resolver links a listing to a seed product when the product's model appears whole in the
title and nothing counts against it; a sibling's qualifier next to the model ("Q2 **HE**", "K2
**Max**" when the catalog has a Q6 HE and a Q15 Max), a missing numbered token, or a phrase that
marks a bundle or a part all push it down into the review queue or out. Every score carries its
reasons. The scorer is category-agnostic; what it cannot know about keyboards — which spec
fields identify a product, which phrases mean "not the product" — lives in the category file's
`resolution:` section.

`data/labels/keyboards-resolution.tsv` is the hand-labeled set (235 pairs, written from titles
before any score was seen; the policy is in the file's header), and
`keyboards-resolution.eval.txt` is the committed report: precision / recall at the operating
point, the threshold sweep, and every miss with the reason. Re-derive it with one crawl and the
`eval` line above.

### Price history and rollups

```
shelf backfill --category keyboards             # a synthetic year behind every observed offer, labeled synthetic; safe to repeat
shelf rollup --category keyboards               # retire unseen offers, recompute every rollup (the coordinator does this per cycle, scoped)
shelf rollup --category keyboards --run 12      # only what run 12 touched — exactly what the post-cycle hook does
scripts/explain.sh out/ scripts/bench/*.sql     # EXPLAIN (ANALYZE, BUFFERS) the representative queries into out/
```

`price_observations` is RANGE-partitioned by month; a crawl creates the partitions it needs at
the start of each cycle, the backfill creates the ones it writes. `price_rollups` is a table,
not a materialized view — one statement per grain recomputes exactly the offers and products a
cycle touched, in the same transaction that retires whatever the cycle proved gone, and
`shelf rollup` rebuilds it from nothing. A product's row is over the *cheapest in-stock live
listing at each instant*; `current_in_stock` says whether its current price is buyable, `synthetic_365d`
how much of the year is backfill. `data/benchmarks/m4/` holds the plans before and after,
the sizes, and the covering index that was measured and declined.

A few more queries for `make psql`:

```sql
select source, count(*) from price_observations group by 1;
select p.canonical_name, r.current_price_cents, r.list_price_cents, r.percentile_365d, r.sale_days_365d
  from price_rollups r join products p on p.id = r.product_id where r.current_in_stock order by r.percentile_365d limit 10;
select count(*) filter (where retired_at is not null) as retired, count(*) from offers;
```

### The deal signal

```
shelf signal --category keyboards                  # buy / wait / neutral for every product, from its rollup row (the coordinator does this per cycle, scoped)
shelf eval backtest --category keyboards --out data/benchmarks/m5/keyboards-backtest.txt
shelf eval backtest --category keyboards --horizon 7 --tolerance 0.05   # judge over a week, count only 5% drops
```

The rule (`signal/DealRule`) is a handful of readable tests over the product's `price_rollups`
row, each leaving a reason code: `buy` when today's price is a sale (at or below 90% of the
year's list price) and a good one for this product (at or within 5% of the year's low, or in
its cheapest fifth); `wait` when it is not a sale, or is one that half the year beat, and the
product does go on sale; no call when there is nothing in stock, fewer than thirty points
behind the price, or a sale that is merely middling for its product. Every call also says
`MOSTLY_SYNTHETIC` when it is.

The backtest is what makes the rule a claim rather than a guess. For every day of the
category's history it recomputes every product's rollup row *as of the end of that day* — the
same SQL that writes `price_rollups`, with a `select` in place of the upsert, so the stored row
(which knows the future) is never read — asks the rule what it would have said, and judges the
call by whether a lower price came within the horizon, beside "always buy" and "buy below
median". `data/benchmarks/m5/keyboards-backtest.txt` is the committed report; its first lines
say how much of the history behind it is synthetic (today: all of it).

```sql
select s.signal, s.reason_codes, p.canonical_name, r.current_price_cents, r.list_price_cents, round(r.percentile_365d::numeric, 2)
  from deal_signals s join products p on p.id = s.product_id join price_rollups r on r.product_id = p.id order by s.signal, p.id;
```

## Configuration

Environment variables, with the defaults matching `docker-compose.yml`:

| Variable | Default | Used by |
|---|---|---|
| `SHELF_DB_URL` | `jdbc:postgresql://localhost:5432/shelf` | app, Gradle Flyway task, CI |
| `SHELF_DB_USER` | `shelf` | as above |
| `SHELF_DB_PASSWORD` | `shelf` | as above |
| `SHELF_CATEGORIES_DIR` | `categories` | category config loading |
| `SHELF_RAW_DIR` | `data/raw` | stored response bodies |
| `SHELF_CRAWLER_CONTACT` | the project's GitHub URL | the crawler's `User-Agent` |

No credentials are ever read from a config file — a retailer that needs an API key names the
environment variable that holds it.

## Layout

```
categories/           per-category config (spec schema, retailers, seed products)
docs/                 Spec, Design Decisions, Sessions, Architecture, Concepts, benchmarks/
scripts/              throughput.sh — the 1-vs-N worker benchmark; explain.sh + bench/ — the M4 query plans
src/main/java/com/achen/shelf/
  cli/                the `shelf` CLI (picocli): crawl, migrate, coordinator, worker, resolve, review, rollup, backfill, signal, eval
  config/             config loading + validation
  db/                 HikariCP pool + thin JDBC query layer (no ORM) + the work queue
  crawl/              fetcher, robots, rate limiting, per-retailer parsers, the per-page pipeline
  crawl/cluster/      coordinator (leader election, cycles, reaping) + worker pool
  resolve/            entity resolution: blocking, scoring, the review queue, the eval (M3)
  rollup/             the post-cycle pass: offer retirement + price rollups (M4)
  backfill/           the labeled synthetic history (M4)
  signal/             the buy/wait/neutral rule, the per-cycle signal pass, the backtest (M5)
  api/                Javalin query API    (M6)
src/main/resources/db/migration/   Flyway SQL migrations
src/test/                          JUnit 5, a fixture HTTP server, golden-file fixtures
data/labels/          hand-labeled resolution pairs + the committed eval report
data/benchmarks/m4/   EXPLAIN ANALYZE plans and sizes, before and after rollups
data/benchmarks/m5/   the committed backtest report
data/raw/             fetched response bodies (gitignored)
Dockerfile            one image, one `shelf` subcommand per compose service
docker-compose.yml    postgres:16 + migrate + coordinator (×2) + worker (scalable); api in M6
```

## Crawler conduct

This is a portfolio project, not a commercial service, and it is built to be a good citizen:
official or public structured endpoints wherever they exist, `robots.txt` fetched, cached and
obeyed, a real `User-Agent` with a contact point, a per-domain rate limit of one request every
few seconds, responses cached on disk, and no circumvention of any anti-bot measure. Retailers
that ask not to be crawled are left out — several were, during the M0 survey (see
`docs/Sessions.md`).

Any price data this project reports as observed was observed. The synthetic history M4 adds
for depth is labelled `synthetic` in `price_observations.source` from the moment it is written,
counted separately in every rollup, and never mixed into a number reported as observed.

## Planning docs

`docs/Spec.md` and `docs/Design Decisions.md` are copies; the source of truth lives in the
Obsidian vault (`Cornell/Shelf/`). Keep them in sync when planning changes.
