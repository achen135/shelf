package com.achen.shelf.crawl;

import com.achen.shelf.config.FetchMode;
import com.achen.shelf.config.Retailer;
import com.achen.shelf.config.SpecValidator;
import com.achen.shelf.crawl.parse.ParseException;
import com.achen.shelf.crawl.parse.Parser;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.Jsonb;
import com.achen.shelf.db.ObservationDao;
import com.achen.shelf.db.OfferDao;
import com.achen.shelf.db.RateLimitDao;
import com.achen.shelf.db.RawFetchDao;
import com.achen.shelf.db.RobotsCacheDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One page of one retailer's list path — the unit of work, whoever is doing the work.
 *
 * <p>This is M1's per-page loop body lifted out of {@link CrawlRunner} so that the M2 worker can
 * run exactly the same steps for a queued task. It is in two halves on purpose:
 *
 * <ol>
 *   <li>{@link #fetch}: robots check, rate-limit wait, HTTP fetch, raw body to disk, audit row,
 *       parse. No transaction — the audit row is append-only and wants to survive whatever happens
 *       next, and an HTTP call has no business inside a database transaction.
 *   <li>{@link #write}: offers (title, brand, validated spec) and observations, on a connection the
 *       caller owns. The worker puts this in the same transaction as marking its task done, so a
 *       page is either fully recorded and finished, or neither. No linking happens here — see
 *       {@code resolve/}.
 * </ol>
 */
public final class PageCrawler {

  private static final Logger log = LoggerFactory.getLogger(PageCrawler.class);

  /** One page to crawl. */
  public record Target(Retailer retailer, String listPath, int page) {
    public String url() {
      return buildUrl(retailer, listPath, page);
    }
  }

  /** The result of the fetch half. Exactly one of the three shapes below. */
  public sealed interface Fetched {
    String url();

    /** robots.txt said no; nothing was requested. */
    record SkippedByRobots(String url) implements Fetched {}

    /** The fetch failed after retries, or the body would not parse. */
    record Failed(String url, String reason) implements Fetched {}

    /** A page, parsed. Empty {@code offers} means the end of the list path. */
    record Parsed(String url, List<ParsedOffer> offers) implements Fetched {
      public Parsed {
        offers = List.copyOf(offers);
      }
    }
  }

  /** What the write half recorded. */
  public record Written(int offersWritten, int observationsWritten) {}

  private final Fetcher fetcher;
  private final RobotsGate robots;
  private final DomainRateLimiter rateLimiter;
  private final RawStore rawStore;
  private final RawFetchDao rawFetches;
  private final OfferDao offers;
  private final ObservationDao observations;

  /**
   * Wires the crawler against a database, sharing the politeness bucket in Postgres with every
   * other process that does the same.
   */
  public PageCrawler(Database db, Fetcher fetcher, RawStore rawStore) {
    this(
        fetcher,
        new RobotsGate(fetcher, new RobotsCacheDao(db)),
        DomainRateLimiter.shared(new RateLimitDao(db)),
        rawStore,
        new RawFetchDao(db),
        new OfferDao(db),
        new ObservationDao(db));
  }

  PageCrawler(
      Fetcher fetcher,
      RobotsGate robots,
      DomainRateLimiter rateLimiter,
      RawStore rawStore,
      RawFetchDao rawFetches,
      OfferDao offers,
      ObservationDao observations) {
    this.fetcher = fetcher;
    this.robots = robots;
    this.rateLimiter = rateLimiter;
    this.rawStore = rawStore;
    this.rawFetches = rawFetches;
    this.offers = offers;
    this.observations = observations;
  }

  /**
   * The fetch half: robots → rate limit → fetch → store the body → audit row → parse.
   *
   * @param runId the crawl run, for the raw body's directory and the audit row
   */
  public Fetched fetch(CrawlContext ctx, Target target, long runId)
      throws InterruptedException, SQLException {
    Retailer retailer = target.retailer();
    String url = target.url();

    if (!robots.allows(url)) {
      log.warn("{}: robots.txt disallows {} — skipping", retailer.name(), url);
      return new Fetched.SkippedByRobots(url);
    }

    Optional<Duration> crawlDelay = robots.crawlDelay(retailer.baseUrl() + "/");
    rateLimiter.configure(retailer.baseUrl(), retailer.fetch().maxRps(), crawlDelay.orElse(null));
    rateLimiter.acquire(url);

    FetchResult result = fetcher.fetch(url);
    String bodyRef =
        result.succeeded()
            ? rawStore.store(runId, result.body().orElseThrow(), extensionFor(retailer))
            : null;
    rawFetches.record(url, runId, result.status().orElse(null), bodyRef);

    if (!result.succeeded()) {
      String reason =
          "fetch failed after "
              + result.attempts()
              + " attempt(s): "
              + result.failure().orElse("unknown");
      log.warn("{}: {} ({})", retailer.name(), reason, url);
      return new Fetched.Failed(url, reason);
    }

    try {
      Parser parser = ctx.parsers().get(retailer.parser());
      return new Fetched.Parsed(url, parser.parse(retailer, result.body().orElseThrow(), url));
    } catch (ParseException e) {
      log.warn("{}: could not parse {}: {}", retailer.name(), url, e.getMessage());
      return new Fetched.Failed(url, "parse failed: " + e.getMessage());
    }
  }

  /**
   * The write half: per offer, validate specs, upsert the offer with what the listing says, record
   * one observation at the run's instant. On the caller's connection, inside the caller's
   * transaction.
   *
   * <p>Nothing here decides which product a listing is. Since M3 that is the resolver's job, run
   * over the pending offers after a cycle closes ({@code resolve/ResolutionRun}); the crawl's part
   * is to record the facts it scores — the brand, the title and the validated spec.
   */
  public Written write(
      Connection c,
      CrawlContext ctx,
      Retailer retailer,
      List<ParsedOffer> parsed,
      long runId,
      Instant observedAt)
      throws SQLException {
    int offersWritten = 0;
    int observationsWritten = 0;
    for (ParsedOffer offer : parsed) {
      SpecValidator.Result specs = ctx.specValidator().validate(offer.specFields());
      if (!specs.isClean()) {
        log.debug(
            "{}: dropped spec values for {}: {}", retailer.name(), offer.url(), specs.warnings());
      }
      long offerId =
          offers.upsert(
              c,
              new OfferDao.Listing(
                  retailer.name(),
                  offer.url(),
                  offer.title(),
                  offer.retailerSku(),
                  offer.currency(),
                  offer.brand(),
                  Normalizer.normalize(offer.brand()),
                  Jsonb.fromMap(specs.accepted())));
      offersWritten++;
      boolean inserted =
          observations.record(
              c,
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
    return new Written(offersWritten, observationsWritten);
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

  private static String extensionFor(Retailer retailer) {
    return retailer.fetch().mode() == FetchMode.API ? "json" : "html";
  }
}
