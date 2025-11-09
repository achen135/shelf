package com.achen.shelf.resolve;

import com.achen.shelf.crawl.Normalizer;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.ResolutionDao;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Precision and recall of the resolver's decisions against labeled pairs.
 *
 * <p>Each label says whether one offer is one product. The resolver is run exactly as it runs in
 * production — block, score every candidate, take the best — and a labeled pair counts as
 * predicted-positive when the best candidate <em>is</em> the labeled product and its score clears
 * the threshold. So a positive pair is missed (a false negative) both when the score is too low and
 * when a different product outscored it, which is the behaviour being measured; and a negative pair
 * is a false positive only when the resolver would actually have linked it. Pairs whose
 * resolver-best is a product no label covers are counted separately as unjudged.
 *
 * <p>The threshold is swept so the trade-off is visible, not just one operating point. Because the
 * scorer's weights are hand-set, scores cluster at a few levels and the curve has plateaus; that is
 * the honest shape of a rule-based scorer.
 */
public final class ResolutionEval {

  /** One labeled pair, resolved to the rows the resolver sees. */
  public record Pair(String url, String title, Listing listing, Candidate product, boolean match) {}

  /** The confusion counts at one threshold. */
  public record Point(double threshold, int tp, int fp, int fn, int tn) {
    public double precision() {
      return tp + fp == 0 ? 1.0 : (double) tp / (tp + fp);
    }

    public double recall() {
      return tp + fn == 0 ? 1.0 : (double) tp / (tp + fn);
    }

    public double f1() {
      double p = precision();
      double r = recall();
      return p + r == 0 ? 0 : 2 * p * r / (p + r);
    }
  }

  /** A labeled pair the operating point gets wrong. */
  public record Mistake(
      String kind, Pair pair, Optional<Resolver.Scored> best, Resolver.Outcome outcome) {}

  /** The whole evaluation. */
  public record Report(
      int pairs,
      int positives,
      int negatives,
      Resolver.Thresholds thresholds,
      List<Point> sweep,
      Point operating,
      int inReviewBand,
      int unjudgedLinks,
      List<Mistake> mistakes) {
    public Report {
      sweep = List.copyOf(sweep);
      mistakes = List.copyOf(mistakes);
    }
  }

  /** The labels as pairs the resolver can score, plus the ones that could not be resolved. */
  public record Loaded(List<Pair> pairs, List<String> skipped) {
    public Loaded {
      pairs = List.copyOf(pairs);
      skipped = List.copyOf(skipped);
    }
  }

  private ResolutionEval() {}

  /**
   * Joins a label file against the database: the offer by URL, the product by normalized brand and
   * model in {@code catalog}. Every pair found is recorded in {@code resolution_labels}; a label
   * whose offer or product is absent is reported in {@code skipped}, not guessed at.
   */
  public static Loaded load(Database db, Catalog catalog, List<Labels.Label> labels)
      throws SQLException {
    Map<String, Candidate> byIdentity = new HashMap<>();
    for (Candidate c : catalog.all()) {
      byIdentity.put(c.brandNorm() + "|" + c.modelNorm(), c);
    }
    ResolutionDao dao = new ResolutionDao(db);
    List<Pair> pairs = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    db.transaction(
        c -> {
          for (Labels.Label l : labels) {
            Candidate product =
                byIdentity.get(
                    Normalizer.normalize(l.brand()) + "|" + Normalizer.normalize(l.model()));
            if (product == null) {
              skipped.add("no catalog product " + l.brand() + " " + l.model());
              continue;
            }
            Optional<ResolutionDao.PendingOffer> offer = dao.byUrl(c, l.offerUrl());
            if (offer.isEmpty()) {
              skipped.add("no offer at " + l.offerUrl());
              continue;
            }
            dao.upsertLabel(c, offer.get().id(), product.productId(), l.match(), l.note());
            pairs.add(
                new Pair(
                    l.offerUrl(),
                    offer.get().title(),
                    ResolutionRun.toListing(offer.get()),
                    product,
                    l.match()));
          }
          return null;
        });
    return new Loaded(pairs, skipped);
  }

  /**
   * A pair after the resolver has seen it: the score for the labeled product, or -1 if another
   * product was the resolver's best.
   */
  private record Outcome(Pair pair, Optional<Resolver.Scored> best, double scoreForLabeled) {}

