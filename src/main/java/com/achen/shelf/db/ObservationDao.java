package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/** Writes into the partitioned {@code price_observations} table. */
public final class ObservationDao {

  /** How an observation was obtained. Anything not literally observed must say so. */
  public enum Source {
    OBSERVED("observed"),
    SYNTHETIC("synthetic");

    private final String dbValue;

    Source(String dbValue) {
      this.dbValue = dbValue;
    }

    public String dbValue() {
      return dbValue;
    }
  }

  /**
   * Where a set of retailers' observations start and end, by source. {@code firstObserved} / {@code
   * lastSynthetic} are null when there is nothing of that source.
   */
  public record Span(Instant first, Instant last, Instant firstObserved, Instant lastSynthetic) {}

  private final Database db;

  public ObservationDao(Database db) {
    this.db = db;
  }

  /** The span of every observation at the given retailers; empty when there are none. */
  public static Optional<Span> span(Connection c, Collection<String> retailers)
      throws SQLException {
    String sql =
        """
        select min(po.observed_at), max(po.observed_at),
               min(po.observed_at) filter (where po.source = 'observed'),
               max(po.observed_at) filter (where po.source = 'synthetic')
        from price_observations po
        join offers o on o.id = po.offer_id
        where o.retailer = any (?)
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setArray(1, c.createArrayOf("text", retailers.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Timestamp first = rs.getTimestamp(1);
        if (first == null) {
          return Optional.empty();
        }
        return Optional.of(
            new Span(
                first.toInstant(),
                rs.getTimestamp(2).toInstant(),
                instantOrNull(rs.getTimestamp(3)),
                instantOrNull(rs.getTimestamp(4))));
      }
    }
  }

  private static Instant instantOrNull(Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  /**
   * Records one observation, ignoring a repeat of one already written.
   *
   * <p>{@code on conflict do nothing} against PK {@code (offer_id, observed_at)} is what makes a
   * crawl idempotent: because every observation in a run carries the run's start instant,
   * re-running a cycle — or M2 repicking a task whose lease expired after it had already written —
   * cannot produce a second row for the same offer.
   *
   * @return true if a row was inserted, false if this observation was already present
   */
  public boolean record(
      long offerId,
      Instant observedAt,
      int priceCents,
      int shippingCents,
      boolean inStock,
      long crawlRunId,
      Source source)
      throws SQLException {
    try (Connection c = db.connection()) {
      return record(c, offerId, observedAt, priceCents, shippingCents, inStock, crawlRunId, source);
    }
  }

  /** As above, on a caller-owned connection, for the worker's single-transaction write. */
  public boolean record(
      Connection c,
      long offerId,
      Instant observedAt,
      int priceCents,
      int shippingCents,
      boolean inStock,
      long crawlRunId,
      Source source)
      throws SQLException {
    String sql =
        """
        insert into price_observations
          (offer_id, observed_at, price_cents, shipping_cents, in_stock, crawl_run_id, source)
        values (?, ?, ?, ?, ?, ?, ?)
        on conflict (offer_id, observed_at) do nothing
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, offerId);
      ps.setTimestamp(2, Timestamp.from(observedAt));
      ps.setInt(3, priceCents);
      ps.setInt(4, shippingCents);
      ps.setBoolean(5, inStock);
      ps.setLong(6, crawlRunId);
      ps.setString(7, source.dbValue());
      return ps.executeUpdate() > 0;
    }
  }
}
