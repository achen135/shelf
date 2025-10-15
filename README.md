# Shelf

Price & deal intelligence for enthusiast product categories. Pick a category, set a budget and
spec filters, get a ranked shortlist and a **buy-now / wait** signal grounded in real price
history.

A *category* is a config file — its spec schema, its retailers and how to fetch each, its seed
products — so onboarding one is configuration plus parsers, not new pipeline code. The crawler
never touches the open web: it visits the paths a category file names, and nothing else.

> **Status: M0 (scaffold + category config) complete.** The build, migrations, config loading
> and test infrastructure work end to end. M1 adds the single-threaded crawl. See
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
`build.gradle.kts` if your Docker socket lives somewhere unusual.

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
src/main/java/com/achen/shelf/
  cli/                the `shelf` CLI (picocli)
  config/             config loading + validation
  db/                 HikariCP pool + thin JDBC query layer (no ORM)
  crawl/              fetcher, robots, rate limiting, per-retailer parsers
  resolve/            entity resolution  (M3)
  rollup/             price rollups        (M4)
  signal/             buy/wait signal + backtest (M5)
  api/                Javalin query API    (M6)
src/main/resources/db/migration/   Flyway SQL migrations
src/test/                          JUnit 5, a fixture HTTP server, golden-file fixtures
data/raw/             fetched response bodies (gitignored)
docker-compose.yml    postgres:16 (coordinator/worker/api services added later)
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
