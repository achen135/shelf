package com.achen.shelf.testing;

import com.achen.shelf.db.Database;
import com.achen.shelf.db.Migrations;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that need a real Postgres.
 *
 * <p>A real database, not an in-memory stand-in: the things most worth testing here — monthly RANGE
 * partitions, {@code on conflict} idempotency, a plpgsql function — are Postgres features that no
 * substitute would exercise honestly.
 *
 * <p>One container is shared by every DB-backed test class (the Testcontainers "singleton
 * container" pattern) and migrated once. Starting a container per class would multiply a ~2s
 * startup across the suite for no extra coverage; tables are truncated between tests instead, which
 * also keeps each test's assertions about row counts unambiguous.
 */
public abstract class PostgresTestBase {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
          .withDatabaseName("shelf")
          .withUsername("shelf")
          .withPassword("shelf");

  protected static final Database DB;

  static {
    POSTGRES.start();
    Migrations.migrate(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    DB = Database.open(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), 4);
  }

  /** JDBC url of the shared container, for tests that need their own connection. */
  protected static String jdbcUrl() {
    return POSTGRES.getJdbcUrl();
  }

  protected static String dbUser() {
    return POSTGRES.getUsername();
  }

  protected static String dbPassword() {
    return POSTGRES.getPassword();
  }

  @BeforeEach
  void truncateEverything() throws SQLException {
    try (Connection c = DB.connection();
        Statement s = c.createStatement()) {
      s.execute(
          "truncate raw_mentions, deal_signals, price_rollups, price_observations, raw_fetches,"
              + " resolution_labels,"
              + " offers, products, crawl_tasks, crawl_runs, robots_cache, domain_rate_limits restart"
              + " identity cascade");
    }
  }

  /** Convenience: runs one statement that returns nothing. */
  protected static void execute(String sql) throws SQLException {
    try (Connection c = DB.connection();
        Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  /** Convenience: runs a query that returns a single count. */
  protected static long count(String sql) throws SQLException {
    try (Connection c = DB.connection();
        Statement s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