  /** Runs the resolver over every pair and tallies it. */
  public static Report evaluate(Resolver resolver, List<Pair> pairs) {
    // Every product any label names for an offer: a resolver-best outside that set is unjudged.
    Map<Long, Set<Long>> labeledProducts = new HashMap<>();
    for (Pair p : pairs) {
      labeledProducts
          .computeIfAbsent(p.listing().offerId(), k -> new HashSet<>())
          .add(p.product().productId());
    }
    List<Outcome> outcomes = new ArrayList<>();
    Set<Long> unjudgedOffers = new HashSet<>();
    int positives = 0;
    for (Pair p : pairs) {
      Resolver.Decision d = resolver.decide(p.listing());
      boolean bestIsLabeled =
          d.best().isPresent() && d.best().get().candidate().productId() == p.product().productId();
      double s = bestIsLabeled ? d.best().get().score().value() : -1;
      outcomes.add(new Outcome(p, d.best(), s));
      if (p.match()) {
        positives++;
      }
      if (d.outcome() == Resolver.Outcome.AUTO
          && !labeledProducts
              .get(p.listing().offerId())
              .contains(d.best().get().candidate().productId())) {
        unjudgedOffers.add(p.listing().offerId());
      }
    }
    int unjudged = unjudgedOffers.size();

    List<Point> sweep = new ArrayList<>();
    for (int i = 0; i <= 20; i++) {
      sweep.add(at(i / 20.0, outcomes));
    }
    Point operating = at(resolver.thresholds().auto(), outcomes);

    int inReviewBand = 0;
    List<Mistake> mistakes = new ArrayList<>();
    for (Outcome o : outcomes) {
      Resolver.Outcome outcome =
          o.scoreForLabeled() < 0
              ? Resolver.Outcome.NONE
              : resolver.outcomeFor(o.scoreForLabeled());
      boolean linked = outcome == Resolver.Outcome.AUTO;
      if (outcome == Resolver.Outcome.REVIEW) {
        inReviewBand++;
      }
      if (o.pair().match() && !linked) {
        mistakes.add(new Mistake("FN", o.pair(), o.best(), outcome));
      } else if (!o.pair().match() && linked) {
        mistakes.add(new Mistake("FP", o.pair(), o.best(), outcome));
      }
    }
    return new Report(
        pairs.size(),
        positives,
        pairs.size() - positives,
        resolver.thresholds(),
        sweep,
        operating,
        inReviewBand,
        unjudged,
        mistakes);
  }

  /** Confusion counts if every labeled pair scoring at or above {@code threshold} were linked. */
  private static Point at(double threshold, List<Outcome> outcomes) {
    int tp = 0;
    int fp = 0;
    int fn = 0;
    int tn = 0;
    for (Outcome o : outcomes) {
      boolean predicted = o.scoreForLabeled() >= 0 && o.scoreForLabeled() >= threshold;
      if (o.pair().match() && predicted) {
        tp++;
      } else if (o.pair().match()) {
        fn++;
      } else if (predicted) {
        fp++;
      } else {
        tn++;
      }
    }
    return new Point(threshold, tp, fp, fn, tn);
  }

  /** The report as text — what the CLI prints and what gets committed next to the labels. */
  public static String render(
      Report r, String category, int catalogSize, int catalogWithSpec, List<String> skipped) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        String.format(
            Locale.ROOT,
            "resolution eval — %s: %d labeled pairs (%d match, %d not) against a catalog of %d"
                + " products (%d with a spec)%n",
            category,
            r.pairs(),
            r.positives(),
            r.negatives(),
            catalogSize,
            catalogWithSpec));
    sb.append(
        String.format(
            Locale.ROOT,
            "operating point: auto >= %.2f, review >= %.2f%n",
            r.thresholds().auto(),
            r.thresholds().review()));
    Point o = r.operating();
    sb.append(
        String.format(
            Locale.ROOT,
            "  precision %.3f  recall %.3f  f1 %.3f   (tp %d, fp %d, fn %d, tn %d)%n",
            o.precision(),
            o.recall(),
            o.f1(),
            o.tp(),
            o.fp(),
            o.fn(),
            o.tn()));
    sb.append(
        String.format(
            Locale.ROOT,
            "  %d labeled pair(s) land in the review band; %d labeled offer(s) would auto-link to"
                + " a product no label covers%n",
            r.inReviewBand(),
            r.unjudgedLinks()));
    sb.append(
        String.format(Locale.ROOT, "%nthreshold sweep (auto threshold; review band ignored):%n"));
    sb.append(
        String.format(
            Locale.ROOT,
            "  %-9s %-9s %-9s %-7s %4s %4s %4s %4s%n",
            "threshold",
            "precision",
            "recall",
            "f1",
            "tp",
            "fp",
            "fn",
            "tn"));
    for (Point p : r.sweep()) {
      sb.append(
          String.format(
              Locale.ROOT,
              "  %-9.2f %-9.3f %-9.3f %-7.3f %4d %4d %4d %4d%n",
              p.threshold(),
              p.precision(),
              p.recall(),
              p.f1(),
              p.tp(),
              p.fp(),
              p.fn(),
              p.tn()));
    }
    if (!r.mistakes().isEmpty()) {
      sb.append(String.format(Locale.ROOT, "%nmistakes at the operating point:%n"));
      for (Mistake m : r.mistakes()) {
        String best =
            m.best()
                .map(
                    b ->
                        String.format(
                            Locale.ROOT,
                            "best=%s %.2f %s",
                            b.candidate().canonicalName(),
                            b.score().value(),
                            b.score().reasons()))
                .orElse("no candidate");
        sb.append(
            String.format(
                Locale.ROOT,
                "  %s  %s × %s  [%s]  %s%n",
                m.kind(),
                m.pair().title(),
                m.pair().product().canonicalName(),
                m.outcome(),
                best));
      }
    }
    if (!skipped.isEmpty()) {
      sb.append(String.format(Locale.ROOT, "%n%d label(s) skipped:%n", skipped.size()));
      skipped.forEach(x -> sb.append("  ").append(x).append(System.lineSeparator()));
    }
    return sb.toString();
  }
}
