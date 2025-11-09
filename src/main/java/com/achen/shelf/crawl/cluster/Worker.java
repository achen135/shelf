package com.achen.shelf.crawl.cluster;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.config.Retailer;
import com.achen.shelf.crawl.CrawlContext;
import com.achen.shelf.crawl.PageCrawler;
import com.achen.shelf.crawl.ParsedOffer;
import com.achen.shelf.db.CrawlTaskDao;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.ProductDao;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A crawl worker: claims tasks from {@code crawl_tasks}, runs {@link PageCrawler} on each, and
 * settles them.
 *
 * <p>One process runs {@code concurrency} claim loops on virtual threads plus one heartbeat thread.
 * The lifecycle of a task in a worker's hands:
 *
 * <ol>
 *   <li><b>Claim</b> ({@code FOR UPDATE SKIP LOCKED}), which leases the task for {@code lease} and
 *       commits at once so the lease is visible.
 *   <li><b>Heartbeat</b>: every {@code heartbeat}, one statement extends every lease this worker
 *       holds. Lease is three beats, so two can be missed before the coordinator takes the task
 *       back.
 *   <li><b>Fetch</b> (no transaction): robots, rate limit, HTTP, raw body, audit row, parse.
 *   <li><b>Settle</b>, in one transaction: lock the task row to prove the lease is still ours,
 *       write the page's offers and observations, mark the task done, and enqueue the next page if
 *       this one had offers and the retailer allows more. A worker that lost its lease finds the
 *       row is not its own and rolls back — whoever holds it now owns the page.
 * </ol>
 *
 * <p>Failure is a state, not an exception: a fetch or parse failure marks the task {@code error}
 * with an exponential backoff, or {@code dead} on its last allowed attempt, and the worker moves
 * on. The process itself never exits on a bad page.
 */
