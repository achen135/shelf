package com.achen.shelf.signal;

import com.achen.shelf.db.RollupDao.Rollup;
import com.achen.shelf.signal.Backtest.Ahead;
import com.achen.shelf.signal.Backtest.Grid;
import com.achen.shelf.signal.Backtest.Strategy;
import com.achen.shelf.signal.Backtest.Tally;
import com.achen.shelf.signal.DealClassifier.Example;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;

/**
 * The classifier against the rule and the baselines (M12), on days none of them has seen.
 *
 * <p><b>The split is chronological, with a gap.</b> The first {@code splitFraction} of the span's
 * days are the training era; a scored day trains the model only if its whole horizon closes inside
 * that era ({@code day + horizon < splitDay}), so no training label reads a price from the held-out
 * era. Every strategy is then judged on the held-out days — {@code day ≥ splitDay} — through the
 * unmodified {@link Backtest#tally} with its {@code include} predicate, on the identical days. A
 * random day-level split would leak: neighbouring days' horizon windows overlap almost entirely, so
 * the same future prices would sit on both sides. This is the backtest's own no-look-ahead rule
 * applied to training.
 *
 * <p>The training-era tallies are reported too, marked in-sample, so an over-fit shows.
 */
public final class ClassifierEval {

  private ClassifierEval() {}

  /** The split. */
  public record Settings(double splitFraction, double abstainBelow, double abstainAbove) {
    /** Train on the first 70% of the span; the abstaining operating point is 0.4 / 0.6. */
    public static Settings defaults() {
      return new Settings(0.7, 0.4, 0.6);
    }

    public Settings {
      if (splitFraction <= 0 || splitFraction >= 1) {
        throw new IllegalArgumentException("splitFraction must be within (0, 1)");
      }
      if (abstainBelow < 0 || abstainBelow > abstainAbove || abstainAbove > 1) {
        throw new IllegalArgumentException("0 ≤ abstainBelow ≤ abstainAbove ≤ 1");
      }
    }
  }

  /** Every strategy on one set of days. */
  public record Table(
      String era,
      int firstDay,
      int lastDay,
      Tally rule,
      Tally alwaysBuy,
      Tally belowMedian,
      Tally decisive,
      Tally abstaining,
      Tally alwaysBuyOnRuleDays,
      Tally belowMedianOnRuleDays,
      Tally decisiveOnRuleDays,
      Tally ruleOnAbstainingDays,
      Tally alwaysBuyOnAbstainingDays,
      Tally belowMedianOnAbstainingDays) {}

  /** The whole evaluation. */
  public record Report(
      Backtest.Settings backtest,
      Settings settings,
      DealClassifier.Settings training,
      Backtest.Span span,
      int products,
      int splitDay,
      int trainingExamples,
      int trainingDrops,
      DealClassifier model,
      long heldOutSyntheticPoints,
      long heldOutTotalPoints,
      int heldOutDaysReachingObserved,
      Table trainingEra,
      Table heldOut) {}

  /** The day index the held-out era starts at. */
  public static int splitDay(Backtest.Span span, double splitFraction) {
    return (int) Math.floor(span.days() * splitFraction);
  }

  /**
   * The training rows: every scored, gate-passing product-day whose horizon closes before the split
   * day, labeled by what followed it.
   */
  public static List<Example> examples(
      Grid grid, Backtest.Settings b, int splitDay, int minObservations) {
    List<Example> out = new ArrayList<>();
    for (long product : grid.products()) {
      for (int d = 0; d + b.horizonDays() < splitDay; d++) {
        if (!Backtest.scored(
            grid, product, d, b.horizonDays(), b.dropTolerance(), b.warmupDays())) {
          continue;
        }
        double[] f = DealClassifier.features(grid.at(product, d), minObservations);
        if (f == null) {
          continue;
        }
        Ahead ahead = Backtest.ahead(grid, product, d, b.horizonDays(), b.dropTolerance());
        out.add(
            new Example(
                product, d, java.util.Arrays.stream(f).boxed().toList(), ahead == Ahead.DROP));
      }
    }
    return out;
  }

