// Shelf build. SKELETON — TODO(M0): confirm every plugin/dependency version resolves,
// wire SpotBugs + Error Prone, and get `./gradlew check` + `flywayMigrate` green in CI.

plugins {
    application
    id("com.diffplug.spotless") version "6.25.0"
    id("com.github.spotbugs") version "6.0.26"
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

    // --- test ---
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.0")
    testImplementation("org.testcontainers:postgresql:1.20.4")       // DB-backed tests
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
}

application {
    // single entrypoint; subcommands: crawl | coordinator | worker | api  (see cli/)
    mainClass = "com.achen.shelf.cli.Main"
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

tasks.withType<JavaCompile> {
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    // TODO(M0): add Error Prone (net.ltgt.errorprone plugin) once the build is green.
}

spotless {
    java {
        googleJavaFormat("1.24.0")
        target("src/**/*.java")
    }
}

// Flyway config for `./gradlew flywayMigrate`. Reads env so CI and local share it.
// TODO(M0): confirm these env var names match docker-compose.yml + CI.
flyway {
    url = System.getenv("SHELF_DB_URL") ?: "jdbc:postgresql://localhost:5432/shelf"
    user = System.getenv("SHELF_DB_USER") ?: "shelf"
    password = System.getenv("SHELF_DB_PASSWORD") ?: "shelf"
    locations = arrayOf("classpath:db/migration")
}
