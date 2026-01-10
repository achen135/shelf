package com.achen.shelf.signal;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.DealSignalDao;
import com.achen.shelf.db.RollupDao;
import com.achen.shelf.db.RollupDao.Rollup;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One signal pass: read the stored rollup row of each product in scope, apply {@link DealRule},
 * write {@code deal_signals}.
 *
 * <p>Runs after the rollup pass in the coordinator's post-run chain (resolution → rollups →
 * signals) over exactly the products that pass recomputed — the touched set travels down the chain
 * rather than being derived a second time — and by {@code shelf signal} over a whole category. One
 * transaction; idempotent, since a signal is a function of the rollup row.
 *
 * <p>A product with no rollup row is skipped and counted, not guessed at: the fix is {@code shelf
 * rollup}, and a neutral row with a made-up {@code as_of} would hide that.
 */
public final class SignalRun {

  private static final Logger log = LoggerFactory.getLogger(SignalRun.class);

  /** What a pass did. {@code asOf} is the newest rollup instant among the rows it read. */
  public record Summary(
      String category,
      Instant asOf,
      int decided,
      int buys,
      int waits,
      int neutrals,
      int withoutRollup,
      Duration took) {}

  private final Database db;
  private final DealRule rule;

  public SignalRun(Database db, DealRule rule) {
    this.db = db;
    this.rule = rule;
  }

  /** Signals for every product in the category. */
  public Summary run(CategoryConfig category) throws SQLException {
    return run(category, null);
  }

  /** Signals for the given products — the ones a rollup pass just recomputed. */
  public Summary run(CategoryConfig category, Collection<Long> productIds) throws SQLException {
    long started = System.nanoTime();
    Summary summary =
        db.transaction(
            c -> {
              Collection<Long> ids =
                  productIds == null ? RollupDao.productsIn(c, category.name()) : productIds;
              List<Rollup> rows = RollupDao.productRollups(c, ids);
              Map<Signal, Integer> counts = new EnumMap<>(Signal.class);
              List<DealSignalDao.Signal> signals = new ArrayList<>(rows.size());
              Instant asOf = null;
              for (Rollup row : rows) {
                DealRule.Decision d = rule.decide(row);
                counts.merge(d.signal(), 1, Integer::sum);
                signals.add(
                    new DealSignalDao.Signal(
                        row.productId(),
                        d.signal().dbValue(),
                        d.reasonNames(),
                        row.currentOfferId(),
                        row.asOf(),
                        null));
                asOf = asOf == null || row.asOf().isAfter(asOf) ? row.asOf() : asOf;
              }
              DealSignalDao.upsert(c, signals);
              return new Summary(
                  category.name(),
                  asOf,
                  signals.size(),
                  counts.getOrDefault(Signal.BUY, 0),
                  counts.getOrDefault(Signal.WAIT, 0),
                  counts.getOrDefault(Signal.NEUTRAL, 0),
                  ids.size() - rows.size(),
                  Duration.ZERO);
            });
    summary =
        new Summary(
            summary.category(),
            summary.asOf(),
            summary.decided(),
            summary.buys(),
            summary.waits(),
            summary.neutrals(),
            summary.withoutRollup(),
            Duration.ofNanos(System.nanoTime() - started));
    log.info(
        "signal pass for {}: {} product(s) decided — {} buy, {} wait, {} neutral; {} without a"
            + " rollup row skipped; as of {} in {} ms",
        summary.category(),
        summary.decided(),
        summary.buys(),
        summary.waits(),
        summary.neutrals(),
        summary.withoutRollup(),
        summary.asOf(),
        summary.took().toMillis());
    return summary;
  }
}