  /** Trains on the training era and judges everything on the held-out one. */
  public static Report evaluate(
      Grid grid, DealRule rule, Backtest.Settings b, Settings s, DealClassifier.Settings training) {
    int splitDay = splitDay(grid.span(), s.splitFraction());
    List<Example> examples = examples(grid, b, splitDay, training.minObservations());
    DealClassifier model = DealClassifier.train(examples, training);
    Strategy decisive = model.at("model", 0.5, 0.5);
    Strategy abstaining = model.at("model, abstaining", s.abstainBelow(), s.abstainAbove());
    Strategy theRule = Backtest.rule(rule);

    long synthetic = 0;
    long total = 0;
    int reaching = 0;
    for (long product : grid.products()) {
      for (int d = splitDay; d < grid.span().days(); d++) {
        if (!Backtest.scored(
            grid, product, d, b.horizonDays(), b.dropTolerance(), b.warmupDays())) {
          continue;
        }
        Rollup r = grid.at(product, d);
        synthetic += r.synthetic365d();
        total += r.observations365d();
        if (grid.span().firstObservedDay() != null
            && !grid.span().day(d + b.horizonDays()).isBefore(grid.span().firstObservedDay())) {
          reaching++;
        }
      }
    }
    int trainingDrops = (int) examples.stream().filter(Example::drop).count();
    return new Report(
        b,
        s,
        training,
        grid.span(),
        grid.products().size(),
        splitDay,
        examples.size(),
        trainingDrops,
        model,
        synthetic,
        total,
        reaching,
        table(
            grid,
            b,
            "training era (in-sample)",
            0,
            splitDay - b.horizonDays() - 1,
            theRule,
            decisive,
            abstaining),
        table(
            grid, b, "held out", splitDay, grid.span().days() - 1, theRule, decisive, abstaining));
  }

  private static Table table(
      Grid grid,
      Backtest.Settings b,
      String era,
      int firstDay,
      int lastDay,
      Strategy rule,
      Strategy decisive,
      Strategy abstaining) {
    BiPredicate<Long, Integer> era0 = (p, d) -> d >= firstDay && d <= lastDay;
    BiPredicate<Long, Integer> ruleSpoke =
        era0.and((p, d) -> rule.call(grid.at(p, d), grid.series(p), d) != Signal.NEUTRAL);
    BiPredicate<Long, Integer> abstainingSpoke =
        era0.and((p, d) -> abstaining.call(grid.at(p, d), grid.series(p), d) != Signal.NEUTRAL);
    return new Table(
        era,
        firstDay,
        lastDay,
        tally(grid, rule, b, era0),
        tally(grid, Backtest.ALWAYS_BUY, b, era0),
        tally(grid, Backtest.BELOW_MEDIAN, b, era0),
        tally(grid, decisive, b, era0),
        tally(grid, abstaining, b, era0),
        tally(grid, Backtest.ALWAYS_BUY, b, ruleSpoke),
        tally(grid, Backtest.BELOW_MEDIAN, b, ruleSpoke),
        tally(grid, decisive, b, ruleSpoke),
        tally(grid, rule, b, abstainingSpoke),
        tally(grid, Backtest.ALWAYS_BUY, b, abstainingSpoke),
        tally(grid, Backtest.BELOW_MEDIAN, b, abstainingSpoke));
  }

  private static Tally tally(
      Grid grid, Strategy s, Backtest.Settings b, BiPredicate<Long, Integer> include) {
    return Backtest.tally(grid, s, b.horizonDays(), b.dropTolerance(), b.warmupDays(), include);
  }

  // ---------------------------------------------------------------------------------------------
  // The report as text.
  // ---------------------------------------------------------------------------------------------

