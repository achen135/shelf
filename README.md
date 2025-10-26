# Shelf

Price & deal intelligence for enthusiast product categories. Pick a category, set a budget and
spec filters, get a ranked shortlist and a **buy-now / wait** signal grounded in real price
history.

A *category* is a config file — its spec schema, its retailers and how to fetch each, its seed
products — so onboarding one is configuration plus parsers, not new pipeline code. The crawler
never touches the open web: it visits the paths a category file names, and nothing else.

> **Status: M2 complete.** The crawler is distributed: a leader-elected coordinator enqueues
> cycles into a Postgres work queue (`FOR UPDATE SKIP LOCKED`, 15 s leases heartbeated every
> 5 s, exponential retry, dead-letter) and a pool of workers claims pages from it, sharing one
> per-domain politeness budget. A cycle over five live retailers records **7,556 offers and
> 7,556 price observations across 10 pages with no errors** at 1 or 4 workers; SIGKILLing a
> worker mid-page is recovered in **13.0 s** with zero duplicate observations, and SIGKILLing the
> leader fails over in **4.9 s** — both automated tests, numbers in `docs/benchmarks/`. See
> `docs/Spec.md` §7 for the milestone plan, `docs/Sessions.md` for what each one actually did,
> and `CLAUDE.md` for the working agreement.

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

It prints a per-retailer summary — pages, offers, price points, how many linked to a seeded
product, errors — and writes into the database you just migrated. Fetched bodies land under
`data/raw/<run>/`, content-addressed, and every attempt gets a `raw_fetches` row.

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
scripts/              throughput.sh — the 1-vs-N worker benchmark
src/main/java/com/achen/shelf/
  cli/                the `shelf` CLI (picocli): crawl, migrate, coordinator, worker
  config/             config loading + validation
  db/                 HikariCP pool + thin JDBC query layer (no ORM) + the work queue
  crawl/              fetcher, robots, rate limiting, per-retailer parsers, the per-page pipeline
  crawl/cluster/      coordinator (leader election, cycles, reaping) + worker pool
  resolve/            entity resolution  (M3)
  rollup/             price rollups        (M4)
  signal/             buy/wait signal + backtest (M5)
  api/                Javalin query API    (M6)
src/main/resources/db/migration/   Flyway SQL migrations
src/test/                          JUnit 5, a fixture HTTP server, golden-file fixtures
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

Any price data this project reports as observed was observed. Synthetic history — which M4
adds for depth — is labelled `synthetic` in `price_observations.source` and stays labelled
everywhere it is used.

## Planning docs

`docs/Spec.md` and `docs/Design Decisions.md` are copies; the source of truth lives in the
Obsidian vault (`Cornell/Shelf/`). Keep them in sync when planning changes.
