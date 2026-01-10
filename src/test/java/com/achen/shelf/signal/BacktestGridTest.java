package com.achen.shelf.signal;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.db.ObservationDao.Source;
import com.achen.shelf.db.RollupDao.Rollup;
import com.achen.shelf.signal.Backtest.Grid;
import com.achen.shelf.signal.Backtest.Report;
import com.achen.shelf.testing.FixtureServer;
import com.achen.shelf.testing.PostgresTestBase;
import com.achen.shelf.testing.PriceHistory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The grid against a real database: one recomputed row per product per day, with nothing after the
 * day in it, and a report at the end.
 *
 * <p>Product A: $100 a day for forty days, $70 on days 20–24 (a sale), one day out of stock (30).
 * Product B: $50 a day, synthetic. Both observed at noon UTC, like the backfill.
 */
class BacktestGridTest extends PostgresTestBase {

  private static final LocalDate DAY0 = LocalDate.of(2030, 3, 1);
  private static final int DAYS = 40;

  private PriceHistory history;
  private CategoryConfig category;
  private long a;
  private long b;

  private static Instant noon(int day) {
    return DAY0.plusDays(day).atTime(12, 0).toInstant(ZoneOffset.UTC);
  }

  @BeforeEach
  void setUp() throws SQLException {
    history = new PriceHistory(DB);
    String yaml =
        FixtureServer.Fixtures.read("categories/fixture-server.yaml.template")
            .replace("__PORT__", "1")
            .replace("name: json_store", "name: " + PriceHistory.RETAILER);
    category =
        new CategoryConfigLoader()
            .load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "keyboards");
    a = history.product("Keychron", "Q2");
    b = history.product("Keychron", "Q4");
    long offerA = history.offer();
    long offerB = history.offer();
    history.link(offerA, a, "auto");
    history.link(offerB, b, "auto");
    long run = history.finishedRun(noon(0));
    for (int d = 0; d < DAYS; d++) {
      boolean sale = d >= 20 && d <= 24;
      history.observe(offerA, noon(d), sale ? 7000 : 10000, d != 30, Source.OBSERVED, run);
      history.observe(offerB, noon(d), 5000, true, Source.SYNTHETIC, run);
    }
    history.lastSeen(offerA, noon(DAYS - 1));
    history.lastSeen(offerB, noon(DAYS - 1));
  }

  @Test
  void buildsOneAsOfRowPerProductPerDayWithNothingFromAfterTheDay() throws SQLException {
    Backtest backtest = new Backtest(DB, new Backtest.Settings(5, 10, 0.02, 2));

    Grid grid = backtest.grid(category);

    assertThat(grid.span().firstDay()).isEqualTo(DAY0);
    assertThat(grid.span().lastDay()).isEqualTo(DAY0.plusDays(DAYS - 1));
    assertThat(grid.span().days()).isEqualTo(DAYS);
    assertThat(grid.span().firstObservedDay()).isEqualTo(DAY0);
    assertThat(grid.span().lastSyntheticDay()).isEqualTo(DAY0.plusDays(DAYS - 1));
    assertThat(grid.products()).containsExactly(a, b);
    assertThat(grid.name(a)).isEqualTo("Keychron Q2");
    for (int d = 0; d < DAYS; d++) {
      Rollup row = grid.at(a, d);
      assertThat(row.productId()).isEqualTo(a);
      assertThat(row.asOf()).isEqualTo(Backtest.endOfDay(DAY0.plusDays(d)));
      assertThat(row.observations365d()).isEqualTo(d + 1 - (d >= 30 ? 1 : 0));
    }
    // the day before the sale knows nothing of it; the first sale day is the year's low
    assertThat(grid.at(a, 19).d365().min()).isEqualTo(10000);
    assertThat(grid.at(a, 19).saleWindows()).isEmpty();
    assertThat(grid.at(a, 20).d365().min()).isEqualTo(7000);
    assertThat(grid.at(a, 20).currentPriceCents()).isEqualTo(7000);
    assertThat(grid.at(a, 20).percentile365d()).isEqualTo(0.0);
    assertThat(grid.at(a, 24).saleWindows()).hasSize(1);
    // the out-of-stock day keeps the last price but is not buyable
    assertThat(grid.at(a, 30).currentInStock()).isFalse();
    assertThat(grid.at(a, 30).currentPriceCents()).isEqualTo(10000);
    assertThat(grid.at(b, 39).synthetic365d()).isEqualTo(40);
    assertThat(grid.at(b, 39).observations365d()).isEqualTo(40);
  }

  @Test
  void theRuleIsJudgedOnWhatItWouldHaveSaid() throws SQLException {
    Backtest backtest = new Backtest(DB, new Backtest.Settings(5, 10, 0.02, 2));
    Grid grid = backtest.grid(category);
    // forty days of history: the production gate of thirty points would leave most of it thin
    assertThat(DealRule.defaults().decide(grid.at(a, 22)))
        .isEqualTo(new DealRule.Decision(Signal.NEUTRAL, List.of(ReasonCode.THIN_HISTORY)));
    DealRule rule = new DealRule(new DealRule.Thresholds(0.20, 0.50, 1.05, 10, 1));

    // before the sale A has never been on sale, so no call (a flat price is trivially its year's
    // low); during it, a buy at the year's low; after it, a wait at list, since A now goes on sale
    assertThat(rule.decide(grid.at(a, 15)))
        .isEqualTo(
            new DealRule.Decision(
                Signal.NEUTRAL,
                List.of(
                    ReasonCode.AT_LIST,
                    ReasonCode.YEAR_LOW,
                    ReasonCode.LOW_PERCENTILE,
                    ReasonCode.NO_SALE_HISTORY)));
    assertThat(rule.decide(grid.at(a, 22)).signal()).isEqualTo(Signal.BUY);
    assertThat(rule.decide(grid.at(a, 22)).reasons())
        .containsExactly(ReasonCode.ON_SALE, ReasonCode.YEAR_LOW, ReasonCode.LOW_PERCENTILE);
    assertThat(rule.decide(grid.at(a, 31)).signal()).isEqualTo(Signal.WAIT);
    assertThat(rule.decide(grid.at(a, 31)).reasons())
        .containsExactly(ReasonCode.AT_LIST, ReasonCode.LOW_PERCENTILE, ReasonCode.SALES_RECUR);
    // B never moves: at its list with no sale history, no call — and MOSTLY_SYNTHETIC, since it is
    assertThat(rule.decide(grid.at(b, 35)).reasons())
        .containsExactly(
            ReasonCode.AT_LIST,
            ReasonCode.YEAR_LOW,
            ReasonCode.LOW_PERCENTILE,
            ReasonCode.NO_SALE_HISTORY,
            ReasonCode.MOSTLY_SYNTHETIC);
    // the tally agrees with the calls above
    assertThat(
            Backtest.tally(grid, Backtest.rule(rule), 5, 0.02, 10, (p, d) -> p == a && d == 22)
                .buyHits())
        .isEqualTo(1);
  }

  @Test
  void reportsHitRatesBesideTheBaselinesAndTheSyntheticShare() throws SQLException {
    Backtest backtest = new Backtest(DB, new Backtest.Settings(5, 10, 0.02, 2));
    Grid grid = backtest.grid(category);

    Report report = backtest.evaluate(grid, DealRule.defaults());

    // scored: days 10..34 for both products, less A's out-of-stock day 30 = 25 + 24
    assertThat(report.products()).isEqualTo(2);
    assertThat(report.productsScored()).isEqualTo(2);
    assertThat(report.scoredDays()).isEqualTo(49);
    assertThat(report.headline().alwaysBuy().days()).isEqualTo(49);
    // A drops on days 15..19 (the sale comes within five days); B never drops
    assertThat(report.headline().alwaysBuy().drops()).isEqualTo(5);
    assertThat(report.headline().alwaysBuy().hitRate()).isEqualTo(44.0 / 49);
    assertThat(report.headline().rule().days()).isEqualTo(49);
    assertThat(report.headline().rule().calls()).isEqualTo(report.alwaysBuyOnRuleDays().days());
    assertThat(report.byHorizon()).containsKeys(5, 7, 14, 30);
    assertThat(report.buySweep()).isNotEmpty();
    assertThat(report.saleWindowSweep()).hasSize(Backtest.SALE_WINDOW_SWEEP.size());
    // B's points are all synthetic, A's none
    assertThat(report.perProduct())
        .extracting(Backtest.ProductRow::name)
        .containsExactly("Keychron Q2", "Keychron Q4");
    assertThat(report.perProduct().get(0).syntheticPoints()).isZero();
    assertThat(report.perProduct().get(1).syntheticPoints())
        .isEqualTo(report.perProduct().get(1).totalPoints());
    assertThat(report.syntheticPoints()).isEqualTo(report.perProduct().get(1).totalPoints());
    assertThat(report.scoredDaysReachingObserved()).isEqualTo(49);

    String text = BacktestReport.render(report, "keyboards");
    assertThat(text)
        .contains("deal-signal backtest — keyboards: 2 products (2 scored)")
        .contains("headline (5 days):")
        .contains("always buy")
        .contains("buy below median")
        .contains("sale-window sweep")
        .contains("Keychron Q4");
  }

  @Test
  void aCategoryWithNoObservationsIsRefusedNotGuessedAt() throws SQLException {
    execute("truncate price_observations");
    Backtest backtest = new Backtest(DB, Backtest.Settings.defaults());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> backtest.grid(category))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no price observations");
  }

  @Test
  void settingsAreGuarded() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Backtest.Settings(0, 0, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Backtest.Settings(7, 0, 1.0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(List.of(Backtest.Settings.defaults().horizonDays())).containsExactly(30);
  }
}
