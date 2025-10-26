package com.achen.shelf.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.testing.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The coordinator's view of {@code crawl_runs}: when a run is finished and what it closes with. */
class CrawlRunDaoTest extends PostgresTestBase {

  private static final String PATH = "/p.json?page={page}";

  private long open(Connection c) throws SQLException {
    return CrawlRunDao.open(c, "keyboards", Instant.now(), 0);
  }

  private long task(Connection c, long runId, int page, String state, String worker)
      throws SQLException {
    CrawlTaskDao.enqueue(c, runId, "keyboards", "r", PATH, page, "r.test", 3);
    long id = CrawlTaskDao.find(c, runId, "r", PATH, page).orElseThrow().id();
    try (var ps =
        c.prepareStatement("update crawl_tasks set state = ?, leased_by = ? where id = ?")) {
      ps.setString(1, state);
      ps.setString(2, worker);
      ps.setLong(3, id);
      ps.executeUpdate();
    }
    return id;
  }

  @Test
  void closesARunOnceEveryTaskHasSettled() throws SQLException {
    try (Connection c = DB.connection()) {
      long runId = open(c);
      task(c, runId, 1, "done", "w1");
      task(c, runId, 2, "done", "w2");
      task(c, runId, 3, "dead", "w1");
      long skipped = task(c, runId, 4, "done", "w2");
      try (var ps =
          c.prepareStatement("update crawl_tasks set skipped_by_robots = true where id = ?")) {
        ps.setLong(1, skipped);
        ps.executeUpdate();
      }

      List<CrawlRunDao.Closed> closed = CrawlRunDao.closeFinished(c);

      assertThat(closed).hasSize(1);
      CrawlRunDao.Closed run = closed.get(0);
      assertThat(run.runId()).isEqualTo(runId);
      assertThat(run.pages()).isEqualTo(2); // done, minus the robots-skipped page
      assertThat(run.errors()).isEqualTo(1);
      assertThat(run.workerCount()).isEqualTo(2);
      assertThat(CrawlRunDao.isFinished(c, runId)).isTrue();
      assertThat(CrawlRunDao.findOpen(c, "keyboards")).isEmpty();
      assertThat(CrawlRunDao.lastFinishedAt(c, "keyboards")).isPresent();

      // idempotent: nothing left to close
      assertThat(CrawlRunDao.closeFinished(c)).isEmpty();
    }
  }

  @Test
  void leavesARunOpenWhileAnyTaskIsLive() throws SQLException {
    try (Connection c = DB.connection()) {
      for (String live : List.of("queued", "leased", "error")) {
        long runId = open(c);
        task(c, runId, 1, "done", "w1");
        task(c, runId, 2, live, "w1");

        assertThat(CrawlRunDao.closeFinished(c)).as(live).isEmpty();
        assertThat(CrawlRunDao.isFinished(c, runId)).as(live).isFalse();
        execute("delete from crawl_tasks; delete from crawl_runs;");
      }
    }
  }

  @Test
  void neverClosesARunThatHasNoTasks() throws SQLException {
    // A `shelf crawl --once` run is task-less and closes itself; the coordinator must not close
    // it out from under the runner.
    try (Connection c = DB.connection()) {
      long runId = open(c);

      assertThat(CrawlRunDao.closeFinished(c)).isEmpty();
      assertThat(CrawlRunDao.findOpen(c, "keyboards")).hasValue(runId);
    }
  }

  @Test
  void openRunLookupIgnoresOtherCategories() throws SQLException {
    try (Connection c = DB.connection()) {
      CrawlRunDao.open(c, "monitors", Instant.now().minus(Duration.ofHours(1)), 0);
      assertThat(CrawlRunDao.findOpen(c, "keyboards")).isEmpty();
      assertThat(CrawlRunDao.lastFinishedAt(c, "keyboards")).isEmpty();
    }
  }
}