  public static String render(Report r, String category) {
    StringBuilder sb = new StringBuilder();
    Backtest.Settings b = r.backtest();
    line(
        sb,
        "deal classifier vs rule — %s: %d products, %s → %s (%d days)",
        category,
        r.products(),
        r.span().firstDay(),
        r.span().lastDay(),
        r.span().days());
    line(
        sb,
        "history: synthetic %s, observed %s",
        r.span().lastSyntheticDay() == null
            ? "none"
            : r.span().firstDay() + " → " + r.span().lastSyntheticDay(),
        r.span().firstObservedDay() == null
            ? "none"
            : r.span().firstObservedDay() + " → " + r.span().lastDay());
    line(
        sb,
        "judged over the next %d days; a drop is %.0f%% or more below today; the first %d days"
            + " are warm-up and not scored",
        b.horizonDays(),
        b.dropTolerance() * 100,
        b.warmupDays());
    line(
        sb,
        "split: chronological at day %d of %d (%s, the first %.0f%% of the span); a day trains"
            + " only if its %d-day horizon closes before the split, so no training label reads a"
            + " held-out price",
        r.splitDay(),
        r.span().days(),
        r.span().day(r.splitDay()),
        r.settings().splitFraction() * 100,
        b.horizonDays());
    line(
        sb,
        "training rows: %,d product-days (%,d with a drop ahead, %.1f%%), days %d → %d",
        r.trainingExamples(),
        r.trainingDrops(),
        100.0 * r.trainingDrops() / Math.max(1, r.trainingExamples()),
        r.trainingEra().firstDay(),
        r.trainingEra().lastDay());
    line(
        sb,
        "held out: %,d product-days, days %d → %d. The features behind them rest on %,d"
            + " observations, %,d (%.1f%%) synthetic; %,d held-out days (%.1f%%) are judged"
            + " against at least one observed day",
        r.heldOut().rule().days(),
        r.heldOut().firstDay(),
        r.heldOut().lastDay(),
        r.heldOutTotalPoints(),
        r.heldOutSyntheticPoints(),
        100.0 * r.heldOutSyntheticPoints() / Math.max(1, r.heldOutTotalPoints()),
        r.heldOutDaysReachingObserved(),
        100.0 * r.heldOutDaysReachingObserved() / Math.max(1, r.heldOut().rule().days()));
    sb.append('\n');
    line(
        sb,
        "model: logistic regression, %d features, gradient descent (rate %.2f, %,d iterations, L2"
            + " %.0e), ≥ %d points to call; training log-loss %.4f — all fixed before this run",
        DealClassifier.FEATURES.size(),
        r.training().learningRate(),
        r.training().iterations(),
        r.training().l2(),
        r.training().minObservations(),
        r.model().trainingLoss());
    line(sb, "weights (per standard deviation of the feature; + means a drop is likelier):");
    sb.append(r.model().describe());
    line(
        sb,
        "calls: decisive = buy at or below 0.5, wait above; abstaining = buy ≤ %.2f, wait ≥ %.2f,"
            + " else no call — both fixed before this run",
        r.settings().abstainBelow(),
        r.settings().abstainAbove());
    sb.append('\n');
    table(sb, r.heldOut(), b.horizonDays());
    sb.append('\n');
    table(sb, r.trainingEra(), b.horizonDays());
    return sb.toString();
  }

  private static void table(StringBuilder sb, Table t, int horizon) {
    line(sb, "%s (%d days; days %d → %d):", t.era(), horizon, t.firstDay(), t.lastDay());
    header(sb);
    row(sb, t.rule());
    row(sb, t.alwaysBuy());
    row(sb, t.belowMedian());
    row(sb, t.decisive());
    row(sb, t.abstaining());
    line(sb, "  on the %,d days the rule spoke:", t.rule().calls());
    row(sb, t.alwaysBuyOnRuleDays());
    row(sb, t.belowMedianOnRuleDays());
    row(sb, t.decisiveOnRuleDays());
    line(sb, "  on the %,d days the abstaining model spoke:", t.abstaining().calls());
    row(sb, t.ruleOnAbstainingDays());
    row(sb, t.alwaysBuyOnAbstainingDays());
    row(sb, t.belowMedianOnAbstainingDays());
    line(
        sb,
        "  base rate: a drop of the tolerance or more followed %.1f%% of these days",
        t.alwaysBuy().baseRate() * 100);
  }

  private static void header(StringBuilder sb) {
    line(
        sb,
        "  %-20s %6s %7s %9s %9s %6s %7s %6s %8s",
        "strategy",
        "days",
        "calls",
        "coverage",
        "hit rate",
        "buys",
        "buy ok",
        "waits",
        "wait ok");
  }

  private static void row(StringBuilder sb, Tally t) {
    line(
        sb,
        "  %-20s %6d %7d %9.3f %9s %6d %7s %6d %8s",
        t.strategy(),
        t.days(),
        t.calls(),
        t.coverage(),
        rate(t.hitRate()),
        t.buys(),
        rate(t.buyPrecision()),
        t.waits(),
        rate(t.waitPrecision()));
  }

  private static String rate(double v) {
    return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.3f", v);
  }

  private static void line(StringBuilder sb, String format, Object... args) {
    sb.append(String.format(Locale.ROOT, format, args)).append('\n');
  }
}
