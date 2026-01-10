package com.achen.shelf.rollup;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.Retailer;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.OfferDao;
import com.achen.shelf.db.RollupDao;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One rollup pass over a category: retire what has gone unseen, then recompute the rollups of every
 * offer and product that changed.
 *
 * <p>Runs after a crawl cycle closes — the coordinator's post-run hook, after the resolution pass,
 * and the tail of {@code shelf crawl --once} — scoped to that run: the offers the run observed
 * ({@code price_observations_run_idx}), the offers this pass retired, and the products any of those
 * are linked to. {@code shelf rollup} runs it over the whole category. Both are one transaction, so
 * a reader never sees a product's row computed before its offers' retirement.
 *
 * <p>Retirement comes first, in the same pass, because every number after it depends on which
 * offers count as live: a product's current price is the cheapest of its live offers, and a
 * delisted SKU that kept counting would sit in that minimum forever.
 *
 * <p>Idempotent: a rollup row is a function of the observations and {@code as_of}, so running the
 * pass twice writes the same rows twice, and a scoped pass and a full one agree on every row they
 * both touch.
 */
public final class RollupRun {

  private static final Logger log = LoggerFactory.getLogger(RollupRun.class);

  /** What to recompute. */
  public sealed interface Scope {
    /** What one crawl run touched: its observed offers, the offers retired now, their products. */
    record Run(long runId) implements Scope {}

    /** Every offer at the category's retailers and every product in the category. */
    record All() implements Scope {}
  }

  /** The retirement rule's one knob. */
  public record Settings(int retireAfterCycles) {
    /** Three unseen cycles — a page that dies once (three attempts) does not retire its offers. */
    public static Settings defaults() {
      return new Settings(3);
    }

    public Settings {
      if (retireAfterCycles < 1) {
        throw new IllegalArgumentException("retireAfterCycles must be at least 1");
      }
    }
  }

  /**
   * What a pass did. {@code products} are the ids whose rows were recomputed — the signal pass's
   * scope.
   */
  public record Summary(
      String category,
      Instant asOf,
      int retired,
      int offersRecomputed,
      List<Long> products,
      Duration took) {
    public Summary {
      products = List.copyOf(products);
    }

    public int productsRecomputed() {
      return products.size();
    }
  }

  private final Database db;
  private final Settings settings;
  private final Clock clock;

  public RollupRun(Database db, Settings settings, Clock clock) {
    this.db = db;
    this.settings = settings;
    this.clock = clock;
  }

  /** Runs one pass. */
  public Summary run(CategoryConfig category, Scope scope) throws SQLException {
    Instant asOf = clock.instant();
    List<String> retailers = category.retailers().stream().map(Retailer::name).toList();
    long started = System.nanoTime();
    Summary summary =
        db.transaction(
            c -> {
              List<OfferDao.Retired> retired =
                  OfferDao.retireUnseen(
                      c, category.name(), retailers, settings.retireAfterCycles(), asOf);
              Set<Long> offers = new LinkedHashSet<>();
              Set<Long> products = new LinkedHashSet<>();
              switch (scope) {
                case Scope.All all -> {
                  offers.addAll(RollupDao.offersAt(c, retailers));
                  products.addAll(RollupDao.productsIn(c, category.name()));
                }
                case Scope.Run run -> {
                  offers.addAll(RollupDao.offersObservedIn(c, run.runId()));
                  retired.forEach(r -> offers.add(r.offerId()));
                  products.addAll(RollupDao.linkedProductsOf(c, offers));
                }
              }
              // A retired offer's product must be recomputed even when that offer was not
              // observed this run — that is the whole point of retiring it.
              retired.stream()
                  .map(OfferDao.Retired::productId)
                  .filter(p -> p != null)
                  .forEach(products::add);
              int o = RollupDao.recomputeOffers(c, offers, asOf);
              RollupDao.recomputeProducts(c, products, asOf);
              return new Summary(
                  category.name(), asOf, retired.size(), o, List.copyOf(products), Duration.ZERO);
            });
    summary =
        new Summary(
            summary.category(),
            summary.asOf(),
            summary.retired(),
            summary.offersRecomputed(),
            summary.products(),
            Duration.ofNanos(System.nanoTime() - started));
    log.info(
        "rollup pass for {} ({}): {} offer(s) retired, {} offer and {} product rollup(s) recomputed"
            + " as of {} in {} ms",
        summary.category(),
        scope,
        summary.retired(),
        summary.offersRecomputed(),
        summary.productsRecomputed(),
        summary.asOf(),
        summary.took().toMillis());
    return summary;
  }
}
