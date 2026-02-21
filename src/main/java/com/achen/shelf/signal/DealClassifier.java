package com.achen.shelf.signal;

import com.achen.shelf.db.RollupDao.Rollup;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The trained alternative to the rule (M12): a logistic regression over the same rollup fields
 * {@link DealRule} reads, fitted on the backtest's own grid, run through the same harness as one
 * more {@link Backtest.Strategy}.
 *
 * <p><b>What it is.</b> One weight per feature and a bias, learned by full-batch gradient descent
 * on the log-loss in plain Java — no library, no randomness, no file format: the model is its
 * weights, printed in the report. The features are read off the as-of row the rule reads, so the
 * comparison is "does a model find more in the <em>same</em> information," not "does more
 * information help":
 *
 * <ol>
 *   <li>price / list price — the rule's on-sale line;
 *   <li>price / the year's low — the rule's near-low test;
 *   <li>price / the year's median;
 *   <li>the year's percentile — the rule's buy and wait thresholds;
 *   <li>sale days in the year / 365 — the rule's "has sales";
 *   <li>sale windows in the year — the rule's "sales recur";
 *   <li>days since the last sale ended / 365 (a full year when none).
 * </ol>
 *
 * Five of the seven — the list and low ratios, the percentile, the sale days and windows — are what
 * the rule reads; the median ratio and the recency are the "one or two more" the plan allowed.
 * Features are standardized by the training set's mean and deviation, so a weight is the change in
 * log-odds of a drop per standard deviation of that feature.
 *
 * <p><b>What it predicts.</b> The probability that a buyable price at least {@code tolerance} lower
 * comes within the horizon — the backtest's own label ({@link Backtest.Ahead#DROP}). The call is
 * the rule's shape: the same three gates first (no price, out of stock, too few points → neutral),
 * then <em>buy</em> when the probability is at or below {@code buyBelow}, <em>wait</em> at or above
 * {@code waitAbove}, neutral between. Two operating points are reported, both fixed before the
 * first run: <em>decisive</em> (0.5 / 0.5 — never abstains, like the baselines) and
 * <em>abstaining</em> (0.4 / 0.6).
 *
 * <p><b>Fixed before the first run on real data</b>, in the spirit of the rule's thresholds: the
 * feature list above, standardization, learning rate 0.5, 3,000 iterations, L2 weight 0.001, the
 * two operating points, and the split ({@link ClassifierEval}). Nothing here was tuned against a
 * held-out day.
 */
public final class DealClassifier {

  /** The training knobs. */
  public record Settings(double learningRate, int iterations, double l2, int minObservations) {
    public static Settings defaults() {
      return new Settings(0.5, 3_000, 1e-3, DealRule.Thresholds.defaults().minObservations());
    }

    public Settings {
      if (learningRate <= 0 || iterations < 1 || l2 < 0 || minObservations < 1) {
        throw new IllegalArgumentException("learningRate > 0, iterations ≥ 1, l2 ≥ 0, points ≥ 1");
      }
    }
  }

  /** The features, in weight order. */
  public static final List<String> FEATURES =
      List.of(
          "price / list",
          "price / year low",
          "price / year median",
          "percentile 365d",
          "sale days 365d / 365",
          "sale windows 365d",
          "days since last sale / 365");

  /** One labeled row of the grid: a scored, gate-passing product-day and what followed it. */
  public record Example(long productId, int day, List<Double> features, boolean drop) {
    public Example {
      features = List.copyOf(features);
      if (features.size() != FEATURES.size()) {
        throw new IllegalArgumentException("expected " + FEATURES.size() + " features");
      }
    }
  }

  private final Settings settings;
  private final double[] mean;
  private final double[] deviation;
  private final double[] weights;
  private final double bias;
  private final double trainingLoss;
  private final int trainedOn;

  private DealClassifier(
      Settings settings,
      double[] mean,
      double[] deviation,
      double[] weights,
      double bias,
      double trainingLoss,
      int trainedOn) {
    this.settings = settings;
    this.mean = mean;
    this.deviation = deviation;
    this.weights = weights;
    this.bias = bias;
    this.trainingLoss = trainingLoss;
    this.trainedOn = trainedOn;
  }

  /**
   * The feature vector of a row, or null when the row fails a gate the rule also applies (no price,
   * out of stock, fewer than {@code minObservations} points) — such a row is never trained on and
   * never called.
   */
  public static double[] features(Rollup r, int minObservations) {
    if (r.currentPriceCents() == null
        || !Boolean.TRUE.equals(r.currentInStock())
        || r.observations365d() < minObservations
        || r.listPriceCents() == null
        || r.d365() == null
        || r.d365().min() == null
        || r.d365().median() == null
        || r.percentile365d() == null) {
      return null;
    }
    double price = r.currentPriceCents();
    double sinceSale =
        r.lastSaleEndedAt() == null
            ? 365
            : Math.min(365, Math.max(0, Duration.between(r.lastSaleEndedAt(), r.asOf()).toDays()));
    return new double[] {
      price / r.listPriceCents(),
      price / r.d365().min(),
      price / r.d365().median(),
      r.percentile365d(),
      r.saleDays365d() / 365.0,
      r.saleWindows().size(),
      sinceSale / 365.0
    };
  }

