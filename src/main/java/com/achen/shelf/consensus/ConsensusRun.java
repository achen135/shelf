package com.achen.shelf.consensus;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.Community;
import com.achen.shelf.db.ConsensusDao;
import com.achen.shelf.db.Database;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One consensus pass over a category: every product's linked mentions in the trailing window,
 * {@link ConsensusRule} applied, one {@code consensus_scores} row per product — with mentions or
 * without, so a page can say "nothing said" rather than nothing (M10).
 *
 * <p>Runs as the last step of {@code shelf mentions} (and after {@code shelf ingest}, which now
 * chains ingest → mentions → consensus the way {@code shelf crawl --once} chains crawl → resolution
 * → rollups → signals) and by {@code shelf consensus} on its own. One transaction; idempotent,
 * since a consensus is a function of the rows and the clock. The cadence question Spec v2 §8 left
 * open is answered by inheritance: the consensus is as fresh as the last mention pass, and the
 * mention pass is as fresh as the last ingest, which is whatever runs it.
 */
public final class ConsensusRun {

  private static final Logger log = LoggerFactory.getLogger(ConsensusRun.class);

  /** The trailing window a consensus is over. */
  public static final int WINDOW_DAYS = 90;

  /** What a pass did. */
  public record Summary(
      String category,
      Instant asOf,
      int products,
      int withMentions,
      int mentions,
      int liked,
      int mixed,
      int disliked,
      Duration took) {}

  private final Database db;
  private final Clock clock;

  public ConsensusRun(Database db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  /** Recomputes every product of the category. */
  public Summary run(CategoryConfig category) throws SQLException {
    Instant asOf = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    Instant since = asOf.minus(Duration.ofDays(WINDOW_DAYS));
    Map<String, Double> weights = new HashMap<>();
    for (Community c : category.communities()) {
      weights.put(c.name(), c.weight());
    }
    return db.transaction(
        c -> {
          List<Long> products = ConsensusDao.productIds(c, category.name());
          Map<Long, List<ConsensusRule.Mention>> byProduct =
              ConsensusDao.linkedMentions(c, category.name(), since, weights);
          int with = 0;
          int mentions = 0;
          int liked = 0;
          int mixed = 0;
          int disliked = 0;
          for (long productId : products) {
            List<ConsensusRule.Mention> rows = byProduct.getOrDefault(productId, List.of());
            ConsensusRule.Consensus k = ConsensusRule.of(rows);
            ConsensusDao.upsert(c, productId, k, WINDOW_DAYS, asOf);
            if (!rows.isEmpty()) {
              with++;
              mentions += rows.size();
              switch (k.leaning()) {
                case LIKED -> liked++;
                case MIXED -> mixed++;
                case DISLIKED -> disliked++;
                case UNHEARD -> {}
              }
            }
          }
          Summary s =
              new Summary(
                  category.name(),
                  asOf,
                  products.size(),
                  with,
                  mentions,
                  liked,
                  mixed,
                  disliked,
                  Duration.between(asOf, clock.instant()));
          log.info(
              "consensus pass for {}: {} products, {} with a mention in the last {} days ({} mentions):"
                  + " {} liked / {} mixed / {} disliked; {} ms",
              s.category(),
              s.products(),
              s.withMentions(),
              WINDOW_DAYS,
              s.mentions(),
              s.liked(),
              s.mixed(),
              s.disliked(),
              s.took().toMillis());
          return s;
        });
  }
}
