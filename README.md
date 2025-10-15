# Shelf

Price & deal intelligence for enthusiast product categories. Pick a category, set a budget and
spec filters, get a ranked shortlist and a **buy-now / wait** signal grounded in real price
history.

> **This is a skeleton.** The directory layout, build files, first migration, and category
> config are first-cut scaffolding with `TODO(M0)` markers. Milestone **M0** makes the build,
> CI, and `make up` actually work; **M1** implements the single-threaded crawl. See
> `docs/Spec.md` §7 for the milestone plan and `CLAUDE.md` for the working agreement.

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
src/test/                          JUnit 5 + golden-file fixtures
data/raw/             fetched response bodies (gitignored)
docker-compose.yml    postgres:16 (coordinator/worker/api services added later)
```

## Prereqs

- JDK 21
- Docker + Docker Compose
- Gradle is used via the wrapper — **`gradle wrapper --gradle-version 8.10` must be run once**
  to generate `gradlew` + `gradle/wrapper/gradle-wrapper.jar` (M0).

## Quickstart (once M0 lands)

```
make up        # docker compose up -d postgres
make migrate   # flyway migrate
make test      # ./gradlew check
make down
```

## Planning docs

`docs/Spec.md` and `docs/Design Decisions.md` are copies; the source of truth lives in the
Obsidian vault (`Cornell/Shelf/`). Keep them in sync when planning changes.
