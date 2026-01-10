package com.achen.shelf.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.achen.shelf.db.ObservationDao.Source;
import com.achen.shelf.db.RollupDao;
import com.achen.shelf.db.RollupDao.Rollup;
import com.achen.shelf.db.RollupDao.SaleWindow;
import com.achen.shelf.db.RollupDao.Window;
import com.achen.shelf.testing.PostgresTestBase;
import com.achen.shelf.testing.PriceHistory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The rollup statement, checked number by number against a hand-built year of prices.
 *
 * <p>{@code at(k)} is {@code k} days before {@code AS_OF}; a window of {@code n} days holds {@code
 * k < n}. The offer's year, oldest first (S = synthetic, × = out of stock):
 *
 * <pre>
 *  k:   400  364S 200S 100  99  98  70  60  25  20  19  18   5   3×  0   -1
 *  $:   100  100  100   90  90 100 100 100 100  80  80  80 100 100  95  50
 * </pre>
 *
 * The list price is the year's mode ($100); the two runs at or below $90 are the sales.
 */
class RollupDaoTest extends PostgresTestBase {

  private static final Instant AS_OF = Instant.parse("2030-06-01T00:00:00Z");

  private PriceHistory history;
  private long offer;

  private static Instant at(int daysAgo) {
    return AS_OF.minus(daysAgo, ChronoUnit.DAYS);
  }

  @BeforeEach
  void setUp() throws SQLException {
    history = new PriceHistory(DB);
    offer = history.offer();
    history.observe(offer, at(400), 10000);
    history.observe(offer, at(364), 10000, true, Source.SYNTHETIC);
    history.observe(offer, at(200), 10000, true, Source.SYNTHETIC);
    history.observe(offer, at(100), 9000);
    history.observe(offer, at(99), 9000);
    history.observe(offer, at(98), 10000);
    history.observe(offer, at(70), 10000);
    history.observe(offer, at(60), 10000);
    history.observe(offer, at(25), 10000);
    history.observe(offer, at(20), 8000);
    history.observe(offer, at(19), 8000);
    history.observe(offer, at(18), 8000);
    history.observe(offer, at(5), 10000);
    history.observe(offer, at(3), 10000, false, Source.OBSERVED);
    history.observe(offer, at(0), 9500);
    history.observe(offer, at(-1), 5000);
  }

  private Rollup offerRollup(long offerId) throws SQLException {
    try (Connection c = DB.connection()) {
      RollupDao.recomputeOffers(c, List.of(offerId), AS_OF);
      return RollupDao.offerRollup(c, offerId).orElseThrow();
    }
  }

  private Rollup productRollup(long productId) throws SQLException {
    try (Connection c = DB.connection()) {
      RollupDao.recomputeProducts(c, List.of(productId), AS_OF);
      return RollupDao.productRollup(c, productId).orElseThrow();
    }
  }

  /** Sample standard deviation over the mean, the way the SQL computes it. */
  private static double volatility(int... cents) {
    double mean = 0;
    for (int c : cents) {
      mean += c;
    }
    mean /= cents.length;
    double ss = 0;
    for (int c : cents) {
      ss += (c - mean) * (c - mean);
    }
    return Math.sqrt(ss / (cents.length - 1)) / mean;
  }

  @Test
  void anOfferRowIsItsOwnHistoryEndingAtAsOf() throws SQLException {
    Rollup r = offerRollup(offer);

    assertThat(r.offerId()).isEqualTo(offer);
    assertThat(r.productId()).isNull();
    assertThat(r.asOf()).isEqualTo(AS_OF);
    // now: the latest observation at or before as_of — the $50 a day later is not "now"
    assertThat(r.currentPriceCents()).isEqualTo(9500);
    assertThat(r.currentObservedAt()).isEqualTo(at(0));
    assertThat(r.currentInStock()).isTrue();
    assertThat(r.currentOfferId()).isNull();
    assertThat(r.listPriceCents()).isEqualTo(10000);
    // windows
    assertThat(r.d7()).isEqualTo(new Window(9500, 10000, 10000));
    assertThat(r.d30()).isEqualTo(new Window(8000, 9500, 10000));
    assertThat(r.d90()).isEqualTo(new Window(8000, 10000, 10000));
    assertThat(r.d365()).isEqualTo(new Window(8000, 10000, 10000));
    // the year: 14 observations, the one at k=400 outside it, the future one excluded
    assertThat(r.observations365d()).isEqualTo(14);
    assertThat(r.synthetic365d()).isEqualTo(2);
    assertThat(r.percentile365d()).isCloseTo(5.0 / 14, within(1e-9));
    assertThat(r.volatility365d())
        .isCloseTo(
            volatility(
                10000, 10000, 9000, 9000, 10000, 10000, 10000, 10000, 8000, 8000, 8000, 10000,
                10000, 9500),
            within(1e-9));
  }

