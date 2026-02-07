package com.achen.shelf.mention;

import com.achen.shelf.crawl.Normalizer;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.MentionDao;
import com.achen.shelf.resolve.Candidate;
import com.achen.shelf.resolve.Catalog;
import com.achen.shelf.resolve.MentionMatcher;
import com.achen.shelf.resolve.ResolutionEval;
import com.achen.shelf.resolve.Resolver;
import com.achen.shelf.resolve.Scorer;
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
 * Precision and recall of mention resolution against the frozen label file (M9) — {@code shelf eval
 * mention-resolution}. The same shape as {@link ResolutionEval}: every labeled pair scored by the
 * matcher, a confusion at the auto threshold and across a sweep, the review band, the mistakes with
 * their reasons, and the pairs the matcher linked that no label judges.
 */
public final class MentionEval {

  /** A labeled pair joined to the database. */
  public record Pair(
      MentionLabels.MatchLabel label, MentionDao.Raw raw, Candidate product, boolean match) {}

  /** What loading found. */
  public record Loaded(List<Pair> pairs, List<String> skipped) {
    public Loaded {
      pairs = List.copyOf(pairs);
      skipped = List.copyOf(skipped);
    }
  }

  /** One wrong call. */
  public record Mistake(String kind, Pair pair, Scorer.Score score, Resolver.Outcome outcome) {}

  /** The report. */
  public record Report(
      int pairs,
      int positives,
      int negatives,
      Resolver.Thresholds thresholds,
      List<ResolutionEval.Point> sweep,
      ResolutionEval.Point operating,
      int inReviewBand,
      int unjudgedLinks,
      List<Mistake> mistakes) {
    public Report {
      sweep = List.copyOf(sweep);
      mistakes = List.copyOf(mistakes);
    }
  }

  private MentionEval() {}

  /**
   * Joins the labels against the database: the raw mention by (source, source_id), the product by
   * normalized brand and model. Every pair found is recorded in {@code mention_labels}; a label
   * whose text or product is absent is reported, not guessed at.
   */
  public static Loaded load(Database db, Catalog catalog, List<MentionLabels.MatchLabel> labels)
      throws SQLException {
    Map<String, Candidate> byIdentity = new HashMap<>();
    for (Candidate c : catalog.all()) {
      byIdentity.put(c.brandNorm() + "|" + c.modelNorm(), c);
    }
    MentionDao dao = new MentionDao(db);
    List<Pair> pairs = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    db.transaction(
        c -> {
          for (MentionLabels.MatchLabel l : labels) {
            Candidate product =
                byIdentity.get(
                    Normalizer.normalize(l.brand()) + "|" + Normalizer.normalize(l.model()));
            if (product == null) {
              skipped.add("no catalog product " + l.brand() + " " + l.model());
              continue;
            }
            Optional<MentionDao.Raw> raw = dao.raw(c, l.source(), l.sourceId());
            if (raw.isEmpty()) {
              skipped.add("no raw mention " + l.source() + " " + l.sourceId());
              continue;
            }
            dao.upsertLabel(c, raw.get().id(), product.productId(), l.match(), l.note());
            pairs.add(new Pair(l, raw.get(), product, l.match()));
          }
          return null;
        });
    return new Loaded(pairs, skipped);
  }

