package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The shared politeness bucket: {@code domain_rate_limits}, one row per registrable domain.
 *
 * <p>Reserving a slot is a single upsert. The row lock Postgres takes for {@code on conflict do
 * update} is what serialises N workers hitting one domain: the second arrives, waits for the first
 * to commit, re-reads the row the first just advanced, and is handed the slot after it. Nothing in
 * the JVM coordinates this, which is the point — the workers share a database, not a process.
 *
 * <p>Both sides of the wait are database time ({@code now()} on the same statement), so a worker
 * whose clock drifts is still handed a correct wait in <em>its</em> terms.
 */
public final class RateLimitDao {

  /**
   * A reserved slot: the database instant it may be used, and how long from now that is.
   *
   * @param slotAt when the caller may make its request, in database time
   * @param delay how long the caller must sleep first; zero when the slot is already due
   */
  public record Reservation(Instant slotAt, Duration delay) {}

  private final Database db;

  public RateLimitDao(Database db) {
    this.db = db;
  }

  /** Reserves the next slot for a domain, spaced {@code interval} after the previous one. */
  public Reservation reserve(String domain, Duration interval) throws SQLException {
    String sql =
        """
        insert into domain_rate_limits as d (domain, next_allowed_at)
        values (?, now() + make_interval(secs => ?))
        on conflict (domain) do update
          set next_allowed_at = greatest(d.next_allowed_at, now()) + make_interval(secs => ?)
        returning next_allowed_at - make_interval(secs => ?) as slot_at, now() as db_now
        """;
    double secs = interval.toNanos() / 1_000_000_000.0;
    try (Connection c = db.connection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, domain);
      ps.setDouble(2, secs);
      ps.setDouble(3, secs);
      ps.setDouble(4, secs);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Instant slotAt = rs.getTimestamp(1).toInstant();
        Instant dbNow = rs.getTimestamp(2).toInstant();
        Duration wait = Duration.between(dbNow, slotAt);
        return new Reservation(slotAt, wait.isNegative() ? Duration.ZERO : wait);
      }
    }
  }

  /** The instant the domain may next be requested, if it has ever been reserved. */
  public Optional<Instant> nextAllowedAt(String domain) throws SQLException {
    try (Connection c = db.connection();
        PreparedStatement ps =
            c.prepareStatement("select next_allowed_at from domain_rate_limits where domain = ?")) {
      ps.setString(1, domain);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        Timestamp ts = rs.getTimestamp(1);
        return Optional.of(ts.toInstant());
      }
    }
  }
}
