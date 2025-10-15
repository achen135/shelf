# Shelf — dev tasks.
#
# Everything here works from a fresh clone with only a JDK 21 and Docker installed; Gradle
# arrives via the committed wrapper.

.PHONY: up down migrate test lint fmt crawl psql clean wrapper

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

## one crawl cycle over the keyboards seed set (M1)
crawl:
	./gradlew run --args="crawl --category keyboards --once"

## psql shell against the local DB
psql:
	docker compose exec postgres psql -U shelf -d shelf

clean:
	./gradlew clean
	rm -rf data/raw/*

## regenerate the Gradle wrapper (rarely needed; the wrapper is committed)
wrapper:
	gradle wrapper --gradle-version 8.10