  @Test
  void salesAreRunsOfConsecutiveObservationsAtOrBelowNinetyPercentOfList() throws SQLException {
    Rollup r = offerRollup(offer);

    assertThat(r.saleWindows())
        .containsExactly(
            new SaleWindow(at(20), at(18), 8000, 20, 3),
            new SaleWindow(at(100), at(99), 9000, 10, 2));
    assertThat(r.saleDays365d()).isEqualTo(5);
    assertThat(r.lastSaleEndedAt()).isEqualTo(at(18));
  }

  @Test
  void aProductRowIsTheCheapestInStockLiveListingAtEachInstant() throws SQLException {
    long product = history.product("Keychron", "Q2");
    history.link(offer, product, "auto");
    long other = history.offer();
    history.link(other, product, "reviewed");
    history.observe(other, at(20), 7000);
    history.observe(other, at(5), 9400);
    history.observe(other, at(2), 9700);
    history.observe(other, at(0), 6000, false, Source.OBSERVED);
    // A retired listing at $10 must not count, however cheap.
    long retired = history.offer();
    history.link(retired, product, "auto");
    history.observe(retired, at(20), 1000);
    history.observe(retired, at(0), 1000);
    execute("update offers set retired_at = now() where id = " + retired);
    // Nor a listing the resolver has only proposed.
    long proposed = history.offer();
    execute("update offers set candidate_product_id = " + product + " where id = " + proposed);
    history.observe(proposed, at(0), 1000);

    Rollup r = productRollup(product);

    assertThat(r.productId()).isEqualTo(product);
    assertThat(r.offerId()).isNull();
    // now: the other listing is cheaper but out of stock, so the $95 in-stock one is current
    assertThat(r.currentPriceCents()).isEqualTo(9500);
    assertThat(r.currentInStock()).isTrue();
    assertThat(r.currentOfferId()).isEqualTo(offer);
    assertThat(r.currentObservedAt()).isEqualTo(at(0));
    assertThat(r.listPriceCents()).isEqualTo(10000);
    // the series: 100 100 90 90 100 100 100 100 | 70 80 80 | 94 97 95 (k=3 is out of stock)
    assertThat(r.d7()).isEqualTo(new Window(9400, 9500, 9700));
    assertThat(r.d30()).isEqualTo(new Window(7000, 9400, 10000));
    assertThat(r.d90()).isEqualTo(new Window(7000, 9500, 10000));
    assertThat(r.d365()).isEqualTo(new Window(7000, 9600, 10000));
    assertThat(r.observations365d()).isEqualTo(14);
    assertThat(r.synthetic365d()).isEqualTo(2);
    assertThat(r.percentile365d()).isCloseTo(6.0 / 14, within(1e-9));
    assertThat(r.saleWindows())
        .containsExactly(
            new SaleWindow(at(20), at(18), 7000, 30, 3),
            new SaleWindow(at(100), at(99), 9000, 10, 2));
  }

