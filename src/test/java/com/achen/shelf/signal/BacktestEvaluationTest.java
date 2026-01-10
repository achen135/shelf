package com.achen.shelf.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.achen.shelf.db.RollupDao.Rollup;
import com.achen.shelf.db.RollupDao.Window;
import com.achen.shelf.signal.Backtest.Ahead;
import com.achen.shelf.signal.Backtest.Grid;
import com.achen.shelf.signal.Backtest.Policy;
import com.achen.shelf.signal.Backtest.Series;
import com.achen.shelf.signal.Backtest.Span;
import com.achen.shelf.signal.Backtest.Strategy;
import com.achen.shelf.signal.Backtest.Tally;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic over a grid, on a hand-built one: judging, the baselines, following the advice.
 *
 * <p>One product, twelve days, prices in dollars: {@code 100 100 100 100 90 90 100 100 100 80 100
 * 100}. With a two-day warm-up and a three-day horizon, days 2–8 are scored.
 */
class BacktestEvaluationTest {

  private static final LocalDate DAY0 = LocalDate.of(2030, 1, 1);
  private static final int[] PRICES = {100, 100, 100, 100, 90, 90, 100, 100, 100, 80, 100, 100};
  private static final int HORIZON = 3;
  private static final int WARMUP = 2;
  private static final double TOL = 0.02;

  private static final Strategy ALWAYS_WAIT = fixed("always wait", Signal.WAIT);
  private static final Strategy NEVER_CALL = fixed("never", Signal.NEUTRAL);

