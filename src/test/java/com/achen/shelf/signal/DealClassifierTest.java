package com.achen.shelf.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.achen.shelf.db.RollupDao.Rollup;
import com.achen.shelf.db.RollupDao.Window;
import com.achen.shelf.signal.Backtest.Grid;
import com.achen.shelf.signal.Backtest.Span;
import com.achen.shelf.signal.Backtest.Strategy;
import com.achen.shelf.signal.Backtest.Tally;
import com.achen.shelf.signal.DealClassifier.Example;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The classifier on hand-built rows: it learns a separable label, runs through the backtest's tally
 * as a strategy with nothing in the harness changed, and its training rows never read past the
 * split.
 */
class DealClassifierTest {

  private static final LocalDate DAY0 = LocalDate.of(2030, 1, 1);
  private static final Backtest.Settings BACKTEST = new Backtest.Settings(3, 2, 0.02, 1);
  private static final DealClassifier.Settings TRAINING =
      new DealClassifier.Settings(0.5, 2_000, 1e-3, 1);

  /** A full rollup row: the day's price, and a percentile the model can read. */
  private static Rollup cell(long product, int day, int dollars, double percentile) {
    Instant asOf = Backtest.endOfDay(DAY0.plusDays(day));
    int price = dollars * 100;
    Window year = new Window(80_00, 100_00, 110_00);
    return new Rollup(
        null,
        product,
        asOf,
        price,
        asOf,
        true,
        1L,
        100_00,
        year,
        year,
        year,
        year,
        percentile,
        0.05,
        40,
        40,
        List.of(),
        0,
        null,
        asOf);
  }

  private static Example example(double percentile, boolean drop) {
    return new Example(1L, 0, List.of(1.0, 1.1, 1.0, percentile, 0.0, 0.0, 1.0), drop);
  }

  @Test
  void learnsASeparableLabelDeterministically() {
    List<Example> rows = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      double percentile = i / 40.0;
      rows.add(example(percentile, percentile >= 0.5)); // a high percentile means a drop ahead
    }
    DealClassifier a = DealClassifier.train(rows, TRAINING);
    DealClassifier b = DealClassifier.train(rows, TRAINING);

