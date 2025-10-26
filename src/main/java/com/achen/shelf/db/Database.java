package com.achen.shelf.db;

import com.achen.shelf.config.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * The connection pool, and the one place JDBC settings are decided.
 *
 * <p>Sessions are pinned to UTC. Postgres resolves {@code timestamptz} literals and {@code
 * date_trunc} against the session's TimeZone, so a pool that inherited each machine's local zone
 * would cut monthly partitions at different instants on different hosts — exactly the bug {@code
 * ensure_price_partition} was written to avoid. Pinning it here means every writer agrees on what
 * "September" means.
 */
public final class Database implements AutoCloseable {

  private final HikariDataSource dataSource;

  private Database(HikariDataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** Opens a pool against the configured database. */
  public static Database open(AppConfig config, int maxPoolSize) {
    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(config.dbUrl());
    hikari.setUsername(config.dbUser());
    hikari.setPassword(config.dbPassword());
    hikari.setMaximumPoolSize(maxPoolSize);
    hikari.setPoolName("shelf");
    hikari.setConnectionInitSql("set time zone 'UTC'");
    return new Database(new HikariDataSource(hikari));
  }

  /** Wraps an existing DataSource-shaped pool; used by tests against a container. */
  public static Database open(String jdbcUrl, String user, String password, int maxPoolSize) {
    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(jdbcUrl);
    hikari.setUsername(user);
    hikari.setPassword(password);
    hikari.setMaximumPoolSize(maxPoolSize);
    hikari.setPoolName("shelf-test");
    hikari.setConnectionInitSql("set time zone 'UTC'");
    return new Database(new HikariDataSource(hikari));
  }

  public DataSource dataSource() {
    return dataSource;
  }

  public Connection connection() throws SQLException {
    return dataSource.getConnection();
  }

  /** Work done on one connection inside one transaction. */
  @FunctionalInterface
  public interface Transactional<T> {
    T apply(Connection c) throws SQLException;
  }

  /**
   * Runs {@code body} in a transaction on a pooled connection: committed if it returns, rolled back
   * if it throws. Autocommit is restored before the connection goes back to the pool.
   */
  public <T> T transaction(Transactional<T> body) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        T result = body.apply(c);
        c.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    }
  }

  @Override
  public void close() {
    dataSource.close();
  }
}
