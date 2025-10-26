# Shelf — one image, one subcommand per service (see docker-compose.yml).
#
# Two stages: the build stage has a JDK and runs Gradle's `installDist` (compile + a start script
# under build/install/shelf); the runtime stage is a plain JRE — smaller, and nothing in it can
# recompile anything. Tests are not run here: CI runs them, and a `docker compose build` should
# not need Docker-in-Docker for Testcontainers.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Wrapper + build files first, so the dependency download is a cached layer when only source
# changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies --quiet > /dev/null 2>&1 || true
COPY src ./src
COPY config ./config
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/install/shelf /app
COPY categories /app/categories
ENV SHELF_CATEGORIES_DIR=/app/categories \
    SHELF_RAW_DIR=/data/raw
VOLUME ["/data/raw"]
ENTRYPOINT ["/app/bin/shelf"]
CMD ["--help"]