public final class Worker implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(Worker.class);

  /** Timing and sizing, all overridable from the CLI. */
  public record Settings(
      Duration lease,
      Duration heartbeat,
      Duration idlePoll,
      Duration retryBackoffBase,
      int concurrency) {

    /**
     * The defaults the recovery claims are stated against: a 15s lease heartbeated every 5s, so a
     * dead worker's tasks are back in the queue within 15s of its last beat; retries at 15s, 30s, …
     * after a failure.
     */
    public static Settings defaults() {
      return new Settings(
          Duration.ofSeconds(15),
          Duration.ofSeconds(5),
          Duration.ofSeconds(1),
          Duration.ofSeconds(15),
          1);
    }

    public Settings {
      if (heartbeat.compareTo(lease) >= 0) {
        throw new IllegalArgumentException(
            "heartbeat (" + heartbeat + ") must be shorter than the lease (" + lease + ")");
      }
      if (concurrency < 1) {
        throw new IllegalArgumentException("concurrency must be at least 1");
      }
    }
  }

  /** How a task left this worker's hands. */
  public enum Disposition {
    /** Written and marked done. */
    DONE,
    /** Failed; marked error, will be retried after backoff. */
    RETRY,
    /** Failed on its last allowed attempt; dead-lettered. */
    DEAD,
    /** The coordinator reaped our lease before we could settle; nothing was written. */
    LOST_LEASE,
    /** We were asked to stop mid-task; the task went back to the queue uncharged. */
    RELEASED
  }

  /** Thrown inside the settle transaction when the task row is no longer ours; rolls it back. */
  private static final class LostLease extends RuntimeException {
    private static final long serialVersionUID = 1L;

    LostLease(long taskId) {
      super("task " + taskId + " is no longer leased by this worker");
    }
  }

  private final String id;
  private final Database db;
  private final PageCrawler pages;
  private final CategoryConfigLoader loader;
  private final Path categoriesDir;
  private final Settings settings;
  private final ProductDao products;

  private final Map<String, CrawlContext> contexts = new HashMap<>();
  private final ReentrantLock contextsLock = new ReentrantLock();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final List<Thread> threads = new ArrayList<>();

  public Worker(
      String id,
      Database db,
      PageCrawler pages,
      CategoryConfigLoader loader,
      Path categoriesDir,
      Settings settings) {
    this.id = id;
    this.db = db;
    this.pages = pages;
    this.loader = loader;
    this.categoriesDir = categoriesDir;
    this.settings = settings;
    this.products = new ProductDao(db);
  }

  /** The id this worker leases tasks under: {@code <host>-<pid>}. */
  public static String defaultId() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      host = "worker";
    }
    return host + "-" + ProcessHandle.current().pid();
  }

  public String id() {
    return id;
  }

  /** Starts the claim loops and the heartbeat. Returns at once. */
  public synchronized void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    for (int i = 0; i < settings.concurrency(); i++) {
      threads.add(Thread.ofVirtual().name(id + "-loop-" + i).start(this::loop));
    }
    threads.add(Thread.ofVirtual().name(id + "-heartbeat").start(this::heartbeatLoop));
    log.info(
        "worker {} started: concurrency={} lease={} heartbeat={}",
        id,
        settings.concurrency(),
        settings.lease(),
        settings.heartbeat());
  }

  /** Stops the loops, releasing any task mid-flight back to the queue, and waits for them. */
  public synchronized void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    threads.forEach(Thread::interrupt);
    for (Thread t : threads) {
      try {
        t.join(Duration.ofSeconds(10).toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    threads.clear();
    log.info("worker {} stopped", id);
  }

  /** Blocks the calling thread until {@link #stop()} is called from elsewhere. */
  public void awaitStop() throws InterruptedException {
    while (running.get()) {
      Thread.sleep(200);
    }
  }

  @Override
  public void close() {
    stop();
  }

  private void loop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      Optional<CrawlTaskDao.Task> claimed;
      try {
        claimed = claim();
      } catch (SQLException e) {
        log.warn("worker {}: claim failed: {}", id, e.getMessage());
        claimed = Optional.empty();
      }
      if (claimed.isEmpty()) {
        try {
          // A little jitter so N idle workers do not poll the queue in lockstep.
          Thread.sleep(settings.idlePoll().toMillis() + ThreadLocalRandom.current().nextLong(200));
        } catch (InterruptedException e) {
          return;
        }
        continue;
      }
      execute(claimed.get());
    }
  }

  /** Claims one task, if any is queued. */
  public Optional<CrawlTaskDao.Task> claim() throws SQLException {
    try (Connection c = db.connection()) {
      return CrawlTaskDao.claim(c, id, settings.lease());
    }
  }

  /** Runs one claimed task to a settled state. Public so tests can drive a worker by hand. */
  public Disposition execute(CrawlTaskDao.Task task) {
    log.info(
        "worker {}: task {} claimed: {} {} page {} (attempt {}/{})",
        id,
        task.id(),
        task.retailer(),
        task.listPath(),
        task.page(),
        task.attempts(),
        task.maxAttempts());
    PageCrawler.Fetched fetched;
    CrawlContext ctx;
    Retailer retailer;
    try {
      ctx = contextFor(task.category());
      retailer = ctx.retailer(task.retailer());
      fetched =
          pages.fetch(
              ctx, new PageCrawler.Target(retailer, task.listPath(), task.page()), task.runId());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return release(task);
    } catch (SQLException | RuntimeException e) {
      return fail(task, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    return switch (fetched) {
      // The Fetcher reports an interrupt as a failed fetch; on shutdown that is a release, not
      // a strike against the page.
      case PageCrawler.Fetched.Failed f ->
          Thread.currentThread().isInterrupted() ? release(task) : fail(task, f.reason());
      case PageCrawler.Fetched.SkippedByRobots s ->
          settle(task, ctx, retailer, List.of(), true, /* more pages */ true);
      case PageCrawler.Fetched.Parsed p ->
          settle(task, ctx, retailer, p.offers(), false, !p.offers().isEmpty());
    };
  }

  private Disposition settle(
      CrawlTaskDao.Task task,
      CrawlContext ctx,
      Retailer retailer,
      List<ParsedOffer> offers,
      boolean skippedByRobots,
      boolean continuePath) {
    try {
      db.transaction(
          c -> {
            // Lock our own row first. If the coordinator reaped the lease, this finds nothing and
            // we write nothing; if it has not, holding the row lock means it cannot reap us while
            // we are committing — a task that has begun settling finishes settling.
            if (!CrawlTaskDao.lockIfHeld(c, task.id(), id)) {
              throw new LostLease(task.id());
            }
            PageCrawler.Written written =
                pages.write(c, ctx, retailer, offers, task.runId(), task.runStartedAt());
            CrawlTaskDao.Outcome outcome =
                new CrawlTaskDao.Outcome(
                    offers.size(),
                    written.offersWritten(),
                    written.observationsWritten(),
                    skippedByRobots);
            if (!CrawlTaskDao.complete(c, task.id(), id, outcome)) {
              throw new LostLease(task.id());
            }
            if (continuePath && task.page() < retailer.fetch().maxPages()) {
              CrawlTaskDao.enqueue(
                  c,
                  task.runId(),
                  task.category(),
                  task.retailer(),
                  task.listPath(),
                  task.page() + 1,
                  task.domain(),
                  task.maxAttempts());
            }
            return null;
          });
    } catch (LostLease e) {
      log.warn("worker {}: task {} lost its lease before settling; rolled back", id, task.id());
      return Disposition.LOST_LEASE;
    } catch (SQLException e) {
      // The page's rows were rolled back with the task still leased to us; report the failure
      // so it is retried rather than left to expire.
      return fail(task, "settle failed: " + e.getMessage());
    }
    log.info(
        "worker {}: task {} done: {} page {} offers={}{}",
        id,
        task.id(),
        task.retailer(),
        task.page(),
        offers.size(),
        skippedByRobots ? " (skipped by robots.txt)" : "");
    return Disposition.DONE;
  }

  private Disposition fail(CrawlTaskDao.Task task, String reason) {
    Duration backoff = settings.retryBackoffBase().multipliedBy(1L << (task.attempts() - 1));
    try {
      Optional<String> state =
          db.transaction(c -> CrawlTaskDao.fail(c, task.id(), id, reason, backoff));
      if (state.isEmpty()) {
        log.warn(
            "worker {}: task {} lost its lease before failing could be recorded", id, task.id());
        return Disposition.LOST_LEASE;
      }
      if (state.get().equals("dead")) {
        log.error(
            "worker {}: task {} dead after {} attempts: {}",
            id,
            task.id(),
            task.attempts(),
            reason);
        return Disposition.DEAD;
      }
      log.warn(
          "worker {}: task {} failed (attempt {}/{}), retry in {}: {}",
          id,
          task.id(),
          task.attempts(),
          task.maxAttempts(),
          backoff,
          reason);
      return Disposition.RETRY;
    } catch (SQLException e) {
      // Nothing more we can do from here: the lease will expire and the coordinator will requeue.
      log.error(
          "worker {}: could not record failure for task {}: {}", id, task.id(), e.getMessage());
      return Disposition.LOST_LEASE;
    }
  }

  private Disposition release(CrawlTaskDao.Task task) {
    try {
      db.transaction(c -> CrawlTaskDao.release(c, task.id(), id));
      log.info("worker {}: task {} released on shutdown", id, task.id());
    } catch (SQLException e) {
      log.warn("worker {}: could not release task {}: {}", id, task.id(), e.getMessage());
    }
    return Disposition.RELEASED;
  }

  private void heartbeatLoop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        Thread.sleep(settings.heartbeat().toMillis());
      } catch (InterruptedException e) {
        return;
      }
      try (Connection c = db.connection()) {
        int extended = CrawlTaskDao.heartbeat(c, id, settings.lease());
        if (extended > 0) {
          log.debug("worker {}: heartbeat extended {} lease(s)", id, extended);
        }
      } catch (SQLException e) {
        // Missing a beat is what the lease is for; log it and try again next beat.
        log.warn("worker {}: heartbeat failed: {}", id, e.getMessage());
      }
    }
  }

  /**
   * The context for a category, built on first use.
   *
   * <p>Guarded by a {@link ReentrantLock} rather than {@code synchronized}: bootstrapping seeds the
   * catalog, which is database I/O, and a virtual thread that blocks inside a monitor pins its
   * carrier. With {@code --concurrency} loops all arriving at once on a small machine, that would
   * starve the heartbeat thread of a carrier just as the leases start counting down.
   */
  private CrawlContext contextFor(String category) throws SQLException {
    contextsLock.lock();
    try {
      CrawlContext ctx = contexts.get(category);
      if (ctx == null) {
        CategoryConfig config = loader.load(categoriesDir, category);
        ctx = CrawlContext.bootstrap(config, products);
        contexts.put(category, ctx);
        log.info("worker {}: loaded category {} ({} seeds)", id, category, ctx.catalog().size());
      }
      return ctx;
    } finally {
      contextsLock.unlock();
    }
  }
}
