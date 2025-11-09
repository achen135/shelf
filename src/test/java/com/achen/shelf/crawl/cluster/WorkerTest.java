package com.achen.shelf.crawl.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.crawl.DomainRateLimiter;
import com.achen.shelf.crawl.Fetcher;
import com.achen.shelf.crawl.PageCrawler;
import com.achen.shelf.crawl.RawStore;
import com.achen.shelf.db.CrawlRunDao;
import com.achen.shelf.db.CrawlTaskDao;
import com.achen.shelf.testing.FixtureCategory;
import com.achen.shelf.testing.FixtureServer;
import com.achen.shelf.testing.PostgresTestBase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A worker driven by hand, one task at a time, against the fixture server and a real Postgres.
 *
 * <p>These pin down what a task does to the database — offers, observations, the task row, the
 * successor page — and what happens when a page fails or a lease is lost. Process-level recovery (a
 * worker killed outright) is {@link KillWorkerRecoveryTest}.
 */
class WorkerTest extends PostgresTestBase {

  private static final Instant RUN_STARTED = Instant.parse("2026-09-11T12:00:00Z");

  @TempDir Path tempDir;
  private FixtureServer server;
  private long runId;

  @BeforeEach
  void setUp() throws SQLException {
    server = FixtureCategory.serveStandardFixtures(FixtureServer.start());
    FixtureCategory.write(tempDir.resolve("categories"), server);
    try (Connection c = DB.connection()) {
      runId = CrawlRunDao.open(c, "keyboards", RUN_STARTED, 0);
    }
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private Worker worker(String id, Worker.Settings settings) {
    Fetcher fetcher =
        new Fetcher(
            "ShelfBot/0.1 (+https://example.test/shelf)",
            1,
            Duration.ofMillis(5),
            Duration.ofSeconds(10));
    PageCrawler pages = new PageCrawler(DB, fetcher, new RawStore(tempDir.resolve("raw")));
    return new Worker(
        id, DB, pages, new CategoryConfigLoader(), tempDir.resolve("categories"), settings);
  }

  private Worker worker(String id) {
    return worker(id, Worker.Settings.defaults());
  }

  private long enqueue(String retailer, String listPath, int page) throws SQLException {
    try (Connection c = DB.connection()) {
      CrawlTaskDao.enqueue(
          c,
          runId,
          "keyboards",
          retailer,
          listPath,
          page,
          DomainRateLimiter.registrableDomain(server.baseUrl()),
          3);
      return CrawlTaskDao.find(c, runId, retailer, listPath, page).orElseThrow().id();
    }
  }

  private Optional<CrawlTaskDao.Row> find(String retailer, String listPath, int page)
      throws SQLException {
    try (Connection c = DB.connection()) {
      return CrawlTaskDao.find(c, runId, retailer, listPath, page);
    }
  }

  private CrawlTaskDao.Row row(long id) throws SQLException {
    try (Connection c = DB.connection()) {
      return CrawlTaskDao.find(c, id).orElseThrow();
    }
  }

  private CrawlTaskDao.Task claimAs(Worker w) throws SQLException {
    Optional<CrawlTaskDao.Task> task = w.claim();
    assertThat(task).isPresent();
    return task.get();
  }

  @Test
  void aPageWithOffersIsWrittenAndItsSuccessorEnqueued() throws SQLException {
    long id = enqueue("json_store", FixtureCategory.JSON_LIST_PATH, 1);
    Worker w = worker("w1");

    Worker.Disposition result = w.execute(claimAs(w));

    assertThat(result).isEqualTo(Worker.Disposition.DONE);
    assertThat(count("select count(*) from offers")).isEqualTo(6);
    assertThat(count("select count(*) from price_observations")).isEqualTo(6);
    assertThat(
            count(
                "select count(*) from price_observations"
                    + " where observed_at = timestamptz '2026-09-11T12:00:00Z'"))
        .as("every observation carries the run's start instant")
        .isEqualTo(6);
    // The page's facts are recorded; linking is the resolver's job after the run closes.
    assertThat(count("select count(*) from offers where product_id is not null")).isZero();
    assertThat(count("select count(*) from offers where brand_norm = 'keychron'")).isEqualTo(6);

    CrawlTaskDao.Row done = row(id);
    assertThat(done.state()).isEqualTo("done");
    assertThat(done.offersWritten()).isEqualTo(6);
    assertThat(done.observationsWritten()).isEqualTo(6);
    assertThat(done.leasedBy()).isEqualTo("w1");

    // page 2 was enqueued in the same transaction; page 3 was not
    assertThat(find("json_store", FixtureCategory.JSON_LIST_PATH, 2))
        .isPresent()
        .get()
        .extracting(CrawlTaskDao.Row::state)
        .isEqualTo("queued");
    assertThat(find("json_store", FixtureCategory.JSON_LIST_PATH, 3)).isEmpty();
  }

  @Test
  void anEmptyPageEndsTheListPath() throws SQLException {
    enqueue("json_store", FixtureCategory.JSON_LIST_PATH, 2);
    Worker w = worker("w1");

    assertThat(w.execute(claimAs(w))).isEqualTo(Worker.Disposition.DONE);

    assertThat(count("select count(*) from offers")).isZero();
    assertThat(find("json_store", FixtureCategory.JSON_LIST_PATH, 3)).isEmpty();
  }

  @Test
  void theLastAllowedPageHasNoSuccessor() throws SQLException {
    // html_store: max_pages 1, so even a page full of offers enqueues nothing.
    enqueue("html_store", FixtureCategory.HTML_LIST_PATH, 1);
    Worker w = worker("w1");

    assertThat(w.execute(claimAs(w))).isEqualTo(Worker.Disposition.DONE);

    assertThat(count("select count(*) from offers")).isEqualTo(4);
    assertThat(find("html_store", FixtureCategory.HTML_LIST_PATH, 2)).isEmpty();
  }

  @Test
  void aRobotsDisallowedPageIsDoneWithoutBeingFetched() throws SQLException {
    long id = enqueue("forbidden_store", FixtureCategory.FORBIDDEN_LIST_PATH, 1);
    Worker w = worker("w1");

    assertThat(w.execute(claimAs(w))).isEqualTo(Worker.Disposition.DONE);

    assertThat(row(id).state()).isEqualTo("done");
    assertThat(count("select count(*) from crawl_tasks where skipped_by_robots")).isEqualTo(1);
    assertThat(server.hits("/collections/keyboards/products.json?sort_by=price&page=1")).isZero();
    assertThat(count("select count(*) from raw_fetches")).isZero();
  }

  @Test
  void aFailingPageIsRetriedWithBackoffAndThenDeadLettered() throws SQLException {
    server.serveSequence(
        "/collections/keyboards/products.json?limit=250&page=1",
        List.of(FixtureServer.Response.status(503, "down")));
    long id = enqueue("json_store", FixtureCategory.JSON_LIST_PATH, 1);
    Worker w =
        worker(
            "w1",
            new Worker.Settings(
                Duration.ofSeconds(15),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                1));

    // attempt 1: error, retry in 60s
    assertThat(w.execute(claimAs(w))).isEqualTo(Worker.Disposition.RETRY);
    CrawlTaskDao.Row afterFirst = row(id);
    assertThat(afterFirst.state()).isEqualTo("error");
    assertThat(afterFirst.lastError()).contains("HTTP 503");
    assertThat(afterFirst.notBefore()).isAfter(Instant.now().plus(Duration.ofSeconds(50)));
    assertThat(w.claim()).isEmpty();

    // attempt 2: backoff doubles to 120s
    requeue(id);
    assertThat(w.execute(claimAs(w))).isEqualTo(Worker.Disposition.RETRY);
    assertThat(row(id).notBefore()).isAfter(Instant.now().plus(Duration.ofSeconds(110)));

    // attempt 3 of 3: dead
    requeue(id);
    assertThat(w.execute(claimAs(w))).isEqualTo(Worker.Disposition.DEAD);
    assertThat(row(id).state()).isEqualTo("dead");

    // three real requests were made, every one audited, nothing written
    assertThat(server.hits("/collections/keyboards/products.json?limit=250&page=1")).isEqualTo(3);
    assertThat(count("select count(*) from raw_fetches where status = 503")).isEqualTo(3);
    assertThat(count("select count(*) from offers")).isZero();
    assertThat(find("json_store", FixtureCategory.JSON_LIST_PATH, 2)).isEmpty();
  }

  @Test
  void aWorkerThatLostItsLeaseRollsBackAndWritesNothing() throws Exception {
    // The fencing case: w1 takes the page, stalls in the fetch long enough for its lease to be
    // reaped, and w2 takes and finishes the page. When w1 finally tries to settle, it must find
    // the row is not its own and write nothing — the observations must be w2's, and there must
    // be exactly one set of them.
    server.serveSequence(
        "/collections/keyboards/products.json?limit=250&page=1",
        List.of(
            FixtureServer.Response.ok(
                    FixtureServer.Fixtures.read("shopify/keychron-products.json"),
                    "application/json")
                .delayedBy(Duration.ofSeconds(3)),
            FixtureServer.Response.ok(
                FixtureServer.Fixtures.read("shopify/keychron-products.json"),
                "application/json")));
    long id = enqueue("json_store", FixtureCategory.JSON_LIST_PATH, 1);
    Worker.Settings shortLease =
        new Worker.Settings(
            Duration.ofSeconds(1),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(15),
            1);
    Worker stalled = worker("w1", shortLease);
    Worker healthy = worker("w2", shortLease);

    CrawlTaskDao.Task w1Task = claimAs(stalled);
    Thread w1 = Thread.ofVirtual().start(() -> resultOf(stalled, w1Task));
    // w1 is (without a heartbeat thread, since it was never start()ed) stuck in a 3s fetch.
    Thread.sleep(1500);
    try (Connection c = DB.connection()) {
      assertThat(CrawlTaskDao.reap(c).leasesRequeued()).isEqualTo(1);
    }
    CrawlTaskDao.Task w2Task = claimAs(healthy);
    assertThat(w2Task.id()).isEqualTo(id);
    assertThat(w2Task.attempts()).isEqualTo(2);
    assertThat(healthy.execute(w2Task)).isEqualTo(Worker.Disposition.DONE);
    w1.join(Duration.ofSeconds(10).toMillis());

    assertThat(lastResult).isEqualTo(Worker.Disposition.LOST_LEASE);
    CrawlTaskDao.Row done = row(id);
    assertThat(done.state()).isEqualTo("done");
    assertThat(done.leasedBy()).isEqualTo("w2");
    assertThat(count("select count(*) from price_observations")).isEqualTo(6);
    assertThat(count("select count(*) from offers")).isEqualTo(6);
    // both fetches happened and were audited; only one set of results exists
    assertThat(count("select count(*) from raw_fetches")).isEqualTo(2);
  }

  private volatile Worker.Disposition lastResult;

  private void resultOf(Worker w, CrawlTaskDao.Task task) {
    lastResult = w.execute(task);
  }

  @Test
  void aHeartbeatingWorkerKeepsALongTaskLeased() throws Exception {
    // The opposite of the case above: a page that takes longer than the lease is fine as long as
    // the worker is alive to heartbeat. The reaper must find nothing to take back.
    server.serveSequence(
        "/collections/keyboards/products.json?limit=250&page=1",
        List.of(
            FixtureServer.Response.ok(
                    FixtureServer.Fixtures.read("shopify/keychron-products.json"),
                    "application/json")
                .delayedBy(Duration.ofSeconds(3))));
    long id = enqueue("json_store", FixtureCategory.JSON_LIST_PATH, 1);
    Worker.Settings shortLease =
        new Worker.Settings(
            Duration.ofSeconds(1),
            Duration.ofMillis(300),
            Duration.ofMillis(200),
            Duration.ofSeconds(15),
            1);

    try (Worker w = worker("w1", shortLease)) {
      w.start();
      Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
      int reapChecks = 0;
      while (Instant.now().isBefore(deadline) && !row(id).state().equals("done")) {
        try (Connection c = DB.connection()) {
          assertThat(CrawlTaskDao.reap(c).leasesRequeued())
              .as("reaper found an expired lease")
              .isZero();
        }
        CrawlTaskDao.Row now = row(id); // one read: the task may finish between two
        if (now.state().equals("leased")) {
          assertThat(now.leaseExpiresAt()).isAfter(Instant.now());
          reapChecks++;
        }
        Thread.sleep(250);
      }
      assertThat(reapChecks)
          .as("the task was observed leased across several reaper passes")
          .isGreaterThan(3);
    }

    CrawlTaskDao.Row done = row(id);
    assertThat(done.state()).isEqualTo("done");
    assertThat(done.attempts()).isEqualTo(1);
    assertThat(count("select count(*) from price_observations")).isEqualTo(6);
  }

  @Test
  void workersOnOneDomainShareTheConfiguredCeiling() throws Exception {
    // Four workers, eight pages, one domain configured at 2 rps. If each worker kept its own
    // budget the server would see bursts; with the bucket in Postgres, consecutive requests are
    // at least 500ms apart no matter who sent them.
    for (int page = 1; page <= 8; page++) {
      // html_store has max_pages 1, so these are plain leaf tasks with no successors.
      enqueue("html_store", FixtureCategory.HTML_LIST_PATH, page);
    }
    Worker.Settings fast =
        new Worker.Settings(
            Duration.ofSeconds(15),
            Duration.ofSeconds(5),
            Duration.ofMillis(100),
            Duration.ofSeconds(15),
            1);
    List<Worker> workers =
        List.of(worker("w1", fast), worker("w2", fast), worker("w3", fast), worker("w4", fast));
    try {
      workers.forEach(Worker::start);
      Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
      while (Instant.now().isBefore(deadline)
          && count("select count(*) from crawl_tasks where state = 'done'") < 8) {
        Thread.sleep(100);
      }
    } finally {
      workers.forEach(Worker::stop);
    }

    assertThat(count("select count(*) from crawl_tasks where state = 'done'")).isEqualTo(8);
    assertThat(count("select count(*) from crawl_tasks where skipped_by_robots"))
        .as("a robots.txt fetch failed and the worker refused the page (fail closed)")
        .isZero();
    assertThat(count("select count(distinct leased_by) from crawl_tasks")).isGreaterThan(1);
    List<Instant> pages =
        server.requestTimes(FixtureCategory.HTML_LIST_PATH).stream().sorted().toList();
    assertThat(pages).hasSize(8);

    // The slots themselves are exactly 500ms apart in database time — RateLimitDaoTest proves
    // that. What arrives at the server is slot + sleep wake-up + scheduling, which on a loaded
    // CI runner jitters by tens of milliseconds either way, so the exact interval is not
    // asserted per gap here. Two things are: the pool's overall rate — eight requests through one
    // 2 rps bucket cannot span less than 7 × 500ms, whereas four private budgets would have
    // finished in about a second — and the absence of any burst, two requests inside half an
    // interval, which is what per-worker budgets would have produced.
    Duration span = Duration.between(pages.get(0), pages.get(pages.size() - 1));
    assertThat(span)
        .as("8 requests through one 2 rps bucket")
        .isGreaterThanOrEqualTo(Duration.ofMillis(3400));
    for (int i = 1; i < pages.size(); i++) {
      assertThat(Duration.between(pages.get(i - 1), pages.get(i)))
          .as("gap before page request %d", i)
          .isGreaterThanOrEqualTo(Duration.ofMillis(250));
    }
  }

  private void requeue(long id) throws SQLException {
    execute("update crawl_tasks set not_before = now() - interval '1 second' where id = " + id);
    try (Connection c = DB.connection()) {
      assertThat(CrawlTaskDao.reap(c).retriesPromoted()).isEqualTo(1);
    }
  }
}
