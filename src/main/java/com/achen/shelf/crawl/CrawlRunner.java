package com.achen.shelf.crawl;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.FetchMode;
import com.achen.shelf.config.Retailer;
import com.achen.shelf.config.SpecValidator;
import com.achen.shelf.crawl.parse.ParseException;
import com.achen.shelf.crawl.parse.Parser;
import com.achen.shelf.crawl.parse.ParserRegistry;
import com.achen.shelf.db.CrawlRunDao;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.ObservationDao;
import com.achen.shelf.db.OfferDao;
import com.achen.shelf.db.PartitionDao;
import com.achen.shelf.db.ProductDao;
import com.achen.shelf.db.RawFetchDao;
import com.achen.shelf.db.RobotsCacheDao;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One crawl cycle, start to finish, on one thread.
 *
 * <p>The sequence per retailer is: check robots.txt, wait for the domain's rate limiter, fetch,
 * store the raw body, parse, validate specs, link to the catalog where possible, then upsert the
 * offer and record one price observation. Correctness first — M2 keeps this pipeline and moves the
 * loop into a queue and a worker pool.
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

  private final Fetcher fetcher;
  private final Clock clock;
  private final RobotsGate robots;
  private final DomainRateLimiter rateLimiter;
  private final RawStore rawStore;

  private final CrawlRunDao runs;
  private final ProductDao products;
  private final OfferDao offers;
  private final ObservationDao observations;
  private final RawFetchDao rawFetches;
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
    this.clock = clock;
    this.fetcher = fetcher;
    this.rawStore = rawStore;
    this.robots = new RobotsGate(fetcher, new RobotsCacheDao(db));
    this.rateLimiter = new DomainRateLimiter();
    this.runs = new CrawlRunDao(db);
    this.products = new ProductDao(db);
    this.offers = new OfferDao(db);
    this.observations = new ObservationDao(db);
    this.rawFetches = new RawFetchDao(db);
    this.partitions = new PartitionDao(db);
  }

  /** Runs one cycle over every enabled retailer in the category. */
  public CrawlSummary runOnce(CategoryConfig category) throws SQLException, InterruptedException {
    Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    ParserRegistry parsers = ParserRegistry.forCategory(category);
    checkParsersResolve(category, parsers);

    // Both months, so a run near a boundary — or the first run of a new month — has somewhere
    // to write without a human having created the partition first.
    partitions.ensurePartition(startedAt);
    partitions.ensurePartition(startedAt.plus(31, ChronoUnit.DAYS));

    long runId = runs.open(category.name(), startedAt, 1);
    SeedCatalog catalog = SeedCatalog.bootstrap(category, products);
    log.info(
        "crawl run {} started: category={} seeds={} retailers={}",
        runId,
        category.name(),
        catalog.size(),
        category.enabledRetailers().size());

    SpecValidator specValidator = new SpecValidator(category.specSchema());
    List<CrawlSummary.RetailerSummary> summaries = new ArrayList<>();
    for (Retailer retailer : category.enabledRetailers()) {
      summaries.add(crawlRetailer(retailer, parsers, specValidator, catalog, runId, startedAt));
    }

    CrawlSummary summary = new CrawlSummary(runId, category.name(), summaries, catalog.size());
    runs.finish(runId, summary.totalPages(), summary.totalErrors(), clock.instant());
    log.info(
        "crawl run {} finished: pages={} offers={} observations={} matched={} errors={}",
        runId,
        summary.totalPages(),
        summary.totalOffersWritten(),
        summary.totalObservations(),
        summary.totalMatched(),
        summary.totalErrors());
    return summary;
  }

  private CrawlSummary.RetailerSummary crawlRetailer(
      Retailer retailer,
      ParserRegistry parsers,
      SpecValidator specValidator,
      SeedCatalog catalog,
      long runId,
      Instant observedAt)
      throws SQLException, InterruptedException {

    Parser parser = parsers.get(retailer.parser());
    int pages = 0;
    int offersSeen = 0;
    int offersWritten = 0;
    int observationsWritten = 0;
    int matched = 0;
    int errors = 0;
    int skippedByRobots = 0;

    // robots.txt can only ever slow us down relative to the configured rate.
    Optional<Duration> crawlDelay = robots.crawlDelay(retailer.baseUrl() + "/");
    rateLimiter.configure(retailer.baseUrl(), retailer.fetch().maxRps(), crawlDelay.orElse(null));
    crawlDelay.ifPresent(
        delay -> log.info("{}: honouring robots.txt Crawl-delay of {}", retailer.name(), delay));

    for (String listPath : retailer.fetch().listPaths()) {
      for (int page = 1; page <= retailer.fetch().maxPages(); page++) {
        String url = buildUrl(retailer, listPath, page);

        if (!robots.allows(url)) {
          log.warn("{}: robots.txt disallows {} — skipping", retailer.name(), url);
          skippedByRobots++;
          continue;
        }

        rateLimiter.acquire(url);
        FetchResult result = fetcher.fetch(url);
        String bodyRef =
            result.succeeded()
                ? rawStore.store(runId, result.body().orElseThrow(), extensionFor(retailer))
                : null;
        rawFetches.record(url, runId, result.status().orElse(null), bodyRef);

        if (!result.succeeded()) {
          log.warn(
              "{}: fetch failed after {} attempts: {} ({})",
              retailer.name(),
              result.attempts(),
              url,
              result.failure().orElse("unknown"));
          errors++;
          break; // a failing page means later pages of the same path are not worth trying
        }

        List<ParsedOffer> parsed;
        try {
          parsed = parser.parse(retailer, result.body().orElseThrow(), url);
        } catch (ParseException e) {
          log.warn("{}: could not parse {}: {}", retailer.name(), url, e.getMessage());
          errors++;
          break;
        }

        pages++;
        offersSeen += parsed.size();
        if (parsed.isEmpty()) {
          log.debug("{}: {} returned no offers — end of this list path", retailer.name(), url);
          break;
        }

        for (ParsedOffer offer : parsed) {
          Optional<Long> productId = catalog.match(offer.brand(), offer.title());
          if (productId.isPresent()) {
            matched++;
          }
          SpecValidator.Result specs = specValidator.validate(offer.specFields());
          if (!specs.isClean()) {
            log.debug(
                "{}: dropped spec values for {}: {}",
                retailer.name(),
                offer.url(),
                specs.warnings());
          }
          long offerId =
              offers.upsert(
                  retailer.name(),
                  offer.url(),
                  offer.title(),
                  offer.retailerSku(),
                  offer.currency(),
                  productId.orElse(null));
          offersWritten++;
          boolean inserted =
              observations.record(
                  offerId,
                  observedAt,
                  offer.priceCents(),
                  offer.shippingCents(),
                  offer.inStock(),
                  runId,
                  ObservationDao.Source.OBSERVED);
          if (inserted) {
            observationsWritten++;
          }
        }
      }
    }

    log.info(
        "{}: pages={} offers={} observations={} matched={} errors={}",
        retailer.name(),
        pages,
        offersWritten,
        observationsWritten,
        matched,
        errors);
    return new CrawlSummary.RetailerSummary(
        retailer.name(),
        retailer.fetch().mode().name().toLowerCase(Locale.ROOT),
        pages,
        offersSeen,
        offersWritten,
        observationsWritten,
        matched,
        errors,
        skippedByRobots);
  }

  /**
   * Substitutes the per-request placeholders into a configured list path.
   *
   * <p>An unresolved placeholder is a configuration error and is raised as one: the disabled Best
   * Buy and eBay entries carry placeholders ({@code {category_path_id}}, {@code {query}}) that are
   * deliberately unfilled, and enabling one without finishing its config should fail loudly rather
   * than fetch a URL with a brace in it.
   */
  static String buildUrl(Retailer retailer, String listPath, int page) {
    String path =
        listPath
            .replace("{page}", String.valueOf(page))
            .replace("{limit}", String.valueOf(retailer.fetch().pageSize()));
    if (path.indexOf('{') >= 0) {
      throw new IllegalStateException(
          "retailer '"
              + retailer.name()
              + "' has an unresolved placeholder in list_path '"
              + listPath
              + "' — finish its config before enabling it");
    }
    return retailer.baseUrl() + path;
  }

  private void checkParsersResolve(CategoryConfig category, ParserRegistry parsers) {
    for (Retailer retailer : category.enabledRetailers()) {
      if (!parsers.has(retailer.parser())) {
        throw new ParseException(
            "retailer '"
                + retailer.name()
                + "' names parser '"
                + retailer.parser()
                + "', which is not registered");
      }
    }
  }

  private static String extensionFor(Retailer retailer) {
    return retailer.fetch().mode() == FetchMode.API ? "json" : "html";
  }
}
