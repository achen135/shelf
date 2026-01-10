package com.achen.shelf.signal;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.db.DealSignalDao;
import com.achen.shelf.db.DealSignalDao.Signal;
import com.achen.shelf.db.ObservationDao.Source;
import com.achen.shelf.rollup.RollupRun;
import com.achen.shelf.testing.FixtureServer;
import com.achen.shelf.testing.PostgresTestBase;
import com.achen.shelf.testing.PriceHistory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The pass: what it writes, that it is scoped, that it is idempotent, and what it skips. */
class SignalRunTest extends PostgresTestBase {

  private static final Instant AS_OF = Instant.parse("2030-06-01T00:00:00Z");

  private PriceHistory history;
  private CategoryConfig category;
  private RollupRun rollups;
  private SignalRun signals;

  private static Instant at(int daysAgo) {
    return AS_OF.minus(daysAgo, ChronoUnit.DAYS);
  }

  @BeforeEach
  void setUp() {
    history = new PriceHistory(DB);
    String yaml =
        FixtureServer.Fixtures.read("categories/fixture-server.yaml.template")
            .replace("__PORT__", "1")
            .replace("name: json_store", "name: " + PriceHistory.RETAILER);
    category =
        new CategoryConfigLoader()
            .load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "keyboards");
    rollups = new RollupRun(DB, RollupRun.Settings.defaults(), Clock.fixed(AS_OF, ZoneOffset.UTC));
    signals = new SignalRun(DB, DealRule.defaults());
  }

  /**
   * A year at $100 with one $70 sale forty days ago, then {@code today} now. Every observation is a
   * finished run of its own, so the offer's {@code last_seen} must be current or the rollup pass
   * would retire it.
   */
  private long productWithAYear(String model, int today) throws SQLException {
    long product = history.product("Keychron", model);
    long offer = history.offer();
    history.link(offer, product, "auto");
    for (int k = 300; k > 0; k -= 10) {
      history.observe(offer, at(k), k == 40 ? 7000 : 10000, true, Source.SYNTHETIC);
    }
    history.observe(offer, at(0), today);
    history.lastSeen(offer, at(0));
    return product;
  }

  private Signal stored(long product) throws SQLException {
    try (Connection c = DB.connection()) {
      return DealSignalDao.forProduct(c, product).orElseThrow();
    }
  }

  @Test
  void decidesEveryProductInScopeFromItsRollupRowAndNoOther() throws SQLException {
    long onSale = productWithAYear("Q2", 7000);
    long atList = productWithAYear("Q4", 10000);
    long untouched = productWithAYear("Q7", 7000);
    RollupRun.Summary rolled = rollups.run(category, new RollupRun.Scope.All());
    assertThat(rolled.products()).containsExactly(onSale, atList, untouched);

    SignalRun.Summary summary = signals.run(category, List.of(onSale, atList));

    assertThat(summary.decided()).isEqualTo(2);
    assertThat(summary.buys()).isEqualTo(1);
    assertThat(summary.waits()).isEqualTo(1);
    assertThat(summary.neutrals()).isZero();
    assertThat(summary.withoutRollup()).isZero();
    assertThat(summary.asOf()).isEqualTo(AS_OF);
    Signal buy = stored(onSale);
    assertThat(buy.signal()).isEqualTo("buy");
    assertThat(buy.reasonCodes())
        .containsExactly("ON_SALE", "YEAR_LOW", "LOW_PERCENTILE", "MOSTLY_SYNTHETIC");
    assertThat(buy.bestOfferId()).isNotNull();
    assertThat(buy.asOf()).isEqualTo(AS_OF);
    Signal wait = stored(atList);
    assertThat(wait.signal()).isEqualTo("wait");
    assertThat(wait.reasonCodes()).startsWith("AT_LIST");
    assertThat(count("select count(*) from deal_signals")).isEqualTo(2);
  }

  @Test
  void aWholeCategoryPassCoversEveryProductAndIsIdempotent() throws SQLException {
    long onSale = productWithAYear("Q2", 7000);
    long noRollup = history.product("Keychron", "Q4");
    rollups.run(category, new RollupRun.Scope.All());
    // Q4 has a rollup row from the full pass (with nothing in it); drop it to exercise the skip.
    execute("delete from price_rollups where product_id = " + noRollup);

    SignalRun.Summary first = signals.run(category);
    SignalRun.Summary second = signals.run(category);

    assertThat(first.decided()).isEqualTo(1);
    assertThat(first.withoutRollup()).isEqualTo(1);
    assertThat(second.decided()).isEqualTo(1);
    assertThat(count("select count(*) from deal_signals")).isEqualTo(1);
    assertThat(stored(onSale).signal()).isEqualTo("buy");
  }

  @Test
  void aProductWithNothingLiveGetsANeutralRowNotAStaleOne() throws SQLException {
    long product = productWithAYear("Q2", 7000);
    rollups.run(category, new RollupRun.Scope.All());
    signals.run(category);
    assertThat(stored(product).signal()).isEqualTo("buy");

    execute("update offers set retired_at = now()");
    rollups.run(category, new RollupRun.Scope.All());
    SignalRun.Summary summary = signals.run(category);

    assertThat(summary.neutrals()).isEqualTo(1);
    Signal s = stored(product);
    assertThat(s.signal()).isEqualTo("neutral");
    assertThat(s.reasonCodes()).containsExactly("NO_PRICE");
    assertThat(s.bestOfferId()).isNull();
  }
}
