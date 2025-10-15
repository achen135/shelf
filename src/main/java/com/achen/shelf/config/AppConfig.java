package com.achen.shelf.config;

import java.nio.file.Path;

/**
 * Process-level settings, read from the environment.
 *
 * <p>The {@code SHELF_DB_*} names are the contract shared by docker-compose.yml, the Gradle Flyway
 * task, CI and this app — changing one means changing all four.
 *
 * @param dbUrl JDBC url
 * @param dbUser database user
 * @param dbPassword database password
 * @param categoriesDir directory holding {@code <category>.yaml}
 * @param rawDir directory fetched bodies are written under
 * @param userAgent the User-Agent every request carries
 */
public record AppConfig(
    String dbUrl,
    String dbUser,
    String dbPassword,
    Path categoriesDir,
    Path rawDir,
    String userAgent) {

  private static final String DEFAULT_CONTACT = "https://github.com/achen135/shelf";

  /** Reads the environment, falling back to the local docker-compose defaults. */
  public static AppConfig fromEnv() {
    return new AppConfig(
        env("SHELF_DB_URL", "jdbc:postgresql://localhost:5432/shelf"),
        env("SHELF_DB_USER", "shelf"),
        env("SHELF_DB_PASSWORD", "shelf"),
        Path.of(env("SHELF_CATEGORIES_DIR", "categories")),
        Path.of(env("SHELF_RAW_DIR", "data/raw")),
        defaultUserAgent());
  }

  /**
   * A real, identifiable User-Agent with a contact point, as docs/Design Decisions.md ("API-first,
   * polite crawler") requires. Operators can substitute their own contact with
   * SHELF_CRAWLER_CONTACT.
   */
  private static String defaultUserAgent() {
    String contact = env("SHELF_CRAWLER_CONTACT", DEFAULT_CONTACT);
    return "ShelfBot/0.1 (+" + contact + ")";
  }

  private static String env(String name, String fallback) {
    String v = System.getenv(name);
    return v == null || v.isBlank() ? fallback : v;
  }
}