  private static Strategy fixed(String name, Signal signal) {
    return new Strategy() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Signal call(Rollup asOf, Series series, int day) {
        return signal;
      }
    };
  }

  /** A rollup row carrying only what a series reads: the day's price and stock. */
  private static Rollup cell(long product, int day, Integer dollars, boolean inStock) {
    Instant asOf = Backtest.endOfDay(DAY0.plusDays(day));
    Integer price = dollars == null ? null : dollars * 100;
    Window none = new Window(null, null, null);
    return new Rollup(
        null,
        product,
        asOf,
        price,
        price == null ? null : asOf,
        price == null ? null : inStock,
        price == null ? null : 1L,
        price,
        none,
        none,
        none,
        none,
        null,
        null,
        0,
        0,
        List.of(),
        0,
        null,
        asOf);
  }

  private static Grid grid(int[] prices, int... outOfStockDays) {
    Rollup[] rows = new Rollup[prices.length];
    for (int d = 0; d < prices.length; d++) {
      boolean oos = false;
      for (int o : outOfStockDays) {
        oos |= o == d;
      }
      rows[d] = cell(1L, d, prices[d], !oos);
    }
    Span span =
        new Span(DAY0, DAY0.plusDays(prices.length - 1), null, DAY0.plusDays(prices.length - 1));
    return new Grid(span, Map.of(1L, "Test Product"), Map.of(1L, rows));
  }

  private static Tally tally(Grid g, Strategy s) {
    return Backtest.tally(g, s, HORIZON, TOL, WARMUP, (p, d) -> true);
  }

  @Test
  void aDropIsALowerBuyablePriceWithinTheHorizon() {
    Grid g = grid(PRICES);
    Series s = seriesOf(g);
    // days 0 and 1 are judgeable but not scored (warm-up); the last three have no full window
    assertThat(Backtest.ahead(s, 2, HORIZON, TOL)).isEqualTo(Ahead.DROP); // 90 on day 4
    assertThat(Backtest.ahead(s, 4, HORIZON, TOL))
        .isEqualTo(Ahead.NO_DROP); // 90 → 90 is not a 2% drop
    assertThat(Backtest.ahead(s, 5, HORIZON, TOL)).isEqualTo(Ahead.NO_DROP);
    assertThat(Backtest.ahead(s, 6, HORIZON, TOL)).isEqualTo(Ahead.DROP); // 80 on day 9
    assertThat(Backtest.ahead(s, 8, HORIZON, TOL)).isEqualTo(Ahead.DROP);
    assertThat(Backtest.ahead(s, 9, HORIZON, TOL))
        .isEqualTo(Ahead.UNJUDGEABLE); // window runs past the end
    assertThat(Backtest.ahead(s, 4, HORIZON, 0.0)).isEqualTo(Ahead.NO_DROP); // 90 is not below 90
    assertThat(Backtest.ahead(s, 3, HORIZON, 0.0)).isEqualTo(Ahead.DROP);
  }

  @Test
  void alwaysBuyScoresTheBaseRateAndBelowMedianReadsTheTrailingSeries() {
    Grid g = grid(PRICES);

    Tally always = tally(g, Backtest.ALWAYS_BUY);
    assertThat(always.days()).isEqualTo(7);
    assertThat(always.drops()).isEqualTo(5);
    assertThat(always.buys()).isEqualTo(7);
    assertThat(always.hitRate()).isCloseTo(2.0 / 7, within(1e-9));
    assertThat(always.baseRate()).isCloseTo(5.0 / 7, within(1e-9));
    assertThat(always.coverage()).isEqualTo(1.0);

    // the trailing median is 100 every day, so 90 is a buy and 100 a wait — all seven right
    Tally median = tally(g, Backtest.BELOW_MEDIAN);
    assertThat(Backtest.trailingMedians(g, 1L)[5]).isEqualTo(100_00);
    assertThat(median.buys()).isEqualTo(2);
    assertThat(median.waits()).isEqualTo(5);
    assertThat(median.hitRate()).isEqualTo(1.0);

    Tally waits = tally(g, ALWAYS_WAIT);
    assertThat(waits.hitRate()).isCloseTo(5.0 / 7, within(1e-9));
    Tally never = tally(g, NEVER_CALL);
    assertThat(never.calls()).isZero();
    assertThat(never.neutrals()).isEqualTo(7);
    assertThat(never.hitRate()).isNaN();
    assertThat(never.coverage()).isZero();
  }

  @Test
  void followingTheAdvicePaysWhatItPays() {
    Grid g = grid(PRICES);

    Policy always = Backtest.follow(g, Backtest.ALWAYS_BUY, HORIZON, TOL, WARMUP);
    assertThat(always.starts()).isEqualTo(7);
    assertThat(always.meanPaidRatio()).isEqualTo(1.0);
    assertThat(always.paidSame()).isEqualTo(7);

    // waiting the full horizon from each scored day buys on day d + 3
    Policy wait = Backtest.follow(g, ALWAYS_WAIT, HORIZON, TOL, WARMUP);
    assertThat(wait.paidLess()).isEqualTo(2); // 2 → 90, 6 → 80
    assertThat(wait.paidMore()).isEqualTo(2); // 4 and 5 → 100 after a 90
    assertThat(wait.paidSame()).isEqualTo(3);
    assertThat(wait.meanPaidRatio())
        .isCloseTo((0.9 + 1 + 100.0 / 90 + 100.0 / 90 + 0.8 + 1 + 1) / 7, within(1e-9));
    assertThat(wait.unresolved()).isZero();
  }

  @Test
  void outOfStockDaysAreNeitherScoredNorBuyable() {
    Grid g = grid(PRICES, 9); // the $80 day cannot be bought on

    Series s = seriesOf(g);
    assertThat(Backtest.ahead(s, 6, HORIZON, TOL))
        .isEqualTo(Ahead.NO_DROP); // the 80 is not buyable
    assertThat(Backtest.ahead(s, 9, HORIZON, TOL)).isEqualTo(Ahead.UNJUDGEABLE);
    Tally always = tally(g, Backtest.ALWAYS_BUY);
    assertThat(always.drops()).isEqualTo(2);

    // waiting from day 6 runs to day 9, which is out of stock, so it buys on day 10
    Policy wait = Backtest.follow(g, ALWAYS_WAIT, HORIZON, TOL, WARMUP);
    assertThat(wait.paidLess()).isEqualTo(1);
    assertThat(wait.unresolved()).isZero();
  }

  @Test
  void aGridNeedsOneRowPerDay() {
    Span span = new Span(DAY0, DAY0.plusDays(2), null, null);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new Grid(span, Map.of(), Map.of(1L, new Rollup[] {cell(1L, 0, 100, true)})))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(span.days()).isEqualTo(3);
    assertThat(Backtest.endOfDay(DAY0)).isEqualTo(Instant.parse("2030-01-01T23:59:59.999999Z"));
  }

  private static Series seriesOf(Grid g) {
    return g.series(1L);
  }
}
