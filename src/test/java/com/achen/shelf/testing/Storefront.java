package com.achen.shelf.testing;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.ObservationDao.Source;
import com.achen.shelf.db.OfferDao;
import com.achen.shelf.rollup.RollupRun;
import com.achen.shelf.signal.DealRule;
import com.achen.shelf.signal.SignalRun;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * A small catalog with prices, specs, rollups and signals — what the query API reads. Built on
 * {@link PriceHistory} through the real DAOs and the real passes, so a test's rows are what a
 * crawl, a rollup pass and a signal pass would have left behind.
 *
 * <p>Two products, as of {@link #AS_OF}:
 *
 * <ul>
 *   <li><b>Keychron Q1 Pro</b> (summary spec {@code hot_swap: true}), three live listings: a 75% at
 *       $150 in stock (its year: $150 with one $120 sale), a 65% at $99 in stock (flat), and a 75%
 *       at $80 <em>out of stock</em>; plus a retired 75% at $10 that must never appear.
 *   <li><b>Ducky One 2 SF</b>, one listing at $50 in stock with no spec, synthetic history.
 * </ul>
 */
public final class Storefront {

  public static final Instant AS_OF = Instant.parse("2030-06-01T12:00:00Z");

  public final CategoryConfig category;
  public final long q1pro;
  public final long ducky;
  public final long q1pro75;
  public final long q1pro65;
  public final long q1pro75oos;
  public final long q1proRetired;
  public final long duckyOffer;

  private final Database db;
  private final PriceHistory history;

  public static Instant at(int daysAgo) {
    return AS_OF.minus(daysAgo, ChronoUnit.DAYS);
  }

  public Storefront(Database db) throws SQLException {
    this.db = db;
    this.history = new PriceHistory(db);
    String yaml =
        FixtureServer.Fixtures.read("categories/fixture-server.yaml.template")
            .replace("__PORT__", "1")
            .replace("name: json_store", "name: " + PriceHistory.RETAILER);
    category =
        new CategoryConfigLoader()
            .load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "keyboards");

    q1pro = history.product("Keychron", "Q1 Pro");
    spec(q1pro, "{\"hot_swap\": true}");
    ducky = history.product("Ducky", "One 2 SF");

    q1pro75 = offer("Keychron Q1 Pro 75% — Carbon Black", "{\"layout_size\": \"75\"}");
    q1pro65 = offer("Keychron Q1 Pro 65% — Navy", "{\"layout_size\": \"65\"}");
    q1pro75oos = offer("Keychron Q1 Pro 75% — Silver", "{\"layout_size\": \"75\"}");
    q1proRetired = offer("Keychron Q1 Pro 75% — Old", "{\"layout_size\": \"75\"}");
    duckyOffer = offer("Ducky One 2 SF", "{}");
    for (long o : new long[] {q1pro75, q1pro65, q1pro75oos, q1proRetired}) {
      history.link(o, q1pro, "auto");
    }
    history.link(duckyOffer, ducky, "auto");

    for (int k = 300; k >= 0; k -= 10) {
      boolean sale = k == 100 || k == 90;
      history.observe(q1pro75, at(k), sale ? 12000 : 15000, true, Source.OBSERVED);
      history.observe(q1pro65, at(k), 9900, true, Source.OBSERVED);
      history.observe(q1pro75oos, at(k), 8000, k > 20, Source.OBSERVED);
      history.observe(duckyOffer, at(k), 5000, true, Source.SYNTHETIC);
    }
    history.observe(q1proRetired, at(300), 1000, true, Source.OBSERVED);
    for (long o : new long[] {q1pro75, q1pro65, q1pro75oos, duckyOffer}) {
      history.lastSeen(o, at(0));
    }
    history.lastSeen(q1proRetired, at(300));
    try (Connection c = db.connection();
        PreparedStatement ps =
            c.prepareStatement("update offers set retired_at = ? where id = ?")) {
      ps.setTimestamp(1, java.sql.Timestamp.from(at(200)));
      ps.setLong(2, q1proRetired);
      ps.executeUpdate();
    }
    new RollupRun(db, RollupRun.Settings.defaults(), Clock.fixed(AS_OF, ZoneOffset.UTC))
        .run(category, new RollupRun.Scope.All());
    new SignalRun(db, DealRule.defaults()).run(category);
  }

  private long offer(String title, String spec) throws SQLException {
    return new OfferDao(db)
        .upsert(
            new OfferDao.Listing(
                PriceHistory.RETAILER,
                "https://" + PriceHistory.RETAILER + ".test/p/" + title.hashCode(),
                title,
                null,
                "USD",
                "Keychron",
                "keychron",
                spec));
  }

  private void spec(long productId, String json) throws SQLException {
    try (Connection c = db.connection();
        PreparedStatement ps =
            c.prepareStatement("update products set spec = ?::jsonb where id = ?")) {
      ps.setString(1, json);
      ps.setLong(2, productId);
      ps.executeUpdate();
    }
  }
}
