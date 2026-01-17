// Shelf build.
//
// `./gradlew check`        compile (+ Error Prone / SpotBugs / Spotless) + JUnit 5
// `./gradlew flywayMigrate` apply src/main/resources/db/migration against SHELF_DB_URL
// `./gradlew run --args="crawl --category keyboards --once"`

import net.ltgt.gradle.errorprone.errorprone

// The Flyway Gradle plugin resolves database support from its own classpath, not the
// project's, so the postgres module + JDBC driver have to be declared here as well.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.flywaydb:flyway-database-postgresql:11.1.0")
        classpath("org.postgresql:postgresql:42.7.4")
    }
}

plugins {
    application
    id("com.diffplug.spotless") version "7.0.2"
    id("com.github.spotbugs") version "6.0.26"
    id("net.ltgt.errorprone") version "4.1.0"
    id("org.flywaydb.flyway") version "11.1.0"
}

group = "com.achen"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // --- runtime ---
    implementation("io.javalin:javalin:6.4.0")                       // query API (M6)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2") // java.time in API JSON (M6)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2") // category config
    implementation("org.jsoup:jsoup:1.18.3")                         // HTML parsing (M1)
    implementation("com.zaxxer:HikariCP:6.2.1")                      // connection pool
    implementation("org.postgresql:postgresql:42.7.4")               // JDBC driver
    implementation("org.flywaydb:flyway-core:11.1.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.1.0")
    implementation("info.picocli:picocli:4.7.6")                     // CLI
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")
    implementation("com.github.crawler-commons:crawler-commons:1.4")  // robots.txt (M1)
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.12")

    // --- static analysis ---
    errorprone("com.google.errorprone:error_prone_core:2.36.0")

    // --- test ---
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.0")
    testImplementation("org.testcontainers:postgresql:1.21.3")       // DB-backed tests
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    // single entrypoint; subcommands: crawl | coordinator | worker | api  (see cli/)
    mainClass = "com.achen.shelf.cli.Main"
}

tasks.test {
    useJUnitPlatform()
    // enableAssertions defaults to true — keep it, the crawl code asserts invariants.
    testLogging { events("passed", "skipped", "failed") }

    // --- Testcontainers on a developer laptop -------------------------------------------
    // docker-java (inside Testcontainers) negotiates API version 1.32 by default, which Docker
    // Engine 25+ refuses outright ("client version 1.32 is too old. Minimum supported API
    // version is 1.40"). 1.40 is the floor every engine since 2019 accepts, so pin it rather
    // than depend on whichever Docker the machine happens to run.
    systemProperty("api.version", "1.40")

    // Extra flags for the test JVM. Used to reproduce what a small CI runner does to virtual
    // threads: SHELF_TEST_JVM_ARGS="-Djdk.virtualThreadScheduler.parallelism=1" runs the whole
    // suite on a single carrier thread, where anything that blocks while pinned stalls
    // everything else (see docs/Sessions.md, M2).
    System.getenv("SHELF_TEST_JVM_ARGS")?.let { jvmArgs(it.split(" ")) }

    // Testcontainers looks for /var/run/docker.sock. Docker Desktop on macOS only creates that
    // symlink when "Allow the default Docker socket to be used" is enabled; otherwise the
    // engine is reachable at docker.raw.sock inside the app container. (The socket under
    // ~/.docker/run is a proxy that answers docker-java with a stub, so it is not used here.)
    // The override tells Ryuk which path to mount *inside* the VM, where it is always
    // /var/run/docker.sock. On Linux and in CI neither branch is taken.
    if (System.getenv("DOCKER_HOST") == null && !File("/var/run/docker.sock").exists()) {
        val desktopEngineSocket =
            File(
                System.getProperty("user.home"),
                "Library/Containers/com.docker.docker/Data/docker.raw.sock",
            )
        if (desktopEngineSocket.exists()) {
            environment("DOCKER_HOST", "unix://${desktopEngineSocket.absolutePath}")
            environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    // -Xlint:-processing: picocli-codegen is on the annotation processor path for the CLI's
    // generated help, and javac warns whenever a compilation unit has annotations no processor
    // claims — which is most of them. Every other lint stays on, and warnings are errors.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        // picocli's generated code and Jackson databind reflection trip these.
        disable("StringCaseLocaleUsage")
    }
}

spotbugs {
    // Findings fail the build; the filter lists the handful of patterns that are wrong here
    // and says why, so a new finding cannot be waved through by loosening a global setting.
    excludeFilter = file("config/spotbugs/exclude.xml")
}

spotless {
    java {
        googleJavaFormat("1.24.0")
        target("src/**/*.java")
    }
}

// Flyway config for `./gradlew flywayMigrate`. Reads env so CI and local share it.
// Env var names are the contract between docker-compose.yml, .github/workflows/ci.yml,
// the Makefile and the app itself: SHELF_DB_URL / SHELF_DB_USER / SHELF_DB_PASSWORD.
flyway {
    url = System.getenv("SHELF_DB_URL") ?: "jdbc:postgresql://localhost:5432/shelf"
    user = System.getenv("SHELF_DB_USER") ?: "shelf"
    password = System.getenv("SHELF_DB_PASSWORD") ?: "shelf"
    locations = arrayOf("classpath:db/migration")
}

// The Flyway tasks read `classpath:db/migration`, which resolves to build/resources/main.
// Without this dependency an edited migration is silently skipped in favour of the stale
// copy from the last build — including in CI. Keeping it on the classpath (rather than
// pointing Flyway at src/) means the Gradle task exercises the same location the packaged
// app reads at runtime.
tasks.matching { it.name.startsWith("flyway") }.configureEach {
    dependsOn(tasks.processResources)
}
