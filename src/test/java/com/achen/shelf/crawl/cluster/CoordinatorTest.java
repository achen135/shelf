package com.achen.shelf.crawl.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The coordinator stepped by hand: leadership, what a tick does, when cycles open, and one whole
 * cycle end to end with a worker. Killing a coordinator process is {@link
 * KillCoordinatorFailoverTest}.
 */
class CoordinatorTest extends PostgresTestBase {

  private static final Coordinator.Settings SETTINGS =
      new Coordinator.Settings(Duration.ofMillis(200), Duration.ofHours(1), 3);

  @TempDir Path tempDir;
  private FixtureServer server;
  private CategoryConfig category;

  /** A clock the test can move, so "an hour later" does not take an hour. */
  private Instant now = Instant.parse("2026-09-11T12:00:00Z");

  private final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now;
        }
      };

  @BeforeEach
  void setUp() {
    server = FixtureCategory.serveStandardFixtures(FixtureServer.start());
    category = FixtureCategory.load(tempDir.resolve("categories"), server);
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private LeaderLock lock(String name) {
    return new LeaderLock(jdbcUrl(), dbUser(), dbPassword(), "coordinator-" + name);
  }

  private Coordinator coordinator(String name) {
    return new Coordinator(name, lock(name), List.of(category), SETTINGS, clock);
  }

  private String holder() throws SQLException {
    try (Connection c = DB.connection()) {
      return LeaderLock.currentHolder(c).orElse(null);
    }
  }

  @Test
  void exactlyOneOfTwoCoordinatorsLeadsAndTheOtherTakesOverWhenItGoes() throws SQLException {
    Coordinator a = coordinator("a");
    try (Coordinator b = coordinator("b")) {
      Coordinator.Tick first = a.tick();
      Coordinator.Tick second = b.tick();

      assertThat(first.leader()).isTrue();
      assertThat(second.leader()).isFalse();
      assertThat(holder()).isEqualTo("coordinator-a");
      // and it stays that way however many times the standby asks
      assertThat(b.tick().leader()).isFalse();
      assertThat(a.tick().leader()).isTrue();

      a.close(); // ends the session; the lock goes with it

      assertThat(b.tick().leader()).isTrue();
      assertThat(holder()).isEqualTo("coordinator-b");
    }
  }

  @Test
  void theFirstLeaderTickOpensACycleWithPageOneOfEveryEnabledListPath() throws SQLException {
    try (Coordinator a = coordinator("a")) {
      Coordinator.Tick tick = a.tick();

      assertThat(tick.opened()).hasSize(1);
      long runId = tick.opened().get(0);
      assertThat(count("select count(*) from crawl_runs where finished_at is null")).isEqualTo(1);
      assertThat(
              count(
                  "select count(*) from crawl_runs where started_at = timestamptz"
                      + " '2026-09-11T12:00:00Z'"))
          .isEqualTo(1);
      // json_store, html_store, forbidden_store: one path each. disabled_store: none.
      assertThat(count("select count(*) from crawl_tasks where crawl_run_id = " + runId))
          .isEqualTo(3);
      assertThat(count("select count(*) from crawl_tasks where page = 1")).isEqualTo(3);
      assertThat(count("select count(*) from crawl_tasks where retailer = 'disabled_store'"))
          .isZero();
      assertThat(count("select count(*) from crawl_tasks where domain = '127.0.0.1'")).isEqualTo(3);
      assertThat(count("select count(*) from crawl_tasks where max_attempts = 3")).isEqualTo(3);
      // and the partitions the run will write into exist
      assertThat(
              count(
                  "select count(*) from pg_tables where tablename like 'price_observations_2026_%'"))
          .isGreaterThanOrEqualTo(2);
    }
  }

  @Test
  void noSecondCycleWhileOneIsOpenAndNoneUntilTheIntervalHasPassed() throws SQLException {
    try (Coordinator a = coordinator("a")) {
      long runId = a.tick().opened().get(0);
      assertThat(a.tick().opened()).as("a cycle is already open").isEmpty();

      // settle the run by hand: every task done
      execute(
          "update crawl_tasks set state = 'done', leased_by = 'w' where crawl_run_id = " + runId);
      now = now.plus(Duration.ofMinutes(30));
      Coordinator.Tick closing = a.tick();
      assertThat(closing.closed()).extracting(CrawlRunDao.Closed::runId).containsExactly(runId);
      assertThat(closing.opened()).as("the interval has not passed").isEmpty();
      assertThat(a.tick().opened()).isEmpty();

      // The interval is measured from when the run finished (database time), which is "now"
      // in real terms; the coordinator's clock has to be past that plus the interval.
      now = Instant.now().plus(Duration.ofHours(1)).plus(Duration.ofSeconds(1));
      assertThat(a.tick().opened()).as("a new cycle is due").hasSize(1);
      assertThat(count("select count(*) from crawl_runs")).isEqualTo(2);
    }
  }

  @Test
  void aStandbyDoesNothingToTheQueue() throws SQLException {
    try (Coordinator a = coordinator("a");
        Coordinator b = coordinator("b")) {
      a.tick();
      Coordinator.Tick standby = b.tick();

      assertThat(standby.leader()).isFalse();
      assertThat(standby.opened()).isEmpty();
      assertThat(count("select count(*) from crawl_runs")).isEqualTo(1);
    }
  }

  @Test
  void aTickReapsExpiredLeasesAndClosesTheRunWhenTheLastTaskSettles() throws SQLException {
    try (Coordinator a = coordinator("a")) {
      long runId = a.tick().opened().get(0);
      // two tasks done; one leased by a worker that stopped heartbeating, out of attempts
      execute(
          "update crawl_tasks set state = 'done', leased_by = 'w1'"
              + " where retailer in ('json_store', 'html_store')");
      execute(
          "update crawl_tasks set state = 'leased', leased_by = 'w2', attempts = 3,"
              + " lease_expires_at = now() - interval '1 second'"
              + " where retailer = 'forbidden_store'");

      Coordinator.Tick tick = a.tick();

      assertThat(tick.reaped().leasesDeadLettered()).isEqualTo(1);
      assertThat(tick.closed()).hasSize(1);
      CrawlRunDao.Closed closed = tick.closed().get(0);
      assertThat(closed.runId()).isEqualTo(runId);
      assertThat(closed.pages()).isEqualTo(2);
      assertThat(closed.errors()).isEqualTo(1);
      assertThat(closed.workerCount()).isEqualTo(2);
    }
  }

  @Test
  void runOnceDrivesAWholeCycleToCompletionWithAWorker() throws Exception {
    Fetcher fetcher =
        new Fetcher(
            "ShelfBot/0.1 (+https://example.test/shelf)",
            2,
            Duration.ofMillis(5),
            Duration.ofSeconds(10));
    PageCrawler pages = new PageCrawler(DB, fetcher, new RawStore(tempDir.resolve("raw")));
    Worker.Settings quick =
        new Worker.Settings(
            Duration.ofSeconds(15),
            Duration.ofSeconds(5),
            Duration.ofMillis(100),
            Duration.ofSeconds(15),
            2);
    try (Worker worker =
            new Worker(
                "w1", DB, pages, new CategoryConfigLoader(), tempDir.resolve("categories"), quick);
        Coordinator a = coordinator("a")) {
      worker.start();

      List<CrawlRunDao.Closed> closed = a.runOnce();

      assertThat(closed).hasSize(1);
      CrawlRunDao.Closed run = closed.get(0);
      // json p1 + json p2 (empty) + html p1 = 3 pages, exactly as the M1 runner counts them;
      // the forbidden page was skipped, not fetched.
      assertThat(run.pages()).isEqualTo(3);
      assertThat(run.errors()).isZero();
      assertThat(run.workerCount()).isEqualTo(1);
      try (Connection c = DB.connection()) {
        Map<String, Integer> states = CrawlTaskDao.countByState(c, run.runId());
        assertThat(states)
            .containsEntry("done", 4)
            .containsEntry("queued", 0)
            .containsEntry("leased", 0)
            .containsEntry("error", 0)
            .containsEntry("dead", 0);
      }
      assertThat(count("select count(*) from offers")).isEqualTo(FixtureCategory.EXPECTED_OFFERS);
      assertThat(count("select count(*) from price_observations"))
          .isEqualTo(FixtureCategory.EXPECTED_OFFERS);
      assertThat(count("select count(*) from products")).isEqualTo(4);
      assertThat(count("select count(*) from robots_cache")).isEqualTo(1);
      assertThat(server.hits("/robots.txt")).isEqualTo(1);
      assertThat(server.hits("/collections/never/products.json?page=1")).isZero();
    }
  }

  @Test
  void replayingACycleAtTheSameInstantWritesNoNewObservations() throws Exception {
    // The idempotency the kill-worker test rests on, at cycle level: two runs opened at the
    // same instant (a fixed clock) produce one set of observations, not two.
    Fetcher fetcher =
        new Fetcher(
            "ShelfBot/0.1 (+https://example.test/shelf)",
            2,
            Duration.ofMillis(5),
            Duration.ofSeconds(10));
    PageCrawler pages = new PageCrawler(DB, fetcher, new RawStore(tempDir.resolve("raw")));
    Worker.Settings quick =
        new Worker.Settings(
            Duration.ofSeconds(15),
            Duration.ofSeconds(5),
            Duration.ofMillis(100),
            Duration.ofSeconds(15),
            1);
    try (Worker worker =
            new Worker(
                "w1", DB, pages, new CategoryConfigLoader(), tempDir.resolve("categories"), quick);
        Coordinator a = coordinator("a")) {
      worker.start();
      a.runOnce();
      a.runOnce();

      assertThat(count("select count(*) from crawl_runs where finished_at is not null"))
          .isEqualTo(2);
      assertThat(count("select count(*) from crawl_tasks where state = 'done'")).isEqualTo(8);
      assertThat(count("select count(*) from offers")).isEqualTo(FixtureCategory.EXPECTED_OFFERS);
      assertThat(count("select count(*) from price_observations"))
          .isEqualTo(FixtureCategory.EXPECTED_OFFERS);
    }
  }
}
