package com.achen.shelf.cli;

import com.achen.shelf.config.AppConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.crawl.Fetcher;
import com.achen.shelf.crawl.PageCrawler;
import com.achen.shelf.crawl.RawStore;
import com.achen.shelf.crawl.cluster.Worker;
import com.achen.shelf.db.Database;
import java.time.Duration;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf worker} — claims and executes crawl tasks until stopped.
 *
 * <p>Any number of these may run against one database; {@code docker compose up --scale worker=N}
 * is the intended way to get N. Each claims with {@code FOR UPDATE SKIP LOCKED}, holds a lease it
 * heartbeats, and writes a page's results and its task's completion in one transaction.
 */
@CommandLine.Command(
    name = "worker",
    mixinStandardHelpOptions = true,
    description = "Claim and execute crawl tasks (M2).")
public final class WorkerCommand implements Callable<Integer> {

  @CommandLine.Option(
      names = "--id",
      description = "Worker id recorded on leased tasks. Default: <hostname>-<pid>.")
  private String id;

  @CommandLine.Option(
      names = "--concurrency",
      defaultValue = "1",
      description =
          "Tasks this process runs at once, each on a virtual thread (default ${DEFAULT-VALUE}).")
  private int concurrency;

  @CommandLine.Option(
      names = "--lease",
      defaultValue = "PT15S",
      description =
          "How long a claim lasts without a heartbeat, ISO-8601 (default ${DEFAULT-VALUE}).")
  private Duration lease;

  @CommandLine.Option(
      names = "--heartbeat",
      defaultValue = "PT5S",
      description = "How often held leases are extended (default ${DEFAULT-VALUE}).")
  private Duration heartbeat;

  @CommandLine.Option(
      names = "--retry-backoff",
      defaultValue = "PT15S",
      description = "Base of the exponential backoff between attempts (default ${DEFAULT-VALUE}).")
  private Duration retryBackoff;

  @Override
  public Integer call() throws Exception {
    AppConfig app = AppConfig.fromEnv();
    Worker.Settings settings =
        new Worker.Settings(lease, heartbeat, Duration.ofSeconds(1), retryBackoff, concurrency);
    String workerId = id == null ? Worker.defaultId() : id;

    try (Database db = Database.open(app, Math.max(4, concurrency + 2))) {
      PageCrawler pages =
          new PageCrawler(db, Fetcher.withDefaults(app.userAgent()), new RawStore(app.rawDir()));
      try (Worker worker =
          new Worker(
              workerId, db, pages, new CategoryConfigLoader(), app.categoriesDir(), settings)) {
        // docker compose stop sends SIGTERM: hand any task in flight back to the queue.
        Runtime.getRuntime().addShutdownHook(new Thread(worker::stop, "worker-shutdown"));
        worker.start();
        worker.awaitStop();
      }
    }
    return CommandLine.ExitCode.OK;
  }
}
