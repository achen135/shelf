package com.achen.shelf.signal;

import com.achen.shelf.db.RollupDao.Rollup;
import java.util.ArrayList;
import java.util.List;

/**
 * The buy / wait / neutral rule: a readable function of a product's {@code price_rollups} row.
 *
 * <p>Written down before the backtest existed, with the thresholds below, in the spirit of M3's
 * scorer: hand-set numbers and a reason code for every decision, not a fitted model. What it says:
 *
 * <ul>
 *   <li><b>No call</b> when there is nothing to buy (no price, out of stock) or too little history
 *       to place today's price in a year.
 *   <li><b>Buy</b> when today's price is a sale by the rollup's own definition (at or below 90% of
 *       the year's list price) <em>and</em> it is a good one for this product: at or within a few
 *       percent of the year's low, or with no more than {@code buyPercentile} of the year priced
 *       below it. A sale that is shallow for its product is not a buy — a GMBK 75% at 10% off, when
 *       the cheapest of its variants sat 20% off for most of the year, is not a deal.
 *   <li><b>Wait</b> when the price is not a sale, or is a sale that most of the year beat ({@code
 *       waitPercentile} or more of the year below it), <em>and</em> the product does go on sale —
 *       the trailing year has at least one sale day. A product that never discounts gets no call at
 *       list: nothing says a better price is coming.
 *   <li><b>Neutral</b> otherwise — mostly a sale that is neither near the low nor unusually cheap
 *       for this product.
 * </ul>
 *
 * <p>All time arithmetic uses the row's own {@code as_of}, never the clock, so the same rule gives
 * the same answer on a stored row today and on a recomputed row at a past instant, which is what
 * lets the backtest run the production rule and not a copy. Volatility is deliberately not a
 * feature in v1: on this corpus the year's coefficient of variation sits in a narrow band
 * (0.04–0.14 across every product) that no threshold usefully splits.
 */
public final class DealRule {

  /** The rule's numbers. Defaults were fixed before the backtest was run. */
  public record Thresholds(
      double buyPercentile, double waitPercentile, double nearLowFactor, int minObservations) {

    /** buy ≤ 0.20 of the year below; wait ≥ 0.50; within 5% of the low; 30 points. */
    public static Thresholds defaults() {
      return new Thresholds(0.20, 0.50, 1.05, 30);
    }

    public Thresholds {
      if (buyPercentile < 0 || buyPercentile > 1 || waitPercentile < 0 || waitPercentile > 1) {
        throw new IllegalArgumentException("percentile thresholds must be within [0, 1]");
      }
      if (buyPercentile >= waitPercentile) {
        throw new IllegalArgumentException("buyPercentile must be below waitPercentile");
      }
      if (nearLowFactor < 1) {
        throw new IllegalArgumentException("nearLowFactor must be at least 1");
      }
      if (minObservations < 1) {
        throw new IllegalArgumentException("minObservations must be at least 1");
      }
    }

    /** The same thresholds with a different buy percentile — what the backtest sweeps. */
    public Thresholds withBuyPercentile(double value) {
      return new Thresholds(value, waitPercentile, nearLowFactor, minObservations);
    }

    /** The same thresholds with a different wait percentile. */
    public Thresholds withWaitPercentile(double value) {
      return new Thresholds(buyPercentile, value, nearLowFactor, minObservations);
    }
  }

  /** The call and every reason behind it, in the order the rule considered them. */
  public record Decision(Signal signal, List<ReasonCode> reasons) {
    public Decision {
      reasons = List.copyOf(reasons);
    }

    /** The reason codes by name, as stored. */
    public List<String> reasonNames() {
      return reasons.stream().map(Enum::name).toList();
    }
  }

  private final Thresholds t;

  public DealRule(Thresholds thresholds) {
    this.t = thresholds;
  }

  public static DealRule defaults() {
    return new DealRule(Thresholds.defaults());
  }

  public Thresholds thresholds() {
    return t;
  }

  /** The call for one product's rollup row. */
  public Decision decide(Rollup r) {
    if (r.currentPriceCents() == null) {
      return new Decision(Signal.NEUTRAL, List.of(ReasonCode.NO_PRICE));
    }
    if (!Boolean.TRUE.equals(r.currentInStock())) {
      return new Decision(Signal.NEUTRAL, List.of(ReasonCode.OUT_OF_STOCK));
    }
    if (r.observations365d() < t.minObservations()) {
      return new Decision(Signal.NEUTRAL, List.of(ReasonCode.THIN_HISTORY));
    }
    // With a current in-stock price there is at least one series point, so the year's list
    // price, low and percentile are all present.
    int price = r.currentPriceCents();
    int list = r.listPriceCents();
    int low = r.d365().min();
    double percentile = r.percentile365d();

    boolean onSale = (long) price * 10 <= (long) list * 9; // the line the rollup draws
    boolean yearLow = price <= low;
    boolean nearLow = !yearLow && price <= low * t.nearLowFactor();
    boolean lowPercentile = percentile <= t.buyPercentile();
    boolean highPercentile = percentile >= t.waitPercentile();
    boolean salesRecur = r.saleDays365d() > 0;

    List<ReasonCode> reasons = new ArrayList<>();
    reasons.add(
        onSale
            ? ReasonCode.ON_SALE
            : price >= list ? ReasonCode.AT_LIST : ReasonCode.MILD_DISCOUNT);
    if (yearLow) {
      reasons.add(ReasonCode.YEAR_LOW);
    } else if (nearLow) {
      reasons.add(ReasonCode.NEAR_YEAR_LOW);
    }
    if (lowPercentile) {
      reasons.add(ReasonCode.LOW_PERCENTILE);
    } else if (highPercentile) {
      reasons.add(ReasonCode.HIGH_PERCENTILE);
    }

    Signal signal;
    if (onSale && (yearLow || nearLow || lowPercentile)) {
      signal = Signal.BUY;
    } else if (salesRecur && (!onSale || highPercentile)) {
      signal = Signal.WAIT;
      reasons.add(ReasonCode.SALES_RECUR);
    } else if (onSale) {
      signal = Signal.NEUTRAL;
      reasons.add(ReasonCode.MIDDLING_SALE);
    } else {
      signal = Signal.NEUTRAL;
      reasons.add(ReasonCode.NO_SALE_HISTORY);
    }
    if (r.synthetic365d() * 2 > r.observations365d()) {
      reasons.add(ReasonCode.MOSTLY_SYNTHETIC);
    }
    return new Decision(signal, reasons);
  }
}
