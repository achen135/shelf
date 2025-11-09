package com.achen.shelf.crawl.cluster;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.Retailer;
import com.achen.shelf.crawl.DomainRateLimiter;
import com.achen.shelf.db.CrawlRunDao;
import com.achen.shelf.db.CrawlTaskDao;
import com.achen.shelf.db.PartitionDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The coordinator: whichever instance holds the {@link LeaderLock} enqueues crawl cycles, reaps
 * expired leases and closes finished runs. Every other instance is a standby that polls for the
 * lock.
 *
 * <p>One tick, every {@code poll}:
 *
 * <ol>
 *   <li>Try for the lock. Not ours → we are a standby; sleep and try again.
 *   <li>Reap: leases that expired go back to the queue (or to dead at {@code max_attempts});
 *       retries whose backoff has passed become claimable.
 *   <li>Close any run whose tasks have all settled, recording pages / errors / workers.
 *   <li>For each category with no open run whose last run is older than {@code cycleInterval}, open
 *       a run and enqueue page 1 of every enabled retailer's list paths. Workers discover later
 *       pages themselves.
 * </ol>
 *
 * <p>All of it runs on the lock's own session, in one transaction per tick. A leader whose session
 * has died cannot commit a tick, so there is never a moment where two instances both act as leader
 * — the lock and the work share a fate.
 *
 * <p>The poll interval is the failover bound: a standby notices a dead leader on its next try,
 * which is at most one {@code poll} after the leader's session ended.
 *
 * <p>After a tick commits, an {@link AfterRun} hook is told about each run that closed — that is
 * where entity resolution runs (M3), on its own connection, outside the leader's transaction. A
 * hook that throws is logged and does not affect the tick; whatever it failed to do is redone the
 * next time a run closes, because its work is idempotent by contract.
 */
public final class Coordinator implements AutoCloseable {

  /** Called by the leader, after the tick that closed a run has committed. */
  @FunctionalInterface
  public interface AfterRun {
    void runClosed(CrawlRunDao.Closed run) throws Exception;

    /** Does nothing. */
    AfterRun NONE = run -> {};
  }

  private static final Logger log = LoggerFactory.getLogger(Coordinator.class);

  /** Timing, all overridable from the CLI. */
  public record Settings(Duration poll, Duration cycleInterval, int maxAttempts) {

    /**
     * The defaults the failover claim is stated against: a 5s poll (so leadership moves within 5s),
     * a new cycle every 6 hours, and three attempts per page before dead-lettering.
     */
    public static Settings defaults() {
      return new Settings(Duration.ofSeconds(5), Duration.ofHours(6), 3);
    }

    public Settings {
      if (maxAttempts < 1) {
        throw new IllegalArgumentException("maxAttempts must be at least 1");
      }
    }
  }

  /** What one tick did, for logs and tests. */
  public record Tick(
      boolean leader,
      CrawlTaskDao.Reaped reaped,
      List<CrawlRunDao.Closed> closed,
      List<Long> opened) {
    public Tick {
      closed = List.copyOf(closed);
      opened = List.copyOf(opened);
    }

    static Tick standby() {
      return new Tick(false, new CrawlTaskDao.Reaped(0, 0, 0), List.of(), List.of());
    }
  }

  private final String id;
  private final LeaderLock lock;
  private final List<CategoryConfig> categories;
  private final Settings settings;
  private final Clock clock;
  private final AfterRun afterRun;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private boolean wasLeader;

  public Coordinator(
      String id, LeaderLock lock, List<CategoryConfig> categories, Settings settings, Clock clock) {
    this(id, lock, categories, settings, clock, AfterRun.NONE);
  }

  public Coordinator(
      String id,
      LeaderLock lock,
      List<CategoryConfig> categories,
      Settings settings,
      Clock clock,
      AfterRun afterRun) {
    this.id = id;
    this.lock = lock;
    this.categories = List.copyOf(categories);
    this.settings = settings;
    this.clock = clock;
    this.afterRun = afterRun;
  }

  public String id() {
    return id;
  }

  /** Ticks every {@code poll} until {@link #stop()} or interruption. */
  public void run() throws InterruptedException {
    running.set(true);
    log.info(
        "coordinator {} started: poll={} cycleInterval={} categories={}",
        id,
        settings.poll(),
        settings.cycleInterval(),
        categories.stream().map(CategoryConfig::name).toList());
    while (running.get()) {
      tick();
      Thread.sleep(settings.poll().toMillis());
    }
  }

