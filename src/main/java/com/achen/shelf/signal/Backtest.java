package com.achen.shelf.signal;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.Retailer;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.ObservationDao;
import com.achen.shelf.db.ProductDao;
import com.achen.shelf.db.RollupDao;
import com.achen.shelf.db.RollupDao.Rollup;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The backtest: for every day of the category's history, what the rule <em>would have said</em>
 * with only the data up to that day, judged by what the price then did, beside two naive baselines.
 *
 * <p><b>No look-ahead.</b> The row the rule sees for day {@code d} is recomputed by the rollup
 * statement itself with {@code as_of} = the end of {@code d} ({@link RollupDao#computeProducts}) —
 * the same SQL that writes {@code price_rollups}, with a select in place of the upsert — so its
 * list price, percentile, windows and sale history know nothing after {@code d}. The stored row is
 * never read: it already knows the future.
 *
 * <p><b>The grid.</b> One recompute per day for every product at once; the result is each product's
 * daily series (its current in-stock price at the end of each day) with a full as-of rollup row
 * behind every point. Everything after that is arithmetic over the grid.
 *
 * <p><b>Judging a call.</b> On a day the product is in stock, look {@code horizonDays} ahead: a
 * <em>drop</em> is an in-stock price at or below today's × (1 − {@code dropTolerance}). A {@code
 * buy} is a hit if no drop comes; a {@code wait} is a hit if one does; {@code neutral} abstains.
 * Hit rate is hits over calls; coverage is calls over scored days. Because the baselines never
 * abstain, each is also tallied on just the days the rule spoke, so the comparison is on the same
 * days.
 *
 * <p><b>Baselines.</b> <em>Always buy</em> says buy every day — its hit rate is the share of days
 * with no drop ahead, the base rate. <em>Buy below median</em> says buy when today's price is
 * strictly below the median of the product's daily prices over the trailing 365 days up to today
 * (computed here, from the series, not from a rollup column), else wait.
 *
 * <p><b>Following the advice.</b> A shopper who starts on day {@code d} and does what a strategy
 * says — waits while it says wait, buys the first day it does not (no advice means buy), and buys
 * regardless at the horizon — pays some price; the ratio of that to day {@code d}'s price says what
 * following the strategy was worth. Reported as the mean ratio and the share of starts that paid
 * less, the same, or more.
 *
 * <p>The first {@code warmupDays} days are not scored by any strategy: a percentile over a week of
 * history means nothing, and the baselines deserve the same footing. Every number states the
 * synthetic share of the history it rests on.
 */
public final class Backtest {

  private static final Logger log = LoggerFactory.getLogger(Backtest.class);

  /** The harness's knobs. */
  public record Settings(int horizonDays, int warmupDays, double dropTolerance, int parallelism) {
    /** Judge over 30 days after a 90-day warm-up; a drop is 2% or more; four recomputes at once. */
    public static Settings defaults() {
      return new Settings(30, 90, 0.02, 4);
    }

    public Settings {
      if (horizonDays < 1 || warmupDays < 0 || parallelism < 1) {
        throw new IllegalArgumentException("horizon and parallelism ≥ 1, warm-up ≥ 0");
      }
      if (dropTolerance < 0 || dropTolerance >= 1) {
        throw new IllegalArgumentException("dropTolerance must be within [0, 1)");
      }
    }
  }

  /** The days the grid covers and where the synthetic history ends and the observed begins. */
  public record Span(
      LocalDate firstDay,
      LocalDate lastDay,
      LocalDate firstObservedDay,
      LocalDate lastSyntheticDay) {
    public int days() {
      return (int) ChronoUnit.DAYS.between(firstDay, lastDay) + 1;
    }

    public LocalDate day(int index) {
      return firstDay.plusDays(index);
    }
  }

  /** One product's daily series, read off its as-of rows. Null price = no live listing yet. */
  public static final class Series {
    final Integer[] price;
    final boolean[] inStock;
    final double[] trailingMedian;

    Series(Rollup[] rows) {
      price = new Integer[rows.length];
      inStock = new boolean[rows.length];
      for (int i = 0; i < rows.length; i++) {
        price[i] = rows[i].currentPriceCents();
        inStock[i] = Boolean.TRUE.equals(rows[i].currentInStock());
      }
      trailingMedian = trailingMedians(price, inStock);
    }

    /** The median of the in-stock prices over the 365 days up to and including each day. */
    private static double[] trailingMedians(Integer[] price, boolean[] inStock) {
      double[] out = new double[price.length];
      for (int i = 0; i < price.length; i++) {
        List<Integer> window = new ArrayList<>();
        for (int j = Math.max(0, i - 364); j <= i; j++) {
          if (price[j] != null && inStock[j]) {
            window.add(price[j]);
          }
        }
        if (window.isEmpty()) {
          out[i] = Double.NaN;
          continue;
        }
        int[] sorted = window.stream().mapToInt(Integer::intValue).sorted().toArray();
        int n = sorted.length;
        out[i] = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
      }
      return out;
    }

    boolean buyable(int day) {
      return price[day] != null && inStock[day];
    }
  }

  /** Every product's as-of rollup row for every day of the span, and the series read off them. */
  public static final class Grid {
    private final Span span;
    private final Map<Long, String> names;
    private final Map<Long, Rollup[]> rows;
    private final Map<Long, Series> series;

    public Grid(Span span, Map<Long, String> names, Map<Long, Rollup[]> rows) {
      this.span = span;
      this.names = new LinkedHashMap<>(names);
      this.rows = new LinkedHashMap<>(rows);
      this.series = new LinkedHashMap<>();
      rows.forEach(
          (id, r) -> {
            if (r.length != span.days()) {
              throw new IllegalArgumentException("product " + id + " has " + r.length + " rows");
            }
            series.put(id, new Series(r));
          });
    }

    public Span span() {
      return span;
    }

    public List<Long> products() {
      return List.copyOf(rows.keySet());
    }

    public String name(long product) {
      return names.getOrDefault(product, "#" + product);
    }

    public Rollup at(long product, int day) {
      return rows.get(product)[day];
    }

    Series series(long product) {
      return series.get(product);
    }
  }

  /** What to say on a day, given the as-of row and the series so far. */
  public interface Strategy {
    String name();

    Signal call(Rollup asOf, Series series, int day);
  }

  /** The rule itself. */
  public static Strategy rule(DealRule rule) {
    return new Strategy() {
      @Override
      public String name() {
        return "rule";
      }

      @Override
      public Signal call(Rollup asOf, Series series, int day) {
        return rule.decide(asOf).signal();
      }
    };
  }

  /** Buy, every day. */
  public static final Strategy ALWAYS_BUY =
      new Strategy() {
        @Override
        public String name() {
          return "always buy";
        }

        @Override
        public Signal call(Rollup asOf, Series series, int day) {
          return Signal.BUY;
        }
      };

  /** Buy when today's price is strictly below the trailing-year median of the series, else wait. */
  public static final Strategy BELOW_MEDIAN =
      new Strategy() {
        @Override
        public String name() {
          return "buy below median";
        }

        @Override
        public Signal call(Rollup asOf, Series series, int day) {
          return series.price[day] < series.trailingMedian[day] ? Signal.BUY : Signal.WAIT;
        }
      };

  /** One strategy's calls at one horizon, judged. */
  public record Tally(
      String strategy,
      int horizon,
      int days,
      int drops,
      int buys,
      int waits,
      int neutrals,
      int buyHits,
      int waitHits) {
    public int calls() {
      return buys + waits;
    }

    public int hits() {
      return buyHits + waitHits;
    }

    public double hitRate() {
      return calls() == 0 ? Double.NaN : (double) hits() / calls();
    }

    public double coverage() {
      return days == 0 ? Double.NaN : (double) calls() / days;
    }

    public double buyPrecision() {
      return buys == 0 ? Double.NaN : (double) buyHits / buys;
    }

    public double waitPrecision() {
      return waits == 0 ? Double.NaN : (double) waitHits / waits;
    }

    /** The share of scored days with a drop ahead — what "always buy" gets wrong. */
    public double baseRate() {
      return days == 0 ? Double.NaN : (double) drops / days;
    }
  }

  /** What following a strategy from every scored day cost, relative to buying on the spot. */
  public record Policy(
      String strategy,
      int horizon,
      int starts,
      int unresolved,
      double meanPaidRatio,
      int paidLess,
      int paidSame,
      int paidMore) {}

  /** A strategy at one horizon with the baselines beside it. */
  public record Comparison(Tally rule, Tally alwaysBuy, Tally belowMedian) {}

  /** The rule at one threshold: its calls judged, and what following them paid. */
  public record SweepPoint(double threshold, Tally tally, Policy policy) {}

  /** How the calls carrying one reason code fared. */
  public record CodeRow(ReasonCode code, int buys, int waits, int neutrals, int hits) {
    public double hitRate() {
      return buys + waits == 0 ? Double.NaN : (double) hits / (buys + waits);
    }
  }

  /** One product's numbers at the headline horizon. */
  public record ProductRow(
      long productId,
      String name,
      Tally rule,
      Tally alwaysBuy,
      Tally belowMedian,
      long syntheticPoints,
      long totalPoints) {}

  /** The whole evaluation. */
  public record Report(
      Settings settings,
      Span span,
      int products,
      int productsScored,
      long syntheticPoints,
      long totalPoints,
      int scoredDays,
      int scoredDaysReachingObserved,
      DealRule.Thresholds thresholds,
      Comparison headline,
      Tally alwaysBuyOnRuleDays,
      Tally belowMedianOnRuleDays,
      Map<Integer, Comparison> byHorizon,
      Map<Double, Comparison> byTolerance,
      List<SweepPoint> buySweep,
      List<SweepPoint> waitSweep,
      List<SweepPoint> saleWindowSweep,
      List<CodeRow> codes,
      Policy rulePolicy,
      Policy alwaysBuyPolicy,
      Policy belowMedianPolicy,
      List<ProductRow> perProduct) {
    public Report {
      byHorizon = Map.copyOf(byHorizon);
      byTolerance = Map.copyOf(byTolerance);
      buySweep = List.copyOf(buySweep);
      waitSweep = List.copyOf(waitSweep);
      saleWindowSweep = List.copyOf(saleWindowSweep);
      codes = List.copyOf(codes);
      perProduct = List.copyOf(perProduct);
    }
  }

  /** The horizons every report tabulates, besides the headline. */
  public static final List<Integer> HORIZONS = List.of(7, 14, 30);

  /** The tolerances every report tabulates, besides the headline. */
  public static final List<Double> TOLERANCES = List.of(0.0, 0.02, 0.05);

  /** The buy percentiles swept (all below the default wait percentile). */
  public static final List<Double> BUY_SWEEP =
      List.of(0.0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.40);

  /** The wait percentiles swept (all above the default buy percentile). */
  public static final List<Double> WAIT_SWEEP =
      List.of(0.30, 0.40, 0.50, 0.60, 0.70, 0.80, 0.90, 1.0);

  /**
   * The sale-window bars swept: how many sales a product's trailing year must hold before a wait is
   * advice. Under a Poisson model of sale starts, a product with k windows a year sees one within
   * 30 days with probability 1 − e^(−30k/365): 9 windows make it more likely than not.
   */
  public static final List<Integer> SALE_WINDOW_SWEEP = List.of(1, 3, 5, 7, 9, 13);

  private final Database db;
  private final Settings settings;

  public Backtest(Database db, Settings settings) {
    this.db = db;
    this.settings = settings;
  }

  /** The as-of instant for a day: its last microsecond, UTC — every observation of the day. */
  public static Instant endOfDay(LocalDate day) {
    return day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1_000);
  }

  /**
   * Builds the grid: the category's products, every day from its first observation to its last, one
   * recompute per day for all products at once, {@code parallelism} days in flight. Empty when the
   * category has no observations.
   */
  public Grid grid(CategoryConfig category) throws SQLException {
    List<String> retailers = category.retailers().stream().map(Retailer::name).toList();
    Map<Long, String> names = new LinkedHashMap<>();
    for (ProductDao.Row p : new ProductDao(db).list(category.name())) {
      names.put(p.id(), p.canonicalName());
    }
    ObservationDao.Span observed;
    try (Connection c = db.connection()) {
      observed =
          ObservationDao.span(c, retailers)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "no price observations at " + category.name() + "'s retailers"));
    }
    Span span =
        new Span(
            utcDate(observed.first()),
            utcDate(observed.last()),
            observed.firstObserved() == null ? null : utcDate(observed.firstObserved()),
            observed.lastSynthetic() == null ? null : utcDate(observed.lastSynthetic()));
    List<Long> ids = List.copyOf(names.keySet());
    long started = System.nanoTime();
    log.info(
        "backtest grid for {}: {} products × {} days ({} → {}), {} recomputes in flight",
        category.name(),
        ids.size(),
        span.days(),
        span.firstDay(),
        span.lastDay(),
        settings.parallelism());

    Map<Long, Rollup[]> rows = new LinkedHashMap<>();
    ids.forEach(id -> rows.put(id, new Rollup[span.days()]));
    ExecutorService pool =
        Executors.newFixedThreadPool(settings.parallelism(), Thread.ofVirtual().factory());
    try {
      List<Future<List<Rollup>>> futures = new ArrayList<>(span.days());
      for (int i = 0; i < span.days(); i++) {
        Instant asOf = endOfDay(span.day(i));
        futures.add(
            pool.submit(
                () -> {
                  try (Connection c = db.connection()) {
                    return RollupDao.computeProducts(c, ids, asOf);
                  }
                }));
      }
      for (int i = 0; i < futures.size(); i++) {
        for (Rollup r : futures.get(i).get()) {
          rows.get(r.productId())[i] = r;
        }
        if ((i + 1) % 50 == 0 || i + 1 == futures.size()) {
          log.info("  {} / {} days recomputed", i + 1, futures.size());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while building the backtest grid", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof SQLException sql) {
        throw sql;
      }
      throw new IllegalStateException("backtest recompute failed", e.getCause());
    } finally {
      pool.shutdownNow();
    }
    log.info("backtest grid built in {} ms", (System.nanoTime() - started) / 1_000_000);
    return new Grid(span, names, rows);
  }

  private static LocalDate utcDate(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC).toLocalDate();
  }

  // ---------------------------------------------------------------------------------------------
  // Evaluation: pure functions over the grid.
  // ---------------------------------------------------------------------------------------------

  /** What the price did after a day, as far as judging a call needs. */
  public enum Ahead {
    /** A lower buyable price — lower by at least the tolerance — came within the horizon. */
    DROP,
    /** No such price came. */
    NO_DROP,
    /** Today was not buyable, the window ran past the data, or it held no buyable day. */
    UNJUDGEABLE
  }

  /** What came within {@code horizon} days of {@code day}. */
  static Ahead ahead(Series s, int day, int horizon, double tolerance) {
    if (day + horizon >= s.price.length || !s.buyable(day)) {
      return Ahead.UNJUDGEABLE;
    }
    Integer min = null;
    for (int j = day + 1; j <= day + horizon; j++) {
      if (s.buyable(j) && (min == null || s.price[j] < min)) {
        min = s.price[j];
      }
    }
    if (min == null) {
      return Ahead.UNJUDGEABLE;
    }
    int today = s.price[day];
    return min < today && today - min >= tolerance * today ? Ahead.DROP : Ahead.NO_DROP;
  }

  /** The days scored at a horizon: after the warm-up, buyable, with a judgeable window. */
  private static boolean scored(Series s, int day, int horizon, double tolerance, int warmup) {
    return day >= warmup && ahead(s, day, horizon, tolerance) != Ahead.UNJUDGEABLE;
  }

  /** Every product and scored day. */
  private static boolean allDays(Long product, Integer day) {
    return true;
  }

  /** Tallies a strategy over the grid at a horizon, on the days {@code include} admits. */
  public static Tally tally(
      Grid grid,
      Strategy strategy,
      int horizon,
      double tolerance,
      int warmup,
      BiPredicate<Long, Integer> include) {
    int days = 0;
    int drops = 0;
    Map<Signal, Integer> byCall = new EnumMap<>(Signal.class);
    int buyHits = 0;
    int waitHits = 0;
    for (long product : grid.products()) {
      Series s = grid.series(product);
      for (int d = 0; d < s.price.length; d++) {
        if (!scored(s, d, horizon, tolerance, warmup) || !include.test(product, d)) {
          continue;
        }
        boolean drop = ahead(s, d, horizon, tolerance) == Ahead.DROP;
        Signal call = strategy.call(grid.at(product, d), s, d);
        days++;
        drops += drop ? 1 : 0;
        byCall.merge(call, 1, Integer::sum);
        if (call == Signal.BUY && !drop) {
          buyHits++;
        } else if (call == Signal.WAIT && drop) {
          waitHits++;
        }
      }
    }
    return new Tally(
        strategy.name(),
        horizon,
        days,
        drops,
        byCall.getOrDefault(Signal.BUY, 0),
        byCall.getOrDefault(Signal.WAIT, 0),
        byCall.getOrDefault(Signal.NEUTRAL, 0),
        buyHits,
        waitHits);
  }

  /** The rule and both baselines at one horizon and tolerance. */
  public static Comparison compare(
      Grid grid, DealRule rule, int horizon, double tolerance, int warmup) {
    return new Comparison(
        tally(grid, rule(rule), horizon, tolerance, warmup, Backtest::allDays),
        tally(grid, ALWAYS_BUY, horizon, tolerance, warmup, Backtest::allDays),
        tally(grid, BELOW_MEDIAN, horizon, tolerance, warmup, Backtest::allDays));
  }

  /**
   * Follows a strategy from every scored day: wait while it says wait, buy the first day it does
   * not (no advice is no reason not to), buy at the horizon regardless; out-of-stock days cannot be
   * bought on and are waited through, up to fourteen days past the horizon before giving up.
   */
  public static Policy follow(
      Grid grid, Strategy strategy, int horizon, double tolerance, int warmup) {
    int starts = 0;
    int unresolved = 0;
    double ratioSum = 0;
    int less = 0;
    int same = 0;
    int more = 0;
    for (long product : grid.products()) {
      Series s = grid.series(product);
      for (int d = 0; d < s.price.length; d++) {
        if (!scored(s, d, horizon, tolerance, warmup)) {
          continue;
        }
        Integer paid = null;
        for (int t = d; t < s.price.length && t <= d + horizon + 14; t++) {
          if (!s.buyable(t)) {
            continue;
          }
          if (t >= d + horizon || strategy.call(grid.at(product, t), s, t) != Signal.WAIT) {
            paid = s.price[t];
            break;
          }
        }
        if (paid == null) {
          unresolved++;
          continue;
        }
        starts++;
        double ratio = (double) paid / s.price[d];
        ratioSum += ratio;
        if (paid < s.price[d]) {
          less++;
        } else if (paid > s.price[d]) {
          more++;
        } else {
          same++;
        }
      }
    }
    return new Policy(
        strategy.name(),
        horizon,
        starts,
        unresolved,
        starts == 0 ? Double.NaN : ratioSum / starts,
        less,
        same,
        more);
  }

  /** How the calls carrying each reason code fared at the headline horizon. */
  public static List<CodeRow> byReason(
      Grid grid, DealRule rule, int horizon, double tolerance, int warmup) {
    Map<ReasonCode, Map<Signal, Integer>> calls = new EnumMap<>(ReasonCode.class);
    Map<ReasonCode, Integer> hits = new EnumMap<>(ReasonCode.class);
    for (long product : grid.products()) {
      Series s = grid.series(product);
      for (int d = 0; d < s.price.length; d++) {
        if (!scored(s, d, horizon, tolerance, warmup)) {
          continue;
        }
        boolean drop = ahead(s, d, horizon, tolerance) == Ahead.DROP;
        DealRule.Decision decision = rule.decide(grid.at(product, d));
        boolean hit =
            (decision.signal() == Signal.BUY && !drop)
                || (decision.signal() == Signal.WAIT && drop);
        for (ReasonCode code : decision.reasons()) {
          calls
              .computeIfAbsent(code, k -> new EnumMap<>(Signal.class))
              .merge(decision.signal(), 1, Integer::sum);
          if (hit) {
            hits.merge(code, 1, Integer::sum);
          }
        }
      }
    }
    List<CodeRow> rows = new ArrayList<>();
    calls.forEach(
        (code, c) ->
            rows.add(
                new CodeRow(
                    code,
                    c.getOrDefault(Signal.BUY, 0),
                    c.getOrDefault(Signal.WAIT, 0),
                    c.getOrDefault(Signal.NEUTRAL, 0),
                    hits.getOrDefault(code, 0))));
    return rows;
  }

  /** The whole report for a grid, with the rule's thresholds as the operating point. */
  public Report evaluate(Grid grid, DealRule rule) {
    int horizon = settings.horizonDays();
    double tolerance = settings.dropTolerance();
    int warmup = settings.warmupDays();
    DealRule.Thresholds t = rule.thresholds();

    Comparison headline = compare(grid, rule, horizon, tolerance, warmup);
    Strategy theRule = rule(rule);
    BiPredicate<Long, Integer> ruleSpoke =
        (product, day) ->
            theRule.call(grid.at(product, day), grid.series(product), day) != Signal.NEUTRAL;
    Tally alwaysOnRuleDays = tally(grid, ALWAYS_BUY, horizon, tolerance, warmup, ruleSpoke);
    Tally medianOnRuleDays = tally(grid, BELOW_MEDIAN, horizon, tolerance, warmup, ruleSpoke);

    Map<Integer, Comparison> byHorizon = new LinkedHashMap<>();
    for (int h : horizonsToReport(horizon)) {
      byHorizon.put(h, h == horizon ? headline : compare(grid, rule, h, tolerance, warmup));
    }
    Map<Double, Comparison> byTolerance = new LinkedHashMap<>();
    for (double tol : tolerancesToReport(tolerance)) {
      byTolerance.put(tol, tol == tolerance ? headline : compare(grid, rule, horizon, tol, warmup));
    }
    List<SweepPoint> buySweep = new ArrayList<>();
    for (double b : BUY_SWEEP) {
      if (b < t.waitPercentile()) {
        buySweep.add(sweepPoint(grid, new DealRule(t.withBuyPercentile(b)), b));
      }
    }
    List<SweepPoint> waitSweep = new ArrayList<>();
    for (double w : WAIT_SWEEP) {
      if (w > t.buyPercentile()) {
        waitSweep.add(sweepPoint(grid, new DealRule(t.withWaitPercentile(w)), w));
      }
    }
    List<SweepPoint> saleWindowSweep = new ArrayList<>();
    for (int k : SALE_WINDOW_SWEEP) {
      saleWindowSweep.add(sweepPoint(grid, new DealRule(t.withMinSaleWindows(k)), k));
    }

    long synthetic = 0;
    long total = 0;
    int scoredDays = 0;
    int reachingObserved = 0;
    int productsScored = 0;
    List<ProductRow> perProduct = new ArrayList<>();
    for (long product : grid.products()) {
      Series s = grid.series(product);
      long productSynthetic = 0;
      long productTotal = 0;
      int productDays = 0;
      for (int d = 0; d < s.price.length; d++) {
        if (!scored(s, d, horizon, tolerance, warmup)) {
          continue;
        }
        Rollup r = grid.at(product, d);
        productSynthetic += r.synthetic365d();
        productTotal += r.observations365d();
        productDays++;
        if (grid.span().firstObservedDay() != null
            && !grid.span().day(d + horizon).isBefore(grid.span().firstObservedDay())) {
          reachingObserved++;
        }
      }
      if (productDays == 0) {
        continue;
      }
      productsScored++;
      scoredDays += productDays;
      synthetic += productSynthetic;
      total += productTotal;
      BiPredicate<Long, Integer> only = (p, d) -> p == product;
      perProduct.add(
          new ProductRow(
              product,
              grid.name(product),
              tally(grid, theRule, horizon, tolerance, warmup, only),
              tally(grid, ALWAYS_BUY, horizon, tolerance, warmup, only),
              tally(grid, BELOW_MEDIAN, horizon, tolerance, warmup, only),
              productSynthetic,
              productTotal));
    }

    return new Report(
        settings,
        grid.span(),
        grid.products().size(),
        productsScored,
        synthetic,
        total,
        scoredDays,
        reachingObserved,
        t,
        headline,
        alwaysOnRuleDays,
        medianOnRuleDays,
        byHorizon,
        byTolerance,
        buySweep,
        waitSweep,
        saleWindowSweep,
        byReason(grid, rule, horizon, tolerance, warmup),
        follow(grid, theRule, horizon, tolerance, warmup),
        follow(grid, ALWAYS_BUY, horizon, tolerance, warmup),
        follow(grid, BELOW_MEDIAN, horizon, tolerance, warmup),
        perProduct);
  }

  private SweepPoint sweepPoint(Grid grid, DealRule swept, double threshold) {
    Strategy strategy = rule(swept);
    int horizon = settings.horizonDays();
    double tolerance = settings.dropTolerance();
    int warmup = settings.warmupDays();
    return new SweepPoint(
        threshold,
        tally(grid, strategy, horizon, tolerance, warmup, Backtest::allDays),
        follow(grid, strategy, horizon, tolerance, warmup));
  }

  private static List<Integer> horizonsToReport(int headline) {
    List<Integer> out = new ArrayList<>(HORIZONS);
    if (!out.contains(headline)) {
      out.add(headline);
    }
    out.sort(null);
    return out;
  }

  private static List<Double> tolerancesToReport(double headline) {
    List<Double> out = new ArrayList<>(TOLERANCES);
    if (!out.contains(headline)) {
      out.add(headline);
    }
    out.sort(null);
    return out;
  }

  /** For tests: the trailing medians a series computed. */
  static double[] trailingMedians(Grid grid, long product) {
    return Arrays.copyOf(
        grid.series(product).trailingMedian, grid.series(product).trailingMedian.length);
  }
}