  @Test
  void aProductWithNothingInStockKeepsItsLastPriceButSaysSo() throws SQLException {
    long product = history.product("Keychron", "Q2");
    long soldOut = history.offer();
    history.link(soldOut, product, "auto");
    history.observe(soldOut, at(10), 12000, true, Source.OBSERVED);
    history.observe(soldOut, at(1), 11000, false, Source.OBSERVED);

    Rollup r = productRollup(product);

    assertThat(r.currentPriceCents()).isEqualTo(11000);
    assertThat(r.currentInStock()).isFalse();
    assertThat(r.currentOfferId()).isEqualTo(soldOut);
    // the series holds in-stock points only
    assertThat(r.d365()).isEqualTo(new Window(12000, 12000, 12000));
    assertThat(r.observations365d()).isEqualTo(1);
  }

  @Test
  void aRowIsWrittenEvenWhenThereIsNothingToSummarise() throws SQLException {
    long product = history.product("Keychron", "Q2");
    history.link(offer, product, "auto");
    Rollup before = productRollup(product);
    assertThat(before.currentPriceCents()).isEqualTo(9500);

    execute("update offers set retired_at = now() where id = " + offer);
    Rollup after = productRollup(product);

    assertThat(after.currentPriceCents()).isNull();
    assertThat(after.currentInStock()).isNull();
    assertThat(after.listPriceCents()).isNull();
    assertThat(after.d365()).isEqualTo(new Window(null, null, null));
    assertThat(after.percentile365d()).isNull();
    assertThat(after.observations365d()).isZero();
    assertThat(after.saleWindows()).isEmpty();
    assertThat(after.saleDays365d()).isZero();
    assertThat(count("select count(*) from price_rollups where product_id = " + product))
        .isEqualTo(1);
  }

  @Test
  void recomputingWritesTheSameRow() throws SQLException {
    Rollup first = offerRollup(offer);
    Rollup second = offerRollup(offer);

    assertThat(second).usingRecursiveComparison().ignoringFields("computedAt").isEqualTo(first);
    assertThat(count("select count(*) from price_rollups")).isEqualTo(1);
  }

  @Test
  void computingReturnsTheRowRecomputingWouldWriteAndWritesNothing() throws SQLException {
    long product = history.product("Keychron", "Q2");
    history.link(offer, product, "auto");
    long other = history.offer();
    history.link(other, product, "auto");
    history.observe(other, at(20), 7000);
    history.observe(other, at(0), 6000, false, Source.OBSERVED);

    List<Rollup> offers;
    List<Rollup> products;
    try (Connection c = DB.connection()) {
      offers = RollupDao.computeOffers(c, List.of(offer, other), AS_OF);
      products = RollupDao.computeProducts(c, List.of(product), AS_OF);
    }

    assertThat(count("select count(*) from price_rollups")).isZero();
    assertThat(offers).extracting(Rollup::offerId).containsExactly(offer, other);
    assertThat(offers.get(0))
        .usingRecursiveComparison()
        .ignoringFields("computedAt")
        .isEqualTo(offerRollup(offer));
    assertThat(products).hasSize(1);
    assertThat(products.get(0))
        .usingRecursiveComparison()
        .ignoringFields("computedAt")
        .isEqualTo(productRollup(product));
  }

  @Test
  void anAsOfInThePastCannotSeeWhatCameAfterIt() throws SQLException {
    // The backtest's guard: the row as of k = 21 is computed from the first eight points only —
    // the $80 sale, the stock-out, the $95 and the $50 that follow are the future.
    long product = history.product("Keychron", "Q2");
    history.link(offer, product, "auto");

    Rollup then;
    try (Connection c = DB.connection()) {
      then = RollupDao.computeProducts(c, List.of(product), at(21)).get(0);
    }

    assertThat(then.asOf()).isEqualTo(at(21));
    assertThat(then.currentPriceCents()).isEqualTo(10000);
    assertThat(then.currentObservedAt()).isEqualTo(at(25));
    assertThat(then.observations365d()).isEqualTo(8);
    assertThat(then.d365()).isEqualTo(new Window(9000, 10000, 10000));
    assertThat(then.saleWindows()).containsExactly(new SaleWindow(at(100), at(99), 9000, 10, 2));
    assertThat(then.lastSaleEndedAt()).isEqualTo(at(99));
    // two of the eight points are below $100
    assertThat(then.percentile365d()).isCloseTo(2.0 / 8, within(1e-9));
  }
}