  /**
   * Waits for leadership, opens one cycle per category right now, ticks until every one of those
   * runs has closed, and returns the closed runs. For the throughput benchmark and the tests.
   */
  public List<CrawlRunDao.Closed> runOnce() throws InterruptedException {
    running.set(true);
    while (running.get() && !lock.tryAcquire()) {
      log.info("coordinator {}: waiting for leadership", id);
      Thread.sleep(settings.poll().toMillis());
    }
    log.info("coordinator {}: acquired leadership", id);
    wasLeader = true;
    List<Long> runIds = new ArrayList<>();
    try {
      Connection c = lock.connection();
      c.setAutoCommit(false);
      try {
        for (CategoryConfig category : categories) {
          openCycle(c, category).ifPresent(runIds::add);
        }
        c.commit();
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    } catch (SQLException e) {
      lock.sessionFailed();
      throw new IllegalStateException("could not open the cycle: " + e.getMessage(), e);
    }

    List<CrawlRunDao.Closed> closed = new ArrayList<>();
    while (running.get() && closed.size() < runIds.size()) {
      Thread.sleep(settings.poll().toMillis());
      Tick tick = tick();
      tick.closed().stream().filter(r -> runIds.contains(r.runId())).forEach(closed::add);
    }
    return closed;
  }

  public void stop() {
    running.set(false);
  }

  /** One tick. Public so tests can step the coordinator by hand. */
  public Tick tick() {
    if (!lock.tryAcquire()) {
      if (wasLeader) {
        log.warn("coordinator {}: lost leadership", id);
        wasLeader = false;
      }
      return Tick.standby();
    }
    if (!wasLeader) {
      log.info("coordinator {}: acquired leadership", id);
      wasLeader = true;
    }

    Tick tick = leaderTick();
    tick.closed().forEach(this::afterRun);
    return tick;
  }

  /** The leader's transaction: reap, close, open. */
  private Tick leaderTick() {
    Connection c = lock.connection();
    try {
      c.setAutoCommit(false);
      try {
        CrawlTaskDao.Reaped reaped = CrawlTaskDao.reap(c);
        if (reaped.anything()) {
          log.info(
              "coordinator {}: reaped {} expired lease(s) ({} dead-lettered), promoted {} retries",
              id,
              reaped.leasesRequeued() + reaped.leasesDeadLettered(),
              reaped.leasesDeadLettered(),
              reaped.retriesPromoted());
        }
        List<CrawlRunDao.Closed> closed = CrawlRunDao.closeFinished(c);
        for (CrawlRunDao.Closed run : closed) {
          log.info(
              "coordinator {}: run {} ({}) finished: pages={} errors={} workers={}",
              id,
              run.runId(),
              run.category(),
              run.pages(),
              run.errors(),
              run.workerCount());
        }
        List<Long> opened = new ArrayList<>();
        for (CategoryConfig category : categories) {
          if (cycleDue(c, category)) {
            openCycle(c, category).ifPresent(opened::add);
          }
        }
        c.commit();
        return new Tick(true, reaped, closed, opened);
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    } catch (SQLException e) {
      // The session is suspect; treat the lock as lost until the next tick proves otherwise.
      log.warn("coordinator {}: tick failed, standing down: {}", id, e.getMessage());
      lock.sessionFailed();
      wasLeader = false;
      return Tick.standby();
    }
  }

  private void afterRun(CrawlRunDao.Closed run) {
    try {
      afterRun.runClosed(run);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.warn(
          "coordinator {}: post-run hook failed for run {} ({}): {}",
          id,
          run.runId(),
          run.category(),
          e.toString());
    }
  }

  private boolean cycleDue(Connection c, CategoryConfig category) throws SQLException {
    if (CrawlRunDao.findOpen(c, category.name()).isPresent()) {
      return false;
    }
    Optional<Instant> last = CrawlRunDao.lastFinishedAt(c, category.name());
    return last.isEmpty() || last.get().plus(settings.cycleInterval()).isBefore(clock.instant());
  }

  /**
   * Opens a run and enqueues its first pages. Returns empty, opening nothing, for a category with
   * no enabled retailers — a run with no tasks would never close.
   */
  private Optional<Long> openCycle(Connection c, CategoryConfig category) throws SQLException {
    if (category.enabledRetailers().isEmpty()) {
      log.warn("coordinator {}: category {} has no enabled retailers", id, category.name());
      return Optional.empty();
    }
    Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    PartitionDao.ensurePartition(c, startedAt);
    PartitionDao.ensurePartition(c, startedAt.plus(31, ChronoUnit.DAYS));
    long runId = CrawlRunDao.open(c, category.name(), startedAt, 0);
    int enqueued = 0;
    for (Retailer retailer : category.enabledRetailers()) {
      String domain = DomainRateLimiter.registrableDomain(retailer.baseUrl());
      for (String listPath : retailer.fetch().listPaths()) {
        if (CrawlTaskDao.enqueue(
            c,
            runId,
            category.name(),
            retailer.name(),
            listPath,
            1,
            domain,
            settings.maxAttempts())) {
          enqueued++;
        }
      }
    }
    log.info(
        "coordinator {}: opened run {} for {} at {}: {} list path(s) enqueued",
        id,
        runId,
        category.name(),
        startedAt,
        enqueued);
    return Optional.of(runId);
  }

  @Override
  public void close() {
    stop();
    lock.close();
  }
}
