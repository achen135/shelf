package com.achen.shelf.signal;

import com.achen.shelf.signal.Backtest.CodeRow;
import com.achen.shelf.signal.Backtest.Policy;
import com.achen.shelf.signal.Backtest.ProductRow;
import com.achen.shelf.signal.Backtest.Report;
import com.achen.shelf.signal.Backtest.SweepPoint;
import com.achen.shelf.signal.Backtest.Tally;
import java.util.Locale;

/** The backtest as text: the committed report and what {@code shelf eval backtest} prints. */
public final class BacktestReport {

  private BacktestReport() {}

  public static String render(Report r, String category) {
    StringBuilder sb = new StringBuilder();
    Backtest.Settings s = r.settings();
    line(
        sb,
        "deal-signal backtest — %s: %d products (%d scored), %s → %s (%d days)",
        category,
        r.products(),
        r.productsScored(),
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
        "rule: buy when on sale and (at or within %.0f%% of the year's low, or ≤ %.2f of the year"
            + " below); wait when not on sale, or on sale with ≥ %.2f of the year below, and sales"
            + " recur; ≥ %d points — thresholds fixed before this run",
        (r.thresholds().nearLowFactor() - 1) * 100,
        r.thresholds().buyPercentile(),
        r.thresholds().waitPercentile(),
        r.thresholds().minObservations());
    line(
        sb,
        "judged over the next %d days; a drop is %.0f%% or more below today; the first %d days are"
            + " warm-up and not scored",
        s.horizonDays(),
        s.dropTolerance() * 100,
        s.warmupDays());
    line(
        sb,
        "scored: %,d product-days. The features behind them rest on %,d observations, %,d"
            + " (%.1f%%) synthetic; %,d scored days (%.1f%%) are judged against at least one"
            + " observed day",
        r.scoredDays(),
        r.totalPoints(),
        r.syntheticPoints(),
        pct(r.totalPoints() == 0 ? Double.NaN : (double) r.syntheticPoints() / r.totalPoints()),
        r.scoredDaysReachingObserved(),
        pct(
            r.scoredDays() == 0
                ? Double.NaN
                : (double) r.scoredDaysReachingObserved() / r.scoredDays()));

    line(sb, "");
    line(sb, "headline (%d days):", s.horizonDays());
    tallyHeader(sb);
    tallyRow(sb, r.headline().rule());
    tallyRow(sb, r.headline().alwaysBuy());
    tallyRow(sb, r.headline().belowMedian());
    line(sb, "  on the %,d days the rule spoke:", r.headline().rule().calls());
    tallyRow(sb, r.alwaysBuyOnRuleDays());
    tallyRow(sb, r.belowMedianOnRuleDays());
    line(
        sb,
        "  base rate: a drop of %.0f%% or more followed %.1f%% of scored days",
        s.dropTolerance() * 100,
        pct(r.headline().alwaysBuy().baseRate()));

    line(sb, "");
    line(
        sb,
        "by horizon (drop ≥ %.0f%%; hit rate, with coverage for the rule):",
        s.dropTolerance() * 100);
    line(
        sb,
        "  %5s %9s %14s %11s %17s %9s",
        "days",
        "scored",
        "rule",
        "coverage",
        "buy below median",
        "always");
    r.byHorizon()
        .forEach(
            (h, c) ->
                line(
                    sb,
                    "  %5d %9d %14s %11s %17s %9s",
                    h,
                    c.rule().days(),
                    fmt(c.rule().hitRate()),
                    fmt(c.rule().coverage()),
                    fmt(c.belowMedian().hitRate()),
                    fmt(c.alwaysBuy().hitRate())));

    line(sb, "");
    line(sb, "by drop tolerance (%d days):", s.horizonDays());
    line(
        sb,
        "  %5s %9s %14s %11s %17s %9s",
        "drop",
        "scored",
        "rule",
        "coverage",
        "buy below median",
        "always");
    r.byTolerance()
        .forEach(
            (tol, c) ->
                line(
                    sb,
                    "  %4.0f%% %9d %14s %11s %17s %9s",
                    tol * 100,
                    c.rule().days(),
                    fmt(c.rule().hitRate()),
                    fmt(c.rule().coverage()),
                    fmt(c.belowMedian().hitRate()),
                    fmt(c.alwaysBuy().hitRate())));

    line(sb, "");
    line(
        sb,
        "buy-percentile sweep (wait %.2f, %d days):",
        r.thresholds().waitPercentile(),
        s.horizonDays());
    sweep(sb, r.buySweep());
    line(sb, "");
    line(
        sb,
        "wait-percentile sweep (buy %.2f, %d days):",
        r.thresholds().buyPercentile(),
        s.horizonDays());
    sweep(sb, r.waitSweep());

    line(sb, "");
    line(
        sb,
        "sale-window sweep (sale windows in the year before a wait is advice; %d days):",
        s.horizonDays());
    sweep(sb, r.saleWindowSweep());

    line(sb, "");
    line(
        sb,
        "reason codes at the operating point (%d days; a call may carry several):",
        s.horizonDays());
    line(sb, "  %-18s %7s %7s %8s %9s", "code", "buys", "waits", "neutral", "hit rate");
    for (CodeRow c : r.codes()) {
      line(
          sb,
          "  %-18s %7d %7d %8d %9s",
          c.code(),
          c.buys(),
          c.waits(),
          c.neutrals(),
          fmt(c.hitRate()));
    }

    line(sb, "");
    line(
        sb,
        "following the advice from every scored day for up to %d days (paid ÷ today's price):",
        s.horizonDays());
    line(
        sb,
        "  %-18s %8s %11s %10s %8s %8s %10s",
        "strategy",
        "starts",
        "mean paid",
        "paid less",
        "same",
        "more",
        "unresolved");
    policy(sb, r.rulePolicy());
    policy(sb, r.alwaysBuyPolicy());
    policy(sb, r.belowMedianPolicy());

    line(sb, "");
    line(sb, "per product (%d days; hit rates):", s.horizonDays());
    line(
        sb,
        "  %-34s %6s %16s %7s %7s %7s %10s",
        "product",
        "days",
        "buy/wait/neutral",
        "rule",
        "median",
        "always",
        "synthetic");
    for (ProductRow p : r.perProduct()) {
      line(
          sb,
          "  %-34s %6d %16s %7s %7s %7s %9.1f%%",
          trim(p.name(), 34),
          p.rule().days(),
          p.rule().buys() + "/" + p.rule().waits() + "/" + p.rule().neutrals(),
          fmt(p.rule().hitRate()),
          fmt(p.belowMedian().hitRate()),
          fmt(p.alwaysBuy().hitRate()),
          pct(p.totalPoints() == 0 ? Double.NaN : (double) p.syntheticPoints() / p.totalPoints()));
    }
    return sb.toString();
  }

