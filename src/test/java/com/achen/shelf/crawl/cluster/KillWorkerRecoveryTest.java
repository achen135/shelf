package com.achen.shelf.crawl.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.db.CrawlTaskDao;
import com.achen.shelf.testing.FixtureCategory;
import com.achen.shelf.testing.FixtureServer;
import com.achen.shelf.testing.PostgresTestBase;
import com.achen.shelf.testing.ShelfProcess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Kill a worker mid-task; the cycle still finishes, with every observation written exactly once.
 *
 * <p>Real processes, real signals, production timings: a {@code shelf coordinator --once}, a {@code
 * shelf worker} that is SIGKILLed while it is inside a fetch, and a second worker started
 * afterwards. The dead worker's lease (15s, heartbeat every 5s) expires; the coordinator's next
 * tick (every 5s) puts the page back in the queue; the survivor takes it. The bound this test
 * asserts and records is therefore <b>lease + poll = 20s</b> from the kill to the survivor's claim.
 *
 * <p>Zero double-writes: the page's observations are keyed on {@code (offer_id, observed_at)} with
 * {@code observed_at} fixed at the run's start, so even a worker killed after writing but before
 * committing (impossible here — they commit together — but the property holds regardless) could not
 * leave a second price point behind. The assertion is made against the database, not the code.
 */
class KillWorkerRecoveryTest extends PostgresTestBase {

  private static final Duration LEASE = Duration.ofSeconds(15);
  private static final Duration POLL = Duration.ofSeconds(5);

  @TempDir Path tempDir;
  private FixtureServer server;
  private Map<String, String> env;

