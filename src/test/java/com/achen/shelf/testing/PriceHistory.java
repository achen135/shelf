package com.achen.shelf.testing;

import com.achen.shelf.db.Database;
import com.achen.shelf.db.ObservationDao;
import com.achen.shelf.db.OfferDao;
import com.achen.shelf.db.PartitionDao;
import com.achen.shelf.db.ProductDao;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Builds price histories by hand for the rollup tests: products, offers, links, crawl runs and
 * observations at chosen instants, written through the real DAOs so a test's series is exactly what
 * a crawl would have produced. Partitions are created for every instant written.
 */
public final class PriceHistory {

  public static final String CATEGORY = "keyboards";
  public static final String RETAILER = "shop";

  private final Database db;
  private final OfferDao offers;
  private final ObservationDao observations;
  private final PartitionDao partitions;
  private int urls;

  public PriceHistory(Database db) {
    this.db = db;
    this.offers = new OfferDao(db);
    this.observations = new ObservationDao(db);
    this.partitions = new PartitionDao(db);
  }

  /** A catalog product. */
  public long product(String brand, String model) throws SQLException {
    return new ProductDao(db)
        .upsert(
            CATEGORY,
            brand,
            model,
            brand.toLowerCase(java.util.Locale.ROOT),
            model.toLowerCase(java.util.Locale.ROOT),
            brand + " " + model,
            "{}");
  }

  /** An offer at {@link #RETAILER} with a fresh URL. */
  public long offer() throws SQLException {
    return offer(RETAILER);
  }

  /** An offer at a retailer with a fresh URL. */
  public long offer(String retailer) throws SQLException {
    urls++;
    return offers.upsert(
        new OfferDao.Listing(
            retailer,
            "https://" + retailer + ".test/p/" + urls,
            "Listing " + urls,
            null,
            "USD",
            "Brand",
            "brand",
            "{}"));
  }

  /** Links an offer to a product with the given status ({@code auto} or {@code reviewed}). */
  public void link(long offerId, long productId, String status) throws SQLException {
    try (Connection c = db.connection();
        PreparedStatement ps =
            c.prepareStatement(
                "update offers set product_id = ?, resolution_status = ?, resolution_score = 1"
                    + " where id = ?")) {
      ps.setLong(1, productId);
      ps.setString(2, status);
      ps.setLong(3, offerId);
      ps.executeUpdate();
    }
  }

  /** Sets an offer's {@code last_seen} directly — the retirement rule's input. */
  public void lastSeen(long offerId, Instant at) throws SQLException {
    try (Connection c = db.connection();
        PreparedStatement ps = c.prepareStatement("update offers set last_seen = ? where id = ?")) {
      ps.setTimestamp(1, Timestamp.from(at));
      ps.setLong(2, offerId);
      ps.executeUpdate();
    }
  }

  /** A finished crawl run that started at {@code startedAt} and took a minute. */
  public long finishedRun(Instant startedAt) throws SQLException {
    return run(startedAt, startedAt.plus(1, ChronoUnit.MINUTES), "crawl");
  }

  /** A crawl run that is still open. */
  public long openRun(Instant startedAt) throws SQLException {
    return run(startedAt, null, "crawl");
  }

  /** A finished run of the given kind. */
  public long run(Instant startedAt, Instant finishedAt, String kind) throws SQLException {
    partitions.ensurePartition(startedAt);
    try (Connection c = db.connection();
        PreparedStatement ps =
            c.prepareStatement(
                "insert into crawl_runs (category, kind, started_at, finished_at, worker_count)"
                    + " values (?, ?, ?, ?, 1) returning id")) {
      ps.setString(1, CATEGORY);
      ps.setString(2, kind);
      ps.setTimestamp(3, Timestamp.from(startedAt));
      ps.setTimestamp(4, finishedAt == null ? null : Timestamp.from(finishedAt));
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** One observed, in-stock price at an instant, under a run of its own. */
  public void observe(long offerId, Instant at, int priceCents) throws SQLException {
    observe(offerId, at, priceCents, true, ObservationDao.Source.OBSERVED);
  }

  /** One price at an instant, under a run of its own. */
  public void observe(
      long offerId, Instant at, int priceCents, boolean inStock, ObservationDao.Source source)
      throws SQLException {
    long runId = finishedRun(at);
    observe(offerId, at, priceCents, inStock, source, runId);
  }

  /** One price at an instant, under the given run. */
  public void observe(
      long offerId,
      Instant at,
      int priceCents,
      boolean inStock,
      ObservationDao.Source source,
      long runId)
      throws SQLException {
    partitions.ensurePartition(at);
    observations.record(offerId, at, priceCents, 0, inStock, runId, source);
  }
}
