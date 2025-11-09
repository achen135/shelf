package com.achen.shelf.crawl;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.Retailer;
import com.achen.shelf.db.CrawlRunDao;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.PartitionDao;
import com.achen.shelf.db.ProductDao;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One crawl cycle, start to finish, on one thread.
 *
 * <p>This is {@code shelf crawl --once}: the M1 path, kept as the simplest correct way to run a
 * cycle and as the reference the distributed path must match. Since M2 the per-page work lives in
 * {@link PageCrawler}, which the queue-driven worker runs one task at a time; this class is that
 * same crawler driven by a plain nested loop instead of a queue.
 *
 * <p>Two invariants worth stating because later milestones depend on them. Every URL fetched is
 * built from configuration (a retailer's base_url plus one of its list_paths): nothing is
 * discovered by following a link out of a fetched document, so the crawl surface is exactly what
 * the category file says it is. And every observation in a run carries the run's start instant, so
 * re-running a cycle writes no new observation rows — the property M2's kill-node recovery test
 * relies on.
 */
public final class CrawlRunner {

  private static final Logger log = LoggerFactory.getLogger(CrawlRunner.class);

  private final Database db;
  private final Clock clock;
  private final PageCrawler pages;
  private final CrawlRunDao runs;
  private final ProductDao products;
  private final PartitionDao partitions;

  public CrawlRunner(Database db, Fetcher fetcher, RawStore rawStore) {
    this(db, fetcher, rawStore, Clock.systemUTC());
  }

  /**
   * As above, with an explicit clock.
   *
   * <p>The clock decides every observation's {@code observed_at}, which is the crawl's idempotency
   * key — so being able to fix it is what lets a test replay a cycle exactly and assert that
   * nothing was written twice.
   */
  public CrawlRunner(Database db, Fetcher fetcher, RawStore rawStore, Clock clock) {
    this.db = db;
    this.clock = clock;
    this.pages = new PageCrawler(db, fetcher, rawStore);
    this.runs = new CrawlRunDao(db);
    this.products = new ProductDao(db);
    this.partitions = new PartitionDao(db);
  }

  /** Runs one cycle over every enabled retailer in the category. */
  public CrawlSummary runOnce(CategoryConfig category) throws SQLException, InterruptedException {
    Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);

    // Both months, so a run near a boundary — or the first run of a new month — has somewhere
    // to write without a human having created the partition first.
    partitions.ensurePartition(startedAt);
    partitions.ensurePartition(startedAt.plus(31, ChronoUnit.DAYS));

    CrawlContext ctx = CrawlContext.bootstrap(category, products);
    long runId = runs.open(category.name(), startedAt, 1);
    log.info(
        "crawl run {} started: category={} seeds={} retailers={}",
        runId,
        category.name(),
        ctx.catalog().size(),
        category.enabledRetailers().size());

    List<CrawlSummary.RetailerSummary> summaries = new ArrayList<>();
    for (Retailer retailer : category.enabledRetailers()) {
      summaries.add(crawlRetailer(ctx, retailer, runId, startedAt));
    }

    CrawlSummary summary =
        new CrawlSummary(runId, category.name(), summaries, ctx.catalog().size());
    runs.finish(runId, summary.totalPages(), summary.totalErrors(), clock.instant());
    log.info(
        "crawl run {} finished: pages={} offers={} observations={} errors={}",
        runId,
        summary.totalPages(),
        summary.totalOffersWritten(),
        summary.totalObservations(),
        summary.totalErrors());
    return summary;
  }

  private CrawlSummary.RetailerSummary crawlRetailer(
      CrawlContext ctx, Retailer retailer, long runId, Instant observedAt)
      throws SQLException, InterruptedException {
    int pageCount = 0;
    int offersSeen = 0;
    int offersWritten = 0;
    int observationsWritten = 0;
    int errors = 0;
    int skippedByRobots = 0;

    for (String listPath : retailer.fetch().listPaths()) {
      for (int page = 1; page <= retailer.fetch().maxPages(); page++) {
        PageCrawler.Target target = new PageCrawler.Target(retailer, listPath, page);
        PageCrawler.Fetched fetched = pages.fetch(ctx, target, runId);

        if (fetched instanceof PageCrawler.Fetched.SkippedByRobots) {
          skippedByRobots++;
          continue;
        }
        if (fetched instanceof PageCrawler.Fetched.Failed) {
          errors++;
          break; // a failing page means later pages of the same path are not worth trying
        }

        List<ParsedOffer> parsed = ((PageCrawler.Fetched.Parsed) fetched).offers();
        pageCount++;
        offersSeen += parsed.size();
        if (parsed.isEmpty()) {
          log.debug(
              "{}: {} returned no offers — end of this list path", retailer.name(), fetched.url());
          break;
        }

        PageCrawler.Written written =
            db.transaction(c -> pages.write(c, ctx, retailer, parsed, runId, observedAt));
        offersWritten += written.offersWritten();
        observationsWritten += written.observationsWritten();
      }
    }

    log.info(
        "{}: pages={} offers={} observations={} errors={}",
        retailer.name(),
        pageCount,
        offersWritten,
        observationsWritten,
        errors);
    return new CrawlSummary.RetailerSummary(
        retailer.name(),
        retailer.fetch().mode().name().toLowerCase(Locale.ROOT),
        pageCount,
        offersSeen,
        offersWritten,
        observationsWritten,
        errors,
        skippedByRobots);
  }

  /** The URL for a page of a list path; see {@link PageCrawler#buildUrl}. */
  static String buildUrl(Retailer retailer, String listPath, int page) {
    return PageCrawler.buildUrl(retailer, listPath, page);
  }
}