    assertThat(a.weights()).containsExactly(b.weights());
    assertThat(a.bias()).isEqualTo(b.bias());
    assertThat(a.trainedOn()).isEqualTo(40);
    assertThat(a.weights()[3]).isPositive(); // the percentile carries the label
    assertThat(a.weights()[0]).isCloseTo(0, within(1e-9)); // a constant feature earns no weight
    assertThat(a.trainingLoss()).isLessThan(0.2);
    assertThat(a.probability(cell(1L, 0, 100, 0.05))).isLessThan(0.2);
    assertThat(a.probability(cell(1L, 0, 100, 0.95))).isGreaterThan(0.8);
    assertThat(a.describe()).contains("percentile 365d");
  }

  @Test
  void theGatesAreTheRulesAndAFailedGateIsNoCall() {
    Rollup gated = cell(1L, 0, 100, 0.5);
    assertThat(DealClassifier.features(gated, 41)).isNull(); // 40 points, 41 needed
    assertThat(DealClassifier.features(gated, 40)).hasSize(DealClassifier.FEATURES.size());
    DealClassifier model =
        DealClassifier.train(List.of(example(0.1, false), example(0.9, true)), TRAINING);
    DealClassifier thin =
        DealClassifier.train(
            List.of(example(0.1, false), example(0.9, true)),
            new DealClassifier.Settings(0.5, 10, 0, 41));
    assertThat(thin.probability(gated)).isNaN();
    assertThat(thin.at("m", 0.5, 0.5).call(gated, null, 0)).isEqualTo(Signal.NEUTRAL);
    assertThat(model.probability(gated)).isBetween(0.0, 1.0);
  }

  @Test
  void runsThroughTheUnmodifiedTallyAsAStrategy() {
    // Twelve days, one product; the percentile alternates low / high, and the price steps so a drop
    // follows exactly the high-percentile days within the three-day horizon.
    int[] prices = {100, 100, 100, 100, 90, 90, 100, 100, 100, 80, 100, 100};
    Rollup[] rows = new Rollup[prices.length];
    for (int d = 0; d < prices.length; d++) {
      boolean dropAhead = Backtest.ahead(seriesOf(prices), d, 3, 0.02) == Backtest.Ahead.DROP;
      rows[d] = cell(1L, d, prices[d], dropAhead ? 0.9 : 0.1);
    }
    Grid grid =
        new Grid(
            new Span(
                DAY0, DAY0.plusDays(prices.length - 1), null, DAY0.plusDays(prices.length - 1)),
            Map.of(1L, "Test Product"),
            Map.of(1L, rows));
    List<Example> rows40 = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      rows40.add(example(i / 40.0, i >= 20));
    }
    DealClassifier model = DealClassifier.train(rows40, TRAINING);

    Strategy decisive = model.at("model", 0.5, 0.5);
    Tally t = Backtest.tally(grid, decisive, 3, 0.02, 2, (p, d) -> true);
    assertThat(t.strategy()).isEqualTo("model");
    assertThat(t.days()).isEqualTo(7); // the same seven days the rule and baselines are scored on
    assertThat(t.calls()).isEqualTo(7);
    assertThat(t.hitRate()).isEqualTo(1.0); // it read the label off the percentile it was given

    Strategy abstaining = model.at("model, abstaining", 0.4, 0.6);
    Tally a = Backtest.tally(grid, abstaining, 3, 0.02, 2, (p, d) -> true);
    assertThat(a.calls() + a.neutrals()).isEqualTo(7);
  }

  @Test
  void trainingRowsNeverReadPastTheSplitAndTheHeldOutTableStartsThere() {
    int days = 40;
    Rollup[] rows = new Rollup[days];
    int[] prices = new int[days];
    for (int d = 0; d < days; d++) {
      prices[d] = d % 5 == 4 ? 90 : 100;
      rows[d] = cell(1L, d, prices[d], d % 5 == 3 ? 0.9 : 0.1);
    }
    Grid grid =
        new Grid(
            new Span(DAY0, DAY0.plusDays(days - 1), null, DAY0.plusDays(days - 1)),
            Map.of(1L, "Test Product"),
            Map.of(1L, rows));
    ClassifierEval.Settings split = new ClassifierEval.Settings(0.7, 0.4, 0.6);
    int splitDay = ClassifierEval.splitDay(grid.span(), 0.7);
    assertThat(splitDay).isEqualTo(28);

    List<Example> examples = ClassifierEval.examples(grid, BACKTEST, splitDay, 1);
    assertThat(examples).isNotEmpty();
    for (Example e : examples) {
      assertThat(e.day()).isGreaterThanOrEqualTo(BACKTEST.warmupDays());
      assertThat(e.day() + BACKTEST.horizonDays()).isLessThan(splitDay); // the label closes before
    }

    ClassifierEval.Report report =
        ClassifierEval.evaluate(grid, DealRule.defaults(), BACKTEST, split, TRAINING);
    assertThat(report.splitDay()).isEqualTo(28);
    assertThat(report.trainingExamples()).isEqualTo(examples.size());
    assertThat(report.heldOut().firstDay()).isEqualTo(28);
    assertThat(report.heldOut().lastDay()).isEqualTo(39);
    assertThat(report.trainingEra().lastDay()).isEqualTo(24);
    // the held-out days are scored identically for every strategy
    assertThat(report.heldOut().rule().days())
        .isEqualTo(report.heldOut().alwaysBuy().days())
        .isEqualTo(report.heldOut().decisive().days())
        .isEqualTo(report.heldOut().abstaining().days())
        .isEqualTo(9); // days 28..36; 37..39 have no full window
    assertThat(report.heldOut().decisive().coverage()).isEqualTo(1.0);
    String text = ClassifierEval.render(report, "test");
    assertThat(text)
        .contains("held out (3 days; days 28 → 39)")
        .contains("training era (in-sample)");
  }

  private static Backtest.Series seriesOf(int[] prices) {
    Rollup[] rows = new Rollup[prices.length];
    for (int d = 0; d < prices.length; d++) {
      rows[d] = cell(1L, d, prices[d], 0.5);
    }
    return new Backtest.Series(rows);
  }
}
