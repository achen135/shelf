# Shelf — dev tasks.
#
# Everything here works from a fresh clone with only a JDK 21 and Docker installed; Gradle
# arrives via the committed wrapper.

.PHONY: up down migrate test lint fmt crawl ingest cluster bench psql clean wrapper

## bring up local infra (postgres on :5432)
up:
	docker compose up -d postgres
	@echo "waiting for postgres to report healthy..."
	@until [ "$$(docker compose ps --format '{{.Health}}' postgres)" = "healthy" ]; do sleep 1; done
	@echo "postgres ready"

## stop local infra (keeps the data volume)
down:
	docker compose down

## run Flyway migrations against the local DB
migrate:
	./gradlew flywayMigrate flywayInfo

## compile + static analysis + unit/integration tests (needs Docker: Testcontainers)
test:
	./gradlew check

## static analysis only
lint:
	./gradlew spotbugsMain spotlessCheck

## apply the code formatter
fmt:
	./gradlew spotlessApply

## one crawl cycle over the keyboards seed set, single process (M1)
crawl:
	./gradlew run --args="crawl --category keyboards --once"

## community ingestion (M8): needs YOUTUBE_API_KEY (and, once approved, REDDIT_CLIENT_ID/SECRET) in the env
ingest:
	./gradlew run --args="ingest --category keyboards"

## the distributed crawler in compose: postgres + migrate + 2 coordinators + N workers (M2)
cluster:
	docker compose up --build --scale worker=$${WORKERS:-4}

## throughput at 1 and 4 local worker processes against the live retailers (M2)
bench:
	scripts/throughput.sh 1
	scripts/throughput.sh 4

## psql shell against the local DB
psql:
	docker compose exec postgres psql -U shelf -d shelf

clean:
	./gradlew clean
	rm -rf data/raw/*

## regenerate the Gradle wrapper (rarely needed; the wrapper is committed)
wrapper:
	gradle wrapper --gradle-version 8.10