  /** Runs the matcher over every pair and tallies it. */
  public static Report evaluate(MentionMatcher matcher, List<Pair> pairs) {
    Map<Long, Set<Long>> labeledProducts = new HashMap<>();
    for (Pair p : pairs) {
      labeledProducts
          .computeIfAbsent(p.raw().id(), k -> new HashSet<>())
          .add(p.product().productId());
    }
    record Outcome(Pair pair, Scorer.Score score) {}
    List<Outcome> outcomes = new ArrayList<>();
    Set<String> unjudged = new HashSet<>();
    Map<Long, MentionMatcher.Text> texts = new HashMap<>();
    int positives = 0;
    for (Pair p : pairs) {
      MentionMatcher.Text text =
          texts.computeIfAbsent(p.raw().id(), k -> matcher.text(p.raw().fullText()));
      // The pair's score is what the product gets after the one-span-one-product rule, so a
      // sibling that lost its span to the better claim scores as unlinked here, as it is in a run.
      Scorer.Score score = Scorer.Score.NONE;
      for (MentionMatcher.Match m : matcher.matches(text)) {
        if (m.candidate().productId() == p.product().productId()) {
          score = m.score();
        } else if (m.outcome(matcher.thresholds()) == Resolver.Outcome.AUTO
            && !labeledProducts.get(p.raw().id()).contains(m.candidate().productId())) {
          unjudged.add(p.raw().id() + ":" + m.candidate().productId());
        }
      }
      outcomes.add(new Outcome(p, score));
      if (p.match()) {
        positives++;
      }
    }

    List<ResolutionEval.Point> sweep = new ArrayList<>();
    for (int i = 0; i <= 20; i++) {
      double t = i / 20.0;
      sweep.add(
          point(
              t,
              outcomes.stream()
                  .map(o -> new double[] {o.score().value(), o.pair().match() ? 1 : 0})
                  .toList()));
    }
    ResolutionEval.Point operating =
        point(
            matcher.thresholds().auto(),
            outcomes.stream()
                .map(o -> new double[] {o.score().value(), o.pair().match() ? 1 : 0})
                .toList());

    int inReviewBand = 0;
    List<Mistake> mistakes = new ArrayList<>();
    for (Outcome o : outcomes) {
      Resolver.Outcome outcome =
          o.score().value() >= matcher.thresholds().auto()
              ? Resolver.Outcome.AUTO
              : o.score().value() >= matcher.thresholds().review()
                  ? Resolver.Outcome.REVIEW
                  : Resolver.Outcome.NONE;
      if (outcome == Resolver.Outcome.REVIEW) {
        inReviewBand++;
      }
      boolean linked = outcome == Resolver.Outcome.AUTO;
      if (o.pair().match() && !linked) {
        mistakes.add(new Mistake("FN", o.pair(), o.score(), outcome));
      } else if (!o.pair().match() && linked) {
        mistakes.add(new Mistake("FP", o.pair(), o.score(), outcome));
      }
    }
    return new Report(
        pairs.size(),
        positives,
        pairs.size() - positives,
        matcher.thresholds(),
        sweep,
        operating,
        inReviewBand,
        unjudged.size(),
        mistakes);
  }

  private static ResolutionEval.Point point(double threshold, List<double[]> scored) {
    int tp = 0;
    int fp = 0;
    int fn = 0;
    int tn = 0;
    for (double[] s : scored) {
      boolean predicted = s[0] >= threshold && s[0] > 0;
      boolean match = s[1] > 0;
      if (match && predicted) {
        tp++;
      } else if (match) {
        fn++;
      } else if (predicted) {
        fp++;
      } else {
        tn++;
      }
    }
    return new ResolutionEval.Point(threshold, tp, fp, fn, tn);
  }

  /** The report as text — what the CLI prints and what gets committed next to the labels. */
  public static String render(String category, Report r, List<String> skipped) {
    StringBuilder sb = new StringBuilder();
    sb.append("mention resolution — ").append(category).append('\n');
    sb.append(
        String.format(
            Locale.ROOT,
            "%d labeled pairs: %d match, %d not; %d skipped (text or product not in the database)%n",
            r.pairs(),
            r.positives(),
            r.negatives(),
            skipped.size()));
    for (String s : skipped) {
      sb.append("  skipped: ").append(s).append('\n');
    }
    sb.append(
        String.format(
            Locale.ROOT,
            "at auto >= %.2f: precision %.3f  recall %.3f  F1 %.3f  (tp %d fp %d fn %d tn %d)%n",
            r.thresholds().auto(),
            r.operating().precision(),
            r.operating().recall(),
            r.operating().f1(),
            r.operating().tp(),
            r.operating().fp(),
            r.operating().fn(),
            r.operating().tn()));
    sb.append(
        String.format(
            Locale.ROOT,
            "%d pairs in the review band [%.2f, %.2f); %d link(s) to a product no label judges%n",
            r.inReviewBand(),
            r.thresholds().review(),
            r.thresholds().auto(),
            r.unjudgedLinks()));
    sb.append("sweep (threshold  precision  recall  f1)\n");
    for (ResolutionEval.Point p : r.sweep()) {
      sb.append(
          String.format(
              Locale.ROOT,
              "  %.2f  %.3f  %.3f  %.3f%n",
              p.threshold(),
              p.precision(),
              p.recall(),
              p.f1()));
    }
    sb.append(
        r.mistakes().isEmpty()
            ? "no mistakes at the operating point\n"
            : "mistakes at the operating point:\n");
    for (Mistake m : r.mistakes()) {
      sb.append(
          String.format(
              Locale.ROOT,
              "  %s  %.2f  %s  %s %s  | %s  | %s%n",
              m.kind(),
              m.score().value(),
              m.outcome().name().toLowerCase(Locale.ROOT),
              m.pair().label().brand(),
              m.pair().label().model(),
              m.pair().label().excerpt(),
              String.join("; ", m.score().reasons())));
    }
    return sb.toString();
  }
}