  /** Fits the weights to the examples. Deterministic: the same rows give the same weights. */
  public static DealClassifier train(List<Example> examples, Settings settings) {
    if (examples.isEmpty()) {
      throw new IllegalArgumentException("nothing to train on");
    }
    int k = FEATURES.size();
    int n = examples.size();
    double[] mean = new double[k];
    double[] deviation = new double[k];
    for (Example e : examples) {
      for (int j = 0; j < k; j++) {
        mean[j] += e.features().get(j) / n;
      }
    }
    for (Example e : examples) {
      for (int j = 0; j < k; j++) {
        double d = e.features().get(j) - mean[j];
        deviation[j] += d * d / n;
      }
    }
    for (int j = 0; j < k; j++) {
      deviation[j] = deviation[j] == 0 ? 1 : Math.sqrt(deviation[j]); // a constant feature stays 0
    }
    double[][] x = new double[n][k];
    double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      double[] f = new double[k];
      for (int j = 0; j < k; j++) {
        f[j] = examples.get(i).features().get(j);
      }
      x[i] = standardize(f, mean, deviation);
      y[i] = examples.get(i).drop() ? 1 : 0;
    }

    double[] w = new double[k];
    double b = 0;
    double loss = Double.NaN;
    for (int iteration = 0; iteration < settings.iterations(); iteration++) {
      double[] gw = new double[k];
      double gb = 0;
      loss = 0;
      for (int i = 0; i < n; i++) {
        double p = sigmoid(dot(w, x[i]) + b);
        double err = p - y[i];
        for (int j = 0; j < k; j++) {
          gw[j] += err * x[i][j] / n;
        }
        gb += err / n;
        loss -=
            (y[i] * Math.log(Math.max(p, 1e-12)) + (1 - y[i]) * Math.log(Math.max(1 - p, 1e-12)))
                / n;
      }
      for (int j = 0; j < k; j++) {
        w[j] -= settings.learningRate() * (gw[j] + settings.l2() * w[j]);
      }
      b -= settings.learningRate() * gb;
    }
    return new DealClassifier(settings, mean, deviation, w, b, loss, n);
  }

  /** The probability of a drop within the horizon, or NaN when the row fails a gate. */
  public double probability(Rollup r) {
    double[] f = features(r, settings.minObservations());
    if (f == null) {
      return Double.NaN;
    }
    return sigmoid(dot(weights, standardize(f, mean, deviation)) + bias);
  }

  /** The model as a strategy at one operating point: buy at or below, wait at or above. */
  public Backtest.Strategy at(String name, double buyBelow, double waitAbove) {
    if (buyBelow > waitAbove) {
      throw new IllegalArgumentException("buyBelow must not exceed waitAbove");
    }
    return new Backtest.Strategy() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Signal call(Rollup asOf, Backtest.Series series, int day) {
        double p = probability(asOf);
        if (Double.isNaN(p)) {
          return Signal.NEUTRAL;
        }
        return p <= buyBelow ? Signal.BUY : p >= waitAbove ? Signal.WAIT : Signal.NEUTRAL;
      }
    };
  }

  /** The weights in standardized space, in {@link #FEATURES} order. */
  public double[] weights() {
    return weights.clone();
  }

  public double bias() {
    return bias;
  }

  /** The mean log-loss over the training rows after the last iteration. */
  public double trainingLoss() {
    return trainingLoss;
  }

  public int trainedOn() {
    return trainedOn;
  }

  public Settings settings() {
    return settings;
  }

  /** The model, readable: one line per feature. */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    for (int j = 0; j < FEATURES.size(); j++) {
      sb.append(
          String.format(
              Locale.ROOT,
              "  %-28s %+.3f   (mean %.3f, sd %.3f)%n",
              FEATURES.get(j),
              weights[j],
              mean[j],
              deviation[j]));
    }
    sb.append(String.format(Locale.ROOT, "  %-28s %+.3f%n", "bias", bias));
    return sb.toString();
  }

  private static double[] standardize(double[] f, double[] mean, double[] deviation) {
    double[] out = new double[f.length];
    for (int j = 0; j < f.length; j++) {
      out[j] = (f[j] - mean[j]) / deviation[j];
    }
    return out;
  }

  private static double dot(double[] a, double[] b) {
    double s = 0;
    for (int j = 0; j < a.length; j++) {
      s += a[j] * b[j];
    }
    return s;
  }

  private static double sigmoid(double z) {
    return 1 / (1 + Math.exp(-z));
  }
}
