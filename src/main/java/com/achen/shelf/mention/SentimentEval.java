package com.achen.shelf.mention;

import com.achen.shelf.crawl.Normalizer;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.MentionDao;
import com.achen.shelf.resolve.Scorer;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Accuracy of the sentiment rule against the frozen phrase-keyed labels (M9) — {@code shelf eval
 * mention-sentiment}: a three-by-three confusion, per-class precision and recall, and every miss
 * with the rule's evidence beside the label's note.
 */
public final class SentimentEval {

  /** A label joined to its text. */
  public record Case(MentionLabels.SentimentLabel label, MentionDao.Raw raw) {}

  /** One reading against its label. */
  public record Judged(Case c, SentimentRule.Reading reading) {
    boolean right() {
      return reading.sentiment() == c.label().sentiment();
    }
  }

  /** The report. */
  public record Report(
      int cases,
      int right,
      Map<SentimentRule.Sentiment, Map<SentimentRule.Sentiment, Integer>> confusion,
      List<Judged> misses) {
    public Report {
      Map<SentimentRule.Sentiment, Map<SentimentRule.Sentiment, Integer>> frozen =
          new EnumMap<>(SentimentRule.Sentiment.class);
      confusion.forEach((k, v) -> frozen.put(k, Map.copyOf(v)));
      confusion = Map.copyOf(frozen);
      misses = List.copyOf(misses);
    }

    public double accuracy() {
      return cases == 0 ? 0 : (double) right / cases;
    }

    /** Precision for a class: right calls of it over all calls of it. */
    public double precision(SentimentRule.Sentiment s) {
      int called = 0;
      for (SentimentRule.Sentiment actual : SentimentRule.Sentiment.values()) {
        called += confusion.get(actual).get(s);
      }
      return called == 0 ? 0 : (double) confusion.get(s).get(s) / called;
    }

    /** Recall for a class: right calls of it over all labeled as it. */
    public double recall(SentimentRule.Sentiment s) {
      int labeled = 0;
      for (int n : confusion.get(s).values()) {
        labeled += n;
      }
      return labeled == 0 ? 0 : (double) confusion.get(s).get(s) / labeled;
    }
  }

  private SentimentEval() {}

  /** Joins the labels to their raw mentions; a label whose text is absent is reported. */
  public static List<Case> load(
      Database db, List<MentionLabels.SentimentLabel> labels, List<String> skipped)
      throws SQLException {
    MentionDao dao = new MentionDao(db);
    List<Case> cases = new ArrayList<>();
    db.transaction(
        c -> {
          for (MentionLabels.SentimentLabel l : labels) {
            Optional<MentionDao.Raw> raw = dao.raw(c, l.source(), l.sourceId());
            if (raw.isEmpty()) {
              skipped.add("no raw mention " + l.source() + " " + l.sourceId());
              continue;
            }
            cases.add(new Case(l, raw.get()));
          }
          return null;
        });
    return cases;
  }

  public static Report evaluate(SentimentRule rule, List<Case> cases) {
    Map<SentimentRule.Sentiment, Map<SentimentRule.Sentiment, Integer>> confusion =
        new EnumMap<>(SentimentRule.Sentiment.class);
    for (SentimentRule.Sentiment a : SentimentRule.Sentiment.values()) {
      Map<SentimentRule.Sentiment, Integer> row = new EnumMap<>(SentimentRule.Sentiment.class);
      for (SentimentRule.Sentiment b : SentimentRule.Sentiment.values()) {
        row.put(b, 0);
      }
      confusion.put(a, row);
    }
    List<Judged> misses = new ArrayList<>();
    int right = 0;
    for (Case c : cases) {
      List<String> name = Scorer.tokens(Normalizer.normalize(c.label().phrase()));
      SentimentRule.Reading reading = rule.read(c.raw().fullText(), name);
      Judged j = new Judged(c, reading);
      confusion.get(c.label().sentiment()).merge(reading.sentiment(), 1, Integer::sum);
      if (j.right()) {
        right++;
      } else {
        misses.add(j);
      }
    }
    return new Report(cases.size(), right, confusion, misses);
  }

  public static String render(String category, Report r, List<String> skipped) {
    StringBuilder sb = new StringBuilder();
    sb.append("mention sentiment — ").append(category).append('\n');
    sb.append(
        String.format(
            Locale.ROOT,
            "%d labeled phrases, %d skipped (text not in the database); accuracy %.3f (%d right)%n",
            r.cases(),
            skipped.size(),
            r.accuracy(),
            r.right()));
    for (String s : skipped) {
      sb.append("  skipped: ").append(s).append('\n');
    }
    sb.append("confusion (rows = labeled, columns = read): positive negative neutral\n");
    for (SentimentRule.Sentiment a : SentimentRule.Sentiment.values()) {
      sb.append(String.format(Locale.ROOT, "  %-9s", a.dbValue()));
      for (SentimentRule.Sentiment b : SentimentRule.Sentiment.values()) {
        sb.append(String.format(Locale.ROOT, " %8d", r.confusion().get(a).get(b)));
      }
      sb.append('\n');
    }
    for (SentimentRule.Sentiment s : SentimentRule.Sentiment.values()) {
      sb.append(
          String.format(
              Locale.ROOT,
              "  %-9s precision %.3f  recall %.3f%n",
              s.dbValue(),
              r.precision(s),
              r.recall(s)));
    }
    sb.append(r.misses().isEmpty() ? "no misses\n" : "misses (labeled → read):\n");
    for (Judged j : r.misses()) {
      sb.append(
          String.format(
              Locale.ROOT,
              "  %s → %s  '%s'  | label: %s  | rule: %s%n",
              j.c().label().sentiment().dbValue(),
              j.reading().sentiment().dbValue(),
              j.c().label().phrase(),
              j.c().label().note(),
              String.join(", ", j.reading().evidence())));
    }
    return sb.toString();
  }
}
