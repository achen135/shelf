package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads and writes {@code crawl_runs}, the per-cycle bookkeeping row. */
public final class CrawlRunDao {

  /** A run the coordinator closed, with the totals it closed it with. */
  public record Closed(long runId, String category, int pages, int errors, int workerCount) {}

  private final Database db;

  public CrawlRunDao(Database db) {
    this.db = db;
  }

  /**
   * Opens a run and returns its id.
   *
   * <p>{@code started_at} is passed in rather than defaulted, because it doubles as the {@code
   * observed_at} of every observation in the run: one crawl cycle is one point in time, so a
   * repeated cycle cannot half-overwrite an earlier one.
   */
  public long open(String category, Instant startedAt, int workerCount) throws SQLException {
    try (Connection c = db.connection()) {
      return open(c, category, startedAt, workerCount);
    }
  }

  /** As above, on a caller-owned connection. */
  public static long open(Connection c, String category, Instant startedAt, int workerCount)
      throws SQLException {
    String sql =
        "insert into crawl_runs (category, started_at, worker_count) values (?, ?, ?) returning id";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, category);
      ps.setTimestamp(2, Timestamp.from(startedAt));
      ps.setInt(3, workerCount);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** Closes a run with its page and error counts. */
  public void finish(long runId, int pages, int errors, Instant finishedAt) throws SQLException {
    String sql = "update crawl_runs set finished_at = ?, pages = ?, errors = ? where id = ?";
    try (Connection c = db.connection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(finishedAt));
      ps.setInt(2, pages);
      ps.setInt(3, errors);
      ps.setLong(4, runId);
      ps.executeUpdate();
    }
  }

  /**
   * Records a backfill as a run of its own, already finished, spanning the instants it wrote — both
   * in the past, so it is never the run a cycle-due or a retirement question finds first.
   */
  public static long openBackfill(Connection c, String category, Instant from, Instant to)
      throws SQLException {
    String sql =
        "insert into crawl_runs (category, kind, started_at, finished_at, worker_count)"
            + " values (?, 'backfill', ?, ?, 0) returning id";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, category);
      ps.setTimestamp(2, Timestamp.from(from));
      ps.setTimestamp(3, Timestamp.from(to));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** The most recent crawl run for a category that has not finished, if there is one. */
  public static Optional<Long> findOpen(Connection c, String category) throws SQLException {
    String sql =
        "select id from crawl_runs where category = ? and kind = 'crawl' and finished_at is null"
            + " order by started_at desc limit 1";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, category);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
      }
    }
  }

  /** When the most recent finished crawl run for a category finished, if any has. */
  public static Optional<Instant> lastFinishedAt(Connection c, String category)
      throws SQLException {
    String sql =
        "select finished_at from crawl_runs where category = ? and kind = 'crawl'"
            + " and finished_at is not null order by finished_at desc limit 1";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, category);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getTimestamp(1).toInstant()) : Optional.empty();
      }
    }
  }

  /** True once the run has a {@code finished_at}. */
  public static boolean isFinished(Connection c, long runId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("select finished_at is not null from crawl_runs where id = ?")) {
      ps.setLong(1, runId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  /**
   * Closes every open run that has no task left in a live state, and returns what was closed.
   *
   * <p>A run is finished when nothing is {@code queued}, {@code leased} or {@code error}; what
   * remains is {@code done} and {@code dead}. Pages are the done tasks that actually fetched
   * something (a page robots.txt refused was never a page, which is how M1 counted too), errors the
   * dead ones, and {@code worker_count} is how many distinct workers touched the run — measured,
   * not configured, which is the number the throughput benchmark wants.
   *
   * <p>Only runs that have tasks qualify. A {@code shelf crawl --once} run (M1's single-process
   * path) has none and closes itself, and a coordinator that happened to be running alongside it
   * must not close it first.
   */
  public static List<Closed> closeFinished(Connection c) throws SQLException {
    String sql =
        """
        update crawl_runs r
        set finished_at = now(),
            pages = s.pages,
            errors = s.errors,
            worker_count = greatest(s.workers, 1)
        from (
          select r.id,
                 count(*) filter (where t.state = 'done' and not t.skipped_by_robots) as pages,
                 count(*) filter (where t.state = 'dead') as errors,
                 count(distinct t.leased_by) as workers
          from crawl_runs r
          join crawl_tasks t on t.crawl_run_id = r.id
          where r.finished_at is null
          group by r.id
          having count(*) filter (where t.state in ('queued', 'leased', 'error')) = 0
        ) s
        where r.id = s.id
        returning r.id, r.category, r.pages, r.errors, r.worker_count
        """;
    List<Closed> closed = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        closed.add(
            new Closed(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)));
      }
    }
    return closed;
  }
}
