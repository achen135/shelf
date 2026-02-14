package com.achen.shelf.mention;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.MentionDao;
import com.achen.shelf.resolve.Catalog;
import com.achen.shelf.resolve.MentionMatcher;
import com.achen.shelf.resolve.ResolutionRun;
import com.achen.shelf.resolve.Resolver;
import com.achen.shelf.resolve.Scorer;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One mention-resolution pass over a category: every raw mention read, every product it names
 * decided, the sentence around each read for sentiment and rank, the rows written (M9).
 *
 * <p>It begins by bootstrapping the seeds, so a product (or an alias) added to the category file is
 * in the catalog before the first text is read — which is what lets M11's proof be a config edit
 * and this command, with no crawl in between. Then one transaction: the machine's rows for the
 * category are deleted and this pass's decisions written, a human's rows kept. Every pass is a full
 * re-decision — cheap (three thousand texts against fifty products in well under a second) and the
 * only way a matcher, alias or lexicon change is guaranteed to apply everywhere. Repeating a pass
 * changes nothing.
 */
public final class MentionRun {

  private static final Logger log = LoggerFactory.getLogger(MentionRun.class);

  /** The extraction method every row of this pass carries. */
  public static final String METHOD = "rule";

  /** What a pass did. */
  public record Summary(
      String category,
      int catalogSize,
      int rawMentions,
      int rawWithAMatch,
      int autoLinked,
      int proposed,
      int keptByHuman,
      int positive,
      int negative,
      int neutral,
      int ranked,
      Duration took) {}

  private final Database db;
  private final MentionDao dao;
  private final Resolver.Thresholds thresholds;

  public MentionRun(Database db, Resolver.Thresholds thresholds) {
    this.db = db;
    this.dao = new MentionDao(db);
    this.thresholds = thresholds;
  }

  /** A matcher over the category's catalog; {@code loadCatalog} bootstraps the seeds first. */
  public MentionMatcher matcher(CategoryConfig category) throws SQLException {
    Catalog catalog = new ResolutionRun(db, thresholds).loadCatalog(category);
    return new MentionMatcher(catalog, thresholds);
  }

  /** Runs one pass. */
  public Summary run(CategoryConfig category) throws SQLException {
    Instant started = Instant.now();
    MentionMatcher matcher = matcher(category);
    SentimentRule sentiment = new SentimentRule(category.sentiment());

    return db.transaction(
        c -> {
          List<MentionDao.Raw> raws = dao.raw(c, category.name());
          List<MentionDao.Decision> decisions = new ArrayList<>();
          int withMatch = 0;
          int auto = 0;
          int proposed = 0;
          int pos = 0;
          int neg = 0;
          int neu = 0;
          int ranked = 0;
          for (MentionDao.Raw raw : raws) {
            String full = raw.fullText();
            List<MentionMatcher.Match> matches = matcher.matches(matcher.text(full));
            if (matches.isEmpty()) {
              continue;
            }
            withMatch++;
            for (MentionMatcher.Match m : matches) {
              Resolver.Outcome outcome = m.outcome(thresholds);
              if (outcome == Resolver.Outcome.NONE) {
                continue;
              }
              List<String> name = Scorer.tokens(m.phrase());
              SentimentRule.Reading reading = sentiment.read(full, name);
              Optional<Integer> rank = RankRule.rank(full, name);
              switch (reading.sentiment()) {
                case POSITIVE -> pos++;
                case NEGATIVE -> neg++;
                case NEUTRAL -> neu++;
              }
              if (rank.isPresent()) {
                ranked++;
              }
              if (outcome == Resolver.Outcome.AUTO) {
                auto++;
              } else {
                proposed++;
              }
              List<String> reasons = new ArrayList<>(m.score().reasons());
              reasons.add("sentiment: " + String.join(", ", reading.evidence()));
              decisions.add(
                  new MentionDao.Decision(
                      raw.id(),
                      m.candidate().productId(),
                      m.score().value(),
                      outcome == Resolver.Outcome.AUTO ? "auto" : "pending",
                      reasons,
                      m.phrase(),
                      reading.sentiment().dbValue(),
                      reading.score(),
                      rank.orElse(null),
                      METHOD));
            }
          }
          int written = dao.replaceMachineRows(c, category.name(), decisions);
          Summary s =
              new Summary(
                  category.name(),
                  matcher.catalog().size(),
                  raws.size(),
                  withMatch,
                  auto,
                  proposed,
                  decisions.size() - written,
                  pos,
                  neg,
                  neu,
                  ranked,
                  Duration.between(started, Instant.now()));
          log.info(
              "mention pass for {}: {} raw mentions, {} name a product; {} linked, {} proposed,"
                  + " {} kept as a human decided; sentiment {} positive / {} negative / {} neutral;"
                  + " {} ranked; {} ms",
              s.category(),
              s.rawMentions(),
              s.rawWithAMatch(),
              s.autoLinked(),
              s.proposed(),
              s.keptByHuman(),
              s.positive(),
              s.negative(),
              s.neutral(),
              s.ranked(),
              s.took().toMillis());
          return s;
        });
  }
}