  @BeforeEach
  void setUp() {
    server = FixtureCategory.serveStandardFixtures(FixtureServer.start());
    // The page the first worker will take: its first response is held for a minute, long enough
    // to be killed inside it; the retry after recovery is answered at once.
    String body = FixtureServer.Fixtures.read("shopify/keychron-products.json");
    server.serveSequence(
        FixtureCategory.JSON_PAGE_1,
        List.of(
            FixtureServer.Response.ok(body, "application/json").delayedBy(Duration.ofSeconds(60)),
            FixtureServer.Response.ok(body, "application/json")));
    Path categories = FixtureCategory.write(tempDir.resolve("categories"), server);
    env =
        Map.of(
            "SHELF_DB_URL", jdbcUrl(),
            "SHELF_DB_USER", dbUser(),
            "SHELF_DB_PASSWORD", dbPassword(),
            "SHELF_CATEGORIES_DIR", categories.toString(),
            "SHELF_RAW_DIR", tempDir.resolve("raw").toString(),
            "SHELF_CRAWLER_CONTACT", "https://example.test/shelf");
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private Optional<CrawlTaskDao.Row> slowTask() throws SQLException {
    try (Connection c = DB.connection()) {
      long runId = count("select coalesce(max(id), 0) from crawl_runs");
      return CrawlTaskDao.find(c, runId, "json_store", FixtureCategory.JSON_LIST_PATH, 1);
    }
  }

  @Test
  void aKilledWorkersLeasedPageIsFinishedByASurvivorWithNoDuplicateObservations() throws Exception {
    Path logs = tempDir.resolve("logs");
    try (ShelfProcess coordinator =
            ShelfProcess.start(
                "coordinator",
                logs,
                env,
                "coordinator",
                "--category",
                "keyboards",
                "--id",
                "coord",
                "--poll",
                POLL.toString(),
                "--once");
        ShelfProcess victim =
            ShelfProcess.start(
                "worker-a",
                logs,
                env,
                "worker",
                "--id",
                "worker-a",
                "--lease",
                LEASE.toString(),
                "--heartbeat",
                "PT5S")) {

      // 1. Wait for the victim to be inside the slow page: the task is leased to it.
      Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
      Optional<CrawlTaskDao.Row> leased = Optional.empty();
      while (Instant.now().isBefore(deadline)) {
        leased = slowTask().filter(t -> "leased".equals(t.state()));
        if (leased.isPresent()) {
          break;
        }
        Thread.sleep(100);
      }
      assertThat(leased)
          .as(
              "worker-a leased the slow page. coordinator log:\n%s\nworker-a log:\n%s",
              coordinator.log(), victim.log())
          .isPresent();
      assertThat(leased.get().leasedBy()).isEqualTo("worker-a");
      assertThat(leased.get().attempts()).isEqualTo(1);
      // Let it get properly stuck — and heartbeat at least once — before pulling the plug.
      Thread.sleep(Duration.ofSeconds(6).toMillis());
      assertThat(slowTask().orElseThrow().state()).isEqualTo("leased");

      // 2. SIGKILL. No shutdown hook, no release, no rollback: the lease is simply orphaned.
      victim.kill();
      Instant killedAt = Instant.now();
      assertThat(victim.isAlive()).isFalse();

      // 3. A survivor arrives. It has nothing to do until the coordinator reaps the lease.
      try (ShelfProcess survivor =
          ShelfProcess.start(
              "worker-b",
              logs,
              env,
              "worker",
              "--id",
              "worker-b",
              "--lease",
              LEASE.toString(),
              "--heartbeat",
              "PT5S")) {

        // 4. The cycle finishes; `--once` exits with the run's summary.
        int exit = coordinator.waitFor(Duration.ofMinutes(2));
        assertThat(exit)
            .as(
                "coordinator exit code. log:\n%s\nworker-b log:\n%s",
                coordinator.log(), survivor.log())
            .isZero();
        Instant finishedAt = Instant.now();

        // 5. The page was recovered: same task row, second attempt, finished by the survivor.
        CrawlTaskDao.Row recovered = slowTask().orElseThrow();
        assertThat(recovered.state()).isEqualTo("done");
        assertThat(recovered.leasedBy()).isEqualTo("worker-b");
        assertThat(recovered.attempts()).isEqualTo(2);
        assertThat(coordinator.log()).contains("reaped 1 expired lease");
        assertThat(count("select count(*) from crawl_tasks where state = 'done'")).isEqualTo(4);
        assertThat(
                count(
                    "select count(*) from crawl_tasks where state in ('queued','leased','error','dead')"))
            .isZero();
        assertThat(
                count(
                    "select count(*) from crawl_runs where finished_at is not null and errors = 0"))
            .isEqualTo(1);
        // worker_count is the workers that settled a task. worker-a settled none — it was killed
        // inside its only one — so the run honestly records a single worker.
        assertThat(count("select worker_count from crawl_runs")).isEqualTo(1);

        // 6. Zero double-writes, asserted against the data: one observation per offer, all of
        //    them at the run's instant, and the offer set itself written once.
        assertThat(count("select count(*) from offers")).isEqualTo(FixtureCategory.EXPECTED_OFFERS);
        assertThat(count("select count(*) from price_observations"))
            .isEqualTo(FixtureCategory.EXPECTED_OFFERS);
        assertThat(count("select count(distinct (offer_id, observed_at)) from price_observations"))
            .isEqualTo(count("select count(*) from price_observations"));
        assertThat(count("select count(distinct observed_at) from price_observations"))
            .isEqualTo(1);
        assertThat(count("select count(distinct offer_id) from price_observations"))
            .isEqualTo(FixtureCategory.EXPECTED_OFFERS);
        // both fetches of the slow page are in the audit log — the one that was killed and the
        // one that finished — which is the honest record of what happened
        assertThat(server.hits(FixtureCategory.JSON_PAGE_1)).isEqualTo(2);

        // 7. Recovery time: kill → survivor's claim, bounded by lease + poll.
        Duration recovery = Duration.between(killedAt, recovered.startedAt());
        Duration bound = LEASE.plus(POLL);
        assertThat(recovery).isPositive().isLessThanOrEqualTo(bound.plus(Duration.ofSeconds(2)));
        record(
            "kill-worker",
            String.format(
                "lease=%s heartbeat=PT5S poll=%s%n"
                    + "kill -> survivor claimed the page: %d ms (bound: lease + poll = %d ms)%n"
                    + "kill -> cycle finished:            %d ms%n"
                    + "observations: %d for %d offers, %d distinct (offer_id, observed_at)%n"
                    + "task attempts: %d, finished by: %s%n",
                LEASE,
                POLL,
                recovery.toMillis(),
                bound.toMillis(),
                Duration.between(killedAt, finishedAt).toMillis(),
                count("select count(*) from price_observations"),
                count("select count(*) from offers"),
                count("select count(distinct (offer_id, observed_at)) from price_observations"),
                recovered.attempts(),
                recovered.leasedBy()));
      }
    }
  }

  /** Writes the measured numbers where the benchmark doc can pick them up. */
  static void record(String name, String text) throws IOException {
    Path dir = Files.createDirectories(Path.of("build", "benchmarks"));
    Files.writeString(dir.resolve(name + ".txt"), text, StandardCharsets.UTF_8);
    System.out.println("RECOVERY[" + name + "]\n" + text);
  }
}