  private static void tallyHeader(StringBuilder sb) {
    line(
        sb,
        "  %-18s %7s %7s %9s %9s %6s %7s %6s %8s",
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

  private static void tallyRow(StringBuilder sb, Tally t) {
    line(
        sb,
        "  %-18s %7d %7d %9s %9s %6d %7s %6d %8s",
        t.strategy(),
        t.days(),
        t.calls(),
        fmt(t.coverage()),
        fmt(t.hitRate()),
        t.buys(),
        fmt(t.buyPrecision()),
        t.waits(),
        fmt(t.waitPrecision()));
  }

  private static void sweep(StringBuilder sb, java.util.List<SweepPoint> points) {
    line(
        sb,
        "  %9s %7s %9s %9s %6s %7s %6s %8s %10s %10s",
        "threshold",
        "calls",
        "coverage",
        "hit rate",
        "buys",
        "buy ok",
        "waits",
        "wait ok",
        "mean paid",
        "paid less");
    for (SweepPoint p : points) {
      Tally t = p.tally();
      line(
          sb,
          "  %9s %7d %9s %9s %6d %7s %6d %8s %10s %9.1f%%",
          p.threshold() == Math.rint(p.threshold())
              ? String.valueOf((int) p.threshold())
              : String.format(Locale.ROOT, "%.2f", p.threshold()),
          t.calls(),
          fmt(t.coverage()),
          fmt(t.hitRate()),
          t.buys(),
          fmt(t.buyPrecision()),
          t.waits(),
          fmt(t.waitPrecision()),
          Double.isNaN(p.policy().meanPaidRatio())
              ? "—"
              : String.format(Locale.ROOT, "%.4f", p.policy().meanPaidRatio()),
          pct(
              p.policy().starts() == 0
                  ? Double.NaN
                  : (double) p.policy().paidLess() / p.policy().starts()));
    }
  }

  private static void policy(StringBuilder sb, Policy p) {
    line(
        sb,
        "  %-18s %8d %11s %9.1f%% %7.1f%% %7.1f%% %10d",
        p.strategy(),
        p.starts(),
        Double.isNaN(p.meanPaidRatio())
            ? "—"
            : String.format(Locale.ROOT, "%.4f", p.meanPaidRatio()),
        pct(p.starts() == 0 ? Double.NaN : (double) p.paidLess() / p.starts()),
        pct(p.starts() == 0 ? Double.NaN : (double) p.paidSame() / p.starts()),
        pct(p.starts() == 0 ? Double.NaN : (double) p.paidMore() / p.starts()),
        p.unresolved());
  }

  private static String fmt(double rate) {
    return Double.isNaN(rate) ? "—" : String.format(Locale.ROOT, "%.3f", rate);
  }

  private static double pct(double share) {
    return Double.isNaN(share) ? Double.NaN : share * 100;
  }

  private static String trim(String s, int width) {
    return s.length() <= width ? s : s.substring(0, width - 1) + "…";
  }

  private static void line(StringBuilder sb, String format, Object... args) {
    sb.append(String.format(Locale.ROOT, format, args)).append(System.lineSeparator());
  }
}
