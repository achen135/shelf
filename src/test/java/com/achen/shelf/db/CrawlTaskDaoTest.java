package com.achen.shelf.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.testing.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The queue's state machine, against the real database — because {@code FOR UPDATE SKIP LOCKED},
 * row locks and {@code on conflict} are exactly the things a stand-in would not exercise.
 */
class CrawlTaskDaoTest extends PostgresTestBase {

  private static final Duration LEASE = Duration.ofSeconds(15);
  private static final String PATH = "/collections/all/products.json?page={page}";

  private long runId;

  @BeforeEach
  void openRun() throws SQLException {
    try (Connection c = DB.connection()) {
      runId = CrawlRunDao.open(c, "keyboards", Instant.parse("2026-09-11T12:00:00Z"), 0);
    }
  }

  private long enqueue(String retailer, int page, String domain, int maxAttempts)
      throws SQLException {
    try (Connection c = DB.connection()) {
      assertThat(
              CrawlTaskDao.enqueue(
                  c, runId, "keyboards", retailer, PATH, page, domain, maxAttempts))
          .isTrue();
      return CrawlTaskDao.find(c, runId, retailer, PATH, page).orElseThrow().id();
    }
  }

  private Optional<CrawlTaskDao.Task> claim(String worker) throws SQLException {
    try (Connection c = DB.connection()) {
      return CrawlTaskDao.claim(c, worker, LEASE);
    }
  }

  /** Claims and asserts something was there to claim. */
  private CrawlTaskDao.Task mustClaim(String worker) throws SQLException {
    Optional<CrawlTaskDao.Task> task = claim(worker);
    assertThat(task).as("a task for %s", worker).isPresent();
    return task.get();
  }

  private CrawlTaskDao.Row row(long id) throws SQLException {
    try (Connection c = DB.connection()) {
      return CrawlTaskDao.find(c, id).orElseThrow();
    }
  }

  private void sql(String statement) throws SQLException {
    try (Connection c = DB.connection();
        Statement s = c.createStatement()) {
      s.execute(statement);
    }
  }

  @Test
  void enqueueIsIdempotentPerRunAndPage() throws SQLException {
    enqueue("keychron", 1, "keychron.com", 3);
    try (Connection c = DB.connection()) {
      assertThat(
              CrawlTaskDao.enqueue(c, runId, "keyboards", "keychron", PATH, 1, "keychron.com", 3))
          .isFalse();
    }
    assertThat(count("select count(*) from crawl_tasks")).isEqualTo(1);
  }

  @Test
  void claimLeasesTheOldestQueuedTaskAndChargesTheAttempt() throws SQLException {
    long first = enqueue("keychron", 1, "keychron.com", 3);
    enqueue("keychron", 2, "keychron.com", 3);

    CrawlTaskDao.Task task = claim("w1").orElseThrow();

    assertThat(task.id()).isEqualTo(first);
    assertThat(task.attempts()).isEqualTo(1);
    assertThat(task.runStartedAt()).isEqualTo(Instant.parse("2026-09-11T12:00:00Z"));
    CrawlTaskDao.Row row = row(first);
    assertThat(row.state()).isEqualTo("leased");
    assertThat(row.leasedBy()).isEqualTo("w1");
    assertThat(row.leaseExpiresAt()).isAfter(Instant.now().plus(Duration.ofSeconds(10)));
    assertThat(row.startedAt()).isNotNull();
  }

  @Test
  void claimReturnsEmptyWhenNothingIsQueued() throws SQLException {
    assertThat(claim("w1")).isEmpty();
  }

  @Test
  void aTaskAnotherWorkerIsMidClaimIsSkippedNotWaitedFor() throws SQLException {
    // The SKIP LOCKED contract: w1 has locked the first row and not yet committed. w2 must get the
    // second row immediately, not block on the first.
    long first = enqueue("keychron", 1, "keychron.com", 3);
    long second = enqueue("keychron", 2, "keychron.com", 3);

    try (Connection w1 = DB.connection()) {
      w1.setAutoCommit(false);
      try {
        assertThat(CrawlTaskDao.claim(w1, "w1", LEASE).orElseThrow().id()).isEqualTo(first);

        CrawlTaskDao.Task w2Task = claim("w2").orElseThrow();
        assertThat(w2Task.id()).isEqualTo(second);
        // and with both locked, a third worker finds nothing rather than waiting
        assertThat(claim("w3")).isEmpty();
      } finally {
        w1.rollback();
        w1.setAutoCommit(true);
      }
    }
    // w1 rolled back, so its task is queued again and claimable.
    assertThat(claim("w3").orElseThrow().id()).isEqualTo(first);
  }

