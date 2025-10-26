package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The work queue: {@code crawl_tasks}.
 *
 * <p>Every method takes the caller's {@link Connection} rather than borrowing one from the pool,
 * because each is a step inside a transaction whose boundaries the caller decides — a worker
 * commits a page's rows and its task's completion together, the coordinator enqueues a cycle on the
 * session that holds its leader lock. There is no "convenience" overload that opens its own
 * connection, so that no caller can accidentally split what must be atomic.
 *
 * <p>The fencing rule, applied in {@link #complete} and {@link #fail}: a worker may only settle a
 * task it still holds ({@code leased_by = me and state = 'leased'}). A worker that stalled long
 * enough for the coordinator to reap its lease finds zero rows updated, and must roll back — the
 * task belongs to whoever claimed it next.
 */
public final class CrawlTaskDao {

  private CrawlTaskDao() {}

  /** A claimed task: the unit of work a worker executes. */
  public record Task(
      long id,
      long runId,
      String category,
      String retailer,
      String listPath,
      int page,
      String domain,
      int attempts,
      int maxAttempts,
      Instant runStartedAt) {}

  /** What executing a task produced; stored on the row so a run's totals are queryable. */
  public record Outcome(
      int offersSeen,
      int offersWritten,
      int observationsWritten,
      int matched,
      boolean skippedByRobots) {}

  /** What one reaper pass did. */
  public record Reaped(int leasesRequeued, int leasesDeadLettered, int retriesPromoted) {
    public boolean anything() {
      return leasesRequeued + leasesDeadLettered + retriesPromoted > 0;
    }
  }

  /** A full row, for the CLI and for tests to assert against. */
  public record Row(
      long id,
      long runId,
      String retailer,
      String listPath,
      int page,
      String state,
      String leasedBy,
      Instant leaseExpiresAt,
      int attempts,
      int maxAttempts,
      Instant notBefore,
      String lastError,
      Instant startedAt,
      Instant finishedAt,
      int offersWritten,
      int observationsWritten) {}

  // ---------------------------------------------------------------------------------------
  // coordinator side
  // ---------------------------------------------------------------------------------------

  /**
   * Adds a page to a run's queue. Idempotent on {@code (run, retailer, list_path, page)}.
   *
   * @return true if the row was inserted, false if that page was already queued for this run
   */
  public static boolean enqueue(
      Connection c,
      long runId,
      String category,
      String retailer,
      String listPath,
      int page,
      String domain,
      int maxAttempts)
      throws SQLException {
    String sql =
        """
        insert into crawl_tasks (crawl_run_id, category, retailer, list_path, page, domain,
                                 max_attempts)
        values (?, ?, ?, ?, ?, ?, ?)
        on conflict (crawl_run_id, retailer, list_path, page) do nothing
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, runId);
      ps.setString(2, category);
      ps.setString(3, retailer);
      ps.setString(4, listPath);
      ps.setInt(5, page);
      ps.setString(6, domain);
      ps.setInt(7, maxAttempts);
      return ps.executeUpdate() > 0;
    }
  }

  /**
   * Recovers work from workers that stopped heartbeating, and releases retries whose backoff has
   * passed.
   *
   * <p>An expired lease goes back to {@code queued} — or to {@code dead} if it has already been
   * claimed {@code max_attempts} times, since the claim that expired was charged when it was made.
   * Nothing here needs to know whether the worker is alive: a live worker that merely stalled will
   * find itself fenced out when it tries to commit.
   */
  public static Reaped reap(Connection c) throws SQLException {
    int requeued = 0;
    int dead = 0;
    String expire =
        """
        update crawl_tasks
        set state = case when attempts >= max_attempts then 'dead' else 'queued' end,
            finished_at = case when attempts >= max_attempts then now() else null end,
            last_error = 'lease expired while held by ' || coalesce(leased_by, '?'),
            lease_expires_at = null
        where state = 'leased' and lease_expires_at < now()
        returning state
        """;
    try (PreparedStatement ps = c.prepareStatement(expire);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        if (rs.getString(1).equals("dead")) {
          dead++;
        } else {
          requeued++;
        }
      }
    }
    String promote =
        "update crawl_tasks set state = 'queued' where state = 'error' and not_before <= now()";
    int promoted;
    try (PreparedStatement ps = c.prepareStatement(promote)) {
      promoted = ps.executeUpdate();
    }
    return new Reaped(requeued, dead, promoted);
  }

  // ---------------------------------------------------------------------------------------
  // worker side
  // ---------------------------------------------------------------------------------------

  /**
   * Takes one queued task, if there is one, and leases it to {@code workerId}.
   *
   * <p>{@code FOR UPDATE SKIP LOCKED} is the whole trick: N workers run this concurrently, each
   * locks the first row it can and skips rows another worker is in the middle of taking, so no two
   * workers ever claim the same task and none of them waits on another. The order is by the domain
   * whose next politeness slot comes soonest, so a pool spreads itself across retailers rather than
   * piling up behind one domain's rate limit; ties go to the oldest task.
   *
   * <p>The claim commits on its own, before any work starts, so that the lease is visible to the
   * reaper the moment it exists.
   */
  public static Optional<Task> claim(Connection c, String workerId, Duration lease)
      throws SQLException {
    String sql =
        """
        with next as (
          select t.id
          from crawl_tasks t
          left join domain_rate_limits d on d.domain = t.domain
          where t.state = 'queued' and t.not_before <= now()
          order by coalesce(d.next_allowed_at, '-infinity'::timestamptz), t.id
          limit 1
          for update of t skip locked
        )
        update crawl_tasks t
        set state = 'leased',
            leased_by = ?,
            lease_expires_at = now() + make_interval(secs => ?),
            attempts = t.attempts + 1,
            started_at = now(),
            last_error = null
        from next
        where t.id = next.id
        returning t.id, t.crawl_run_id, t.category, t.retailer, t.list_path, t.page, t.domain,
                  t.attempts, t.max_attempts,
                  (select started_at from crawl_runs r where r.id = t.crawl_run_id)
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, workerId);
      ps.setDouble(2, seconds(lease));
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Task(
                rs.getLong(1),
                rs.getLong(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getInt(6),
                rs.getString(7),
                rs.getInt(8),
                rs.getInt(9),
                rs.getTimestamp(10).toInstant()));
      }
    }
  }

  /**
   * Extends every lease this worker holds. One statement per worker per beat, however many tasks it
   * is running — and a task the reaper has already taken back is simply not matched.
   *
   * @return how many leases were extended
   */
  public static int heartbeat(Connection c, String workerId, Duration lease) throws SQLException {
    String sql =
        "update crawl_tasks set lease_expires_at = now() + make_interval(secs => ?)"
            + " where leased_by = ? and state = 'leased'";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setDouble(1, seconds(lease));
      ps.setString(2, workerId);
      return ps.executeUpdate();
    }
  }

  /**
   * Locks the task row for the rest of the caller's transaction, if this worker still holds its
   * lease.
   *
   * <p>The fence, taken before a worker writes a page's rows: if the lease was reaped this returns
   * false and the worker must roll back; if it was not, the row lock now blocks the reaper (and the
   * worker's own heartbeat) on this row until the transaction ends, so a task that has started to
   * settle cannot be taken away half-way through.
   */
  public static boolean lockIfHeld(Connection c, long taskId, String workerId) throws SQLException {
    String sql =
        "select 1 from crawl_tasks where id = ? and leased_by = ? and state = 'leased' for update";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, taskId);
      ps.setString(2, workerId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /**
   * Hands a task back to the queue without charging the attempt — for a worker that was asked to
   * stop while holding it. Fenced like {@link #complete}.
   */
  public static boolean release(Connection c, long taskId, String workerId) throws SQLException {
    String sql =
        """
        update crawl_tasks
        set state = 'queued', lease_expires_at = null, attempts = attempts - 1
        where id = ? and leased_by = ? and state = 'leased'
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, taskId);
      ps.setString(2, workerId);
      return ps.executeUpdate() == 1;
    }
  }

  /**
   * Marks a task done with what it produced. Fenced: returns false, having changed nothing, if this
   * worker no longer holds the lease — the caller must then roll back its transaction.
   */
  public static boolean complete(Connection c, long taskId, String workerId, Outcome outcome)
      throws SQLException {
    String sql =
        """
        update crawl_tasks
        set state = 'done', finished_at = now(), lease_expires_at = null,
            offers_seen = ?, offers_written = ?, observations_written = ?, matched = ?,
            skipped_by_robots = ?
        where id = ? and leased_by = ? and state = 'leased'
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setInt(1, outcome.offersSeen());
      ps.setInt(2, outcome.offersWritten());
      ps.setInt(3, outcome.observationsWritten());
      ps.setInt(4, outcome.matched());
      ps.setBoolean(5, outcome.skippedByRobots());
      ps.setLong(6, taskId);
      ps.setString(7, workerId);
      return ps.executeUpdate() == 1;
    }
  }

  /**
   * Records a failed attempt: the task goes to {@code error} to be retried after {@code backoff},
   * or to {@code dead} if this was its last allowed attempt. Fenced like {@link #complete}.
   *
   * @return the resulting state, or empty if this worker no longer held the lease
   */
  public static Optional<String> fail(
      Connection c, long taskId, String workerId, String error, Duration backoff)
      throws SQLException {
    String sql =
        """
        update crawl_tasks
        set state = case when attempts >= max_attempts then 'dead' else 'error' end,
            finished_at = case when attempts >= max_attempts then now() else null end,
            not_before = now() + make_interval(secs => ?),
            last_error = ?,
            lease_expires_at = null
        where id = ? and leased_by = ? and state = 'leased'
        returning state
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setDouble(1, seconds(backoff));
      ps.setString(2, error);
      ps.setLong(3, taskId);
      ps.setString(4, workerId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // reads
  // ---------------------------------------------------------------------------------------

  /** One task, in full. */
  public static Optional<Row> find(Connection c, long taskId) throws SQLException {
    String sql =
        """
        select id, crawl_run_id, retailer, list_path, page, state, leased_by, lease_expires_at,
               attempts, max_attempts, not_before, last_error, started_at, finished_at,
               offers_written, observations_written
        from crawl_tasks where id = ?
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, taskId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(row(rs)) : Optional.empty();
      }
    }
  }

  /** The task for a page of a run, if it has been enqueued. */
  public static Optional<Row> find(
      Connection c, long runId, String retailer, String listPath, int page) throws SQLException {
    String sql =
        """
        select id, crawl_run_id, retailer, list_path, page, state, leased_by, lease_expires_at,
               attempts, max_attempts, not_before, last_error, started_at, finished_at,
               offers_written, observations_written
        from crawl_tasks
        where crawl_run_id = ? and retailer = ? and list_path = ? and page = ?
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, runId);
      ps.setString(2, retailer);
      ps.setString(3, listPath);
      ps.setInt(4, page);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(row(rs)) : Optional.empty();
      }
    }
  }

  /** How many of a run's tasks are in each state, in state-machine order. */
  public static Map<String, Integer> countByState(Connection c, long runId) throws SQLException {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String s : new String[] {"queued", "leased", "done", "error", "dead"}) {
      counts.put(s, 0);
    }
    String sql = "select state, count(*) from crawl_tasks where crawl_run_id = ? group by state";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, runId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          counts.put(rs.getString(1), rs.getInt(2));
        }
      }
    }
    return counts;
  }

  private static Row row(ResultSet rs) throws SQLException {
    return new Row(
        rs.getLong(1),
        rs.getLong(2),
        rs.getString(3),
        rs.getString(4),
        rs.getInt(5),
        rs.getString(6),
        rs.getString(7),
        instant(rs, 8),
        rs.getInt(9),
        rs.getInt(10),
        instant(rs, 11),
        rs.getString(12),
        instant(rs, 13),
        instant(rs, 14),
        rs.getInt(15),
        rs.getInt(16));
  }

  private static Instant instant(ResultSet rs, int column) throws SQLException {
    var ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }

  private static double seconds(Duration d) {
    return d.toNanos() / 1_000_000_000.0;
  }
}
