package com.achen.shelf.crawl.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.db.CrawlTaskDao;
import com.achen.shelf.testing.FixtureCategory;
import com.achen.shelf.testing.FixtureServer;
import com.achen.shelf.testing.PostgresTestBase;
import com.achen.shelf.testing.ShelfProcess;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Kill the leading coordinator; a standby takes over within one poll interval and carries on.
 *
 * <p>Two real {@code shelf coordinator} processes with the production 5s poll. Leadership is
 * observed from outside, in {@code pg_locks}: the session-scoped advisory lock is released the
 * instant the leader's process dies, and the standby's next try — at most one poll later — takes
 * it. "Carries on" is proven by planting an expired lease after the kill and watching the new
 * leader reap it.
 */
class KillCoordinatorFailoverTest extends PostgresTestBase {

  private static final Duration POLL = Duration.ofSeconds(5);

  @TempDir Path tempDir;
  private FixtureServer server;
  private Map<String, String> env;

  @BeforeEach
  void setUp() {
    server = FixtureCategory.serveStandardFixtures(FixtureServer.start());
    Path categories = FixtureCategory.write(tempDir.resolve("categories"), server);
    env =
        Map.of(
            "SHELF_DB_URL", jdbcUrl(),
            "SHELF_DB_USER", dbUser(),
            "SHELF_DB_PASSWORD", dbPassword(),
            "SHELF_CATEGORIES_DIR", categories.toString(),
            "SHELF_RAW_DIR", tempDir.resolve("raw").toString());
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private Optional<String> holder() throws SQLException {
    try (Connection c = DB.connection()) {
      return LeaderLock.currentHolder(c);
    }
  }

  private Optional<String> awaitHolder(String expected, Duration timeout) throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    Optional<String> h = holder();
    while (Instant.now().isBefore(deadline) && !h.equals(Optional.of(expected))) {
      Thread.sleep(50);
      h = holder();
    }
    return h;
  }

  /** Makes one of the run's queued tasks look like a worker died holding it, and returns its id. */
  private long plantExpiredLease(String ghost) throws SQLException {
    String sql =
        """
        update crawl_tasks
        set state = 'leased', leased_by = ?, attempts = 1,
            lease_expires_at = now() - interval '1 second'
        where id = (select min(id) from crawl_tasks where state = 'queued')
        returning id
        """;
    try (Connection c = DB.connection();
        var ps = c.prepareStatement(sql)) {
      ps.setString(1, ghost);
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).as("a queued task to orphan").isTrue();
        return rs.getLong(1);
      }
    }
  }

  private CrawlTaskDao.Row task(long id) throws SQLException {
    try (Connection c = DB.connection()) {
      return CrawlTaskDao.find(c, id).orElseThrow();
    }
  }

  private CrawlTaskDao.Row awaitReaped(long id, Duration timeout) throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    CrawlTaskDao.Row row = task(id);
    while (Instant.now().isBefore(deadline) && !"queued".equals(row.state())) {
      Thread.sleep(50);
      row = task(id);
    }
    return row;
  }

  @Test
  void aStandbyAcquiresTheLockWithinOnePollIntervalAndResumesReaping() throws Exception {
    Path logs = tempDir.resolve("logs");
    String[] common = {
      "coordinator",
      "--category",
      "keyboards",
      "--poll",
      POLL.toString(),
      "--cycle-interval",
      "PT24H"
    };
    try (ShelfProcess leader = ShelfProcess.start("coordinator-a", logs, env, args(common, "a"));
        ShelfProcess standby = ShelfProcess.start("coordinator-b", logs, env, args(common, "b"))) {

      // 1. One of them leads. (Which one is a race between two JVM start-ups; the test follows
      //    whichever won.)
      Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
      Optional<String> first = holder();
      while (Instant.now().isBefore(deadline) && first.isEmpty()) {
        Thread.sleep(50);
        first = holder();
      }
      assertThat(first).as("a leader. a:\n%s\nb:\n%s", leader.log(), standby.log()).isPresent();
      ShelfProcess victim = first.get().equals("coordinator-a") ? leader : standby;
      ShelfProcess successor = victim == leader ? standby : leader;

      // 2. It stays the leader across several polls, and does leader work: the first tick opened
      //    a cycle, and an expired lease planted now is reaped within a poll.
      Thread.sleep(POLL.plusSeconds(1).toMillis());
      assertThat(holder()).hasValue(victim.name());
      assertThat(count("select count(*) from crawl_runs")).isEqualTo(1);
      long planted = plantExpiredLease("ghost-1");
      CrawlTaskDao.Row reapedByLeader = awaitReaped(planted, POLL.plusSeconds(2));
      assertThat(reapedByLeader.state())
          .as("leader reaps. log:\n%s", victim.log())
          .isEqualTo("queued");
      assertThat(reapedByLeader.lastError()).contains("ghost-1");
      assertThat(holder()).as("the standby has not taken over").hasValue(victim.name());

      // 3. Plant a second orphaned lease, then kill the leader.
      long orphaned = plantExpiredLease("ghost-2");
      victim.kill();
      Instant killedAt = Instant.now();

      // 4. The standby holds the lock within one poll.
      Optional<String> after = awaitHolder(successor.name(), POLL.plusSeconds(3));
      Instant acquiredAt = Instant.now();
      assertThat(after)
          .as("failover. successor log:\n%s", successor.log())
          .hasValue(successor.name());
      Duration failover = Duration.between(killedAt, acquiredAt);
      assertThat(failover).isLessThanOrEqualTo(POLL.plusSeconds(1));

      // 5. ...and resumes the leader's work: the orphaned lease is reaped on its next tick, and it
      //    does not open a second cycle for a run that is still in progress.
      CrawlTaskDao.Row reapedBySuccessor = awaitReaped(orphaned, POLL.plusSeconds(2));
      Instant reapedAt = Instant.now();
      assertThat(reapedBySuccessor.state())
          .as("successor reaps. log:\n%s", successor.log())
          .isEqualTo("queued");
      assertThat(reapedBySuccessor.lastError()).contains("ghost-2");
      assertThat(successor.log()).contains("acquired leadership");
      assertThat(count("select count(*) from crawl_runs")).isEqualTo(1);

      KillWorkerRecoveryTest.record(
          "kill-coordinator",
          String.format(
              "poll=%s%n"
                  + "kill -> standby holds the advisory lock: %d ms (bound: one poll = %d ms)%n"
                  + "kill -> standby reaped an orphaned lease: %d ms%n",
              POLL,
              failover.toMillis(),
              POLL.toMillis(),
              Duration.between(killedAt, reapedAt).toMillis()));
    }
  }

  private static String[] args(String[] common, String id) {
    String[] all = new String[common.length + 2];
    System.arraycopy(common, 0, all, 0, common.length);
    all[common.length] = "--id";
    all[common.length + 1] = id;
    return all;
  }
}