  @Test
  void concurrentWorkersNeverClaimTheSameTask() throws Exception {
    int tasks = 40;
    for (int page = 1; page <= tasks; page++) {
      enqueue("keychron", page, "keychron.com", 3);
    }
    Set<Long> claimed = ConcurrentHashMap.newKeySet();
    List<Long> all = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int w = 0; w < 8; w++) {
        String worker = "w" + w;
        futures.add(
            pool.submit(
                () -> {
                  int n = 0;
                  Optional<CrawlTaskDao.Task> t;
                  while ((t = claim(worker)).isPresent()) {
                    if (!claimed.add(t.get().id())) {
                      throw new AssertionError("task " + t.get().id() + " claimed twice");
                    }
                    n++;
                  }
                  return n;
                }));
      }
      for (Future<Integer> f : futures) {
        f.get();
      }
    }
    assertThat(claimed).hasSize(tasks);
    assertThat(count("select count(*) from crawl_tasks where state = 'leased'")).isEqualTo(tasks);
    assertThat(count("select count(distinct leased_by) from crawl_tasks")).isGreaterThan(1);
    all.addAll(claimed);
    assertThat(all).doesNotHaveDuplicates();
  }

  @Test
  void claimPrefersTheDomainWhoseNextSlotComesSoonest() throws SQLException {
    long busy = enqueue("keychron", 1, "keychron.com", 3);
    long idle = enqueue("kbdfans", 1, "kbdfans.com", 3);
    long fresh = enqueue("epomaker", 1, "epomaker.com", 3);
    sql("insert into domain_rate_limits values ('keychron.com', now() + interval '10 seconds')");
    sql("insert into domain_rate_limits values ('kbdfans.com', now() + interval '1 second')");

    // A domain never reserved sorts first, then the soonest slot, then the one 10s out —
    // regardless of the tasks' ids.
    assertThat(claim("w").orElseThrow().id()).isEqualTo(fresh);
    assertThat(claim("w").orElseThrow().id()).isEqualTo(idle);
    assertThat(claim("w").orElseThrow().id()).isEqualTo(busy);
  }

  @Test
  void aTaskWhoseBackoffHasNotPassedIsNotClaimable() throws SQLException {
    long id = enqueue("keychron", 1, "keychron.com", 3);
    sql("update crawl_tasks set not_before = now() + interval '1 hour' where id = " + id);

    assertThat(claim("w")).isEmpty();
  }

  @Test
  void completeIsFencedToTheLeaseHolder() throws SQLException {
    long id = enqueue("keychron", 1, "keychron.com", 3);
    mustClaim("w1");
    CrawlTaskDao.Outcome outcome = new CrawlTaskDao.Outcome(10, 10, 10, 2, false);

    try (Connection c = DB.connection()) {
      assertThat(CrawlTaskDao.lockIfHeld(c, id, "w2")).isFalse();
      assertThat(CrawlTaskDao.complete(c, id, "w2", outcome)).isFalse();
      assertThat(row(id).state()).isEqualTo("leased");

      assertThat(CrawlTaskDao.lockIfHeld(c, id, "w1")).isTrue();
      assertThat(CrawlTaskDao.complete(c, id, "w1", outcome)).isTrue();
    }
    CrawlTaskDao.Row done = row(id);
    assertThat(done.state()).isEqualTo("done");
    assertThat(done.leasedBy()).isEqualTo("w1"); // kept, for the record
    assertThat(done.leaseExpiresAt()).isNull();
    assertThat(done.finishedAt()).isNotNull();
    assertThat(done.offersWritten()).isEqualTo(10);
    // and a done task can be neither completed nor failed again
    try (Connection c = DB.connection()) {
      assertThat(CrawlTaskDao.complete(c, id, "w1", outcome)).isFalse();
      assertThat(CrawlTaskDao.fail(c, id, "w1", "late", Duration.ZERO)).isEmpty();
    }
  }

  @Test
  void failRetriesWithBackoffThenDeadLettersAtMaxAttempts() throws SQLException {
    long id = enqueue("keychron", 1, "keychron.com", 2);

    // attempt 1 fails → error, retry after backoff
    mustClaim("w1");
    try (Connection c = DB.connection()) {
      assertThat(CrawlTaskDao.fail(c, id, "w1", "HTTP 503", Duration.ofMinutes(5)))
          .hasValue("error");
      CrawlTaskDao.Row row = row(id);
      assertThat(row.state()).isEqualTo("error");
      assertThat(row.lastError()).isEqualTo("HTTP 503");
      assertThat(row.notBefore()).isAfter(Instant.now().plus(Duration.ofMinutes(4)));

      // not claimable, and the reaper leaves it alone while the backoff runs
      assertThat(CrawlTaskDao.claim(c, "w1", LEASE)).isEmpty();
      assertThat(CrawlTaskDao.reap(c).retriesPromoted()).isZero();
    }

    // backoff passes → the reaper promotes it → claimable as attempt 2
    sql("update crawl_tasks set not_before = now() - interval '1 second' where id = " + id);
    try (Connection c = DB.connection()) {
      assertThat(CrawlTaskDao.reap(c).retriesPromoted()).isEqualTo(1);
    }
    assertThat(row(id).state()).isEqualTo("queued");
    CrawlTaskDao.Task second = claim("w2").orElseThrow();
    assertThat(second.attempts()).isEqualTo(2);

    // attempt 2 of 2 fails → dead
    try (Connection c = DB.connection()) {
      assertThat(CrawlTaskDao.fail(c, id, "w2", "HTTP 503 again", Duration.ofMinutes(5)))
          .hasValue("dead");
    }
    CrawlTaskDao.Row dead = row(id);
    assertThat(dead.state()).isEqualTo("dead");
    assertThat(dead.finishedAt()).isNotNull();
    assertThat(dead.lastError()).isEqualTo("HTTP 503 again");
    assertThat(claim("w3")).isEmpty();
  }

  @Test
  void reapRequeuesExpiredLeasesAndDeadLettersThoseOutOfAttempts() throws SQLException {
    long recoverable = enqueue("keychron", 1, "keychron.com", 3);
    long exhausted = enqueue("keychron", 2, "keychron.com", 1);
    long healthy = enqueue("keychron", 3, "keychron.com", 3);
    mustClaim("dead-worker");
    mustClaim("dead-worker");
    mustClaim("live-worker");
    sql(
        "update crawl_tasks set lease_expires_at = now() - interval '1 second'"
            + " where leased_by = 'dead-worker'");

    CrawlTaskDao.Reaped reaped;
    try (Connection c = DB.connection()) {
      reaped = CrawlTaskDao.reap(c);
    }

    assertThat(reaped.leasesRequeued()).isEqualTo(1);
    assertThat(reaped.leasesDeadLettered()).isEqualTo(1);
    CrawlTaskDao.Row requeued = row(recoverable);
    assertThat(requeued.state()).isEqualTo("queued");
    assertThat(requeued.lastError()).contains("lease expired").contains("dead-worker");
    assertThat(requeued.attempts()).isEqualTo(1); // the failed claim stays charged
    assertThat(row(exhausted).state()).isEqualTo("dead");
    assertThat(row(healthy).state()).isEqualTo("leased"); // the live worker's lease is untouched

    // the requeued task goes to whoever asks next, as attempt 2
    assertThat(claim("w9").orElseThrow().attempts()).isEqualTo(2);
  }

  @Test
  void heartbeatExtendsOnlyThisWorkersLiveLeases() throws SQLException {
    long mine = enqueue("keychron", 1, "keychron.com", 3);
    long theirs = enqueue("keychron", 2, "keychron.com", 3);
    mustClaim("me");
    mustClaim("them");
    sql("update crawl_tasks set lease_expires_at = now() + interval '1 second'");
    Instant before = row(mine).leaseExpiresAt();

    int extended;
    try (Connection c = DB.connection()) {
      extended = CrawlTaskDao.heartbeat(c, "me", LEASE);
    }

    assertThat(extended).isEqualTo(1);
    assertThat(row(mine).leaseExpiresAt()).isAfter(before.plus(Duration.ofSeconds(10)));
    assertThat(row(theirs).leaseExpiresAt()).isBefore(Instant.now().plus(Duration.ofSeconds(2)));
  }

  @Test
  void releaseHandsATaskBackUncharged() throws SQLException {
    long id = enqueue("keychron", 1, "keychron.com", 3);
    mustClaim("w1");

    try (Connection c = DB.connection()) {
      assertThat(CrawlTaskDao.release(c, id, "w1")).isTrue();
    }

    CrawlTaskDao.Row row = row(id);
    assertThat(row.state()).isEqualTo("queued");
    assertThat(row.attempts()).isZero();
    assertThat(claim("w2").orElseThrow().attempts()).isEqualTo(1);
  }

  @Test
  void countByStateCoversEveryState() throws SQLException {
    enqueue("keychron", 1, "keychron.com", 3);
    long leased = enqueue("keychron", 2, "keychron.com", 3);
    mustClaim("w"); // takes page 1
    mustClaim("w"); // takes page 2
    try (Connection c = DB.connection()) {
      CrawlTaskDao.complete(c, leased, "w", new CrawlTaskDao.Outcome(0, 0, 0, 0, false));
      assertThat(CrawlTaskDao.countByState(c, runId))
          .containsEntry("queued", 0)
          .containsEntry("leased", 1)
          .containsEntry("done", 1)
          .containsEntry("error", 0)
          .containsEntry("dead", 0);
    }
  }
}
