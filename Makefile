# Shelf — dev tasks. TODO(M0): verify each target once the Gradle build + compose exist.

.PHONY: up down migrate test lint fmt crawl clean wrapper

## bring up local infra
up:
	docker compose up -d postgres

## stop local infra
down:
	docker compose down

## run Flyway migrations against the local DB
migrate:
	./gradlew flywayMigrate

## compile + static analysis + unit tests
test:
	./gradlew check

## static analysis only
lint:
	./gradlew spotbugsMain

## one crawl cycle over the keyboards seed set (M1)
crawl:
	./gradlew run --args="crawl --category keyboards --once"

## generate the Gradle wrapper (run once, then commit gradlew + gradle-wrapper.jar)
wrapper:
	gradle wrapper --gradle-version 8.10

clean:
	./gradlew clean
	rm -rf data/raw/*
