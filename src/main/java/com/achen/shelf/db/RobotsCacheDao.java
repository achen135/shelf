package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Caches {@code robots.txt} bodies per domain.
 *
 * <p>In Postgres rather than in memory because M2's workers are separate processes: one fetch of a
 * retailer's robots.txt should serve all of them, and re-fetching it per worker per run would be
 * exactly the kind of avoidable traffic the politeness rules exist to prevent.
 */
public final class RobotsCacheDao {

  /** A cached robots.txt body and when it was fetched. */
  public record Entry(String body, Instant fetchedAt) {}

  private final Database db;

  public RobotsCacheDao(Database db) {
    this.db = db;
  }

  /** Returns the cached body for a domain if it is younger than {@code maxAge}. */
  public Optional<Entry> get(String domain, Duration maxAge) throws SQLException {
    String sql = "select body, fetched_at from robots_cache where domain = ?";
    try (Connection c = db.connection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, domain);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        Instant fetchedAt = rs.getTimestamp(2).toInstant();
        if (fetchedAt.plus(maxAge).isBefore(Instant.now())) {
          return Optional.empty();
        }
        return Optional.of(new Entry(rs.getString(1), fetchedAt));
      }
    }
  }

  /** Stores (or refreshes) a domain's robots.txt. */
  public void put(String domain, String body) throws SQLException {
    String sql =
        """
        insert into robots_cache (domain, body, fetched_at) values (?, ?, now())
        on conflict (domain) do update set body = excluded.body, fetched_at = now()
        """;
    try (Connection c = db.connection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, domain);
      ps.setString(2, body);
      ps.executeUpdate();
    }
  }
}
