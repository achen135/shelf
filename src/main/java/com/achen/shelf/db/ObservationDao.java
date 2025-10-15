package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

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

  private final Database db;

  public ObservationDao(Database db) {
    this.db = db;
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
    String sql =
        """
        insert into price_observations
          (offer_id, observed_at, price_cents, shipping_cents, in_stock, crawl_run_id, source)
        values (?, ?, ?, ?, ?, ?, ?)
        on conflict (offer_id, observed_at) do nothing
        """;
    try (Connection c = db.connection();
        PreparedStatement ps = c.prepareStatement(sql)) {
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
