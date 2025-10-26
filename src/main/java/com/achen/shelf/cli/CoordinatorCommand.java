package com.achen.shelf.cli;

import com.achen.shelf.config.AppConfig;
import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.crawl.cluster.Coordinator;
import com.achen.shelf.crawl.cluster.LeaderLock;
import com.achen.shelf.db.CrawlRunDao;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf coordinator --category keyboards} — the leader-elected scheduler.
 *
 * <p>Run as many as you like; exactly one holds the advisory lock and does the work, the rest wait
 * to take over. {@code --once} opens a cycle immediately, waits for it to finish, prints what it
 * did and exits — the shape the throughput benchmark and the recovery tests use.
 */
@CommandLine.Command(
    name = "coordinator",
    mixinStandardHelpOptions = true,
    description = "Enqueue crawl cycles and reap expired leases (M2).")
public final class CoordinatorCommand implements Callable<Integer> {

  @CommandLine.Option(
      names = {"-c", "--category"},
      required = true,
      arity = "1..*",
      description = "Categories to schedule; each must match a file in categories/.")
  private List<String> categories;

  @CommandLine.Option(
      names = "--id",
      description = "Coordinator id, visible in pg_stat_activity. Default: <hostname>-<pid>.")
  private String id;

  @CommandLine.Option(
      names = "--poll",
      defaultValue = "PT5S",
      description =
          "How often to try for leadership and, as leader, tick; also the failover bound"
              + " (default ${DEFAULT-VALUE}).")
  private Duration poll;

  @CommandLine.Option(
      names = "--cycle-interval",
      defaultValue = "PT6H",
      description =
          "Time between the end of one cycle and the start of the next (default ${DEFAULT-VALUE}).")
  private Duration cycleInterval;

  @CommandLine.Option(
      names = "--max-attempts",
      defaultValue = "3",
      description = "Claims a task gets before it is dead-lettered (default ${DEFAULT-VALUE}).")
  private int maxAttempts;

  @CommandLine.Option(
      names = "--once",
      description = "Open one cycle now, wait for it to finish, print a summary and exit.")
  private boolean once;

  @Override
  public Integer call() throws Exception {
    AppConfig app = AppConfig.fromEnv();
    CategoryConfigLoader loader = new CategoryConfigLoader();
    List<CategoryConfig> configs = new ArrayList<>();
    for (String name : categories) {
      configs.add(loader.load(app.categoriesDir(), name));
    }
    String coordinatorId = id == null ? defaultId() : id;
    Coordinator.Settings settings = new Coordinator.Settings(poll, cycleInterval, maxAttempts);

    LeaderLock lock =
        new LeaderLock(app.dbUrl(), app.dbUser(), app.dbPassword(), "coordinator-" + coordinatorId);
    try (Coordinator coordinator =
        new Coordinator(coordinatorId, lock, configs, settings, Clock.systemUTC())) {
      Runtime.getRuntime().addShutdownHook(new Thread(coordinator::stop, "coordinator-shutdown"));
      if (!once) {
        coordinator.run();
        return CommandLine.ExitCode.OK;
      }
      List<CrawlRunDao.Closed> closed = coordinator.runOnce();
      int errors = 0;
      System.out.printf(
          "%-6s %-12s %6s %7s %8s%n", "run", "category", "pages", "errors", "workers");
      for (CrawlRunDao.Closed run : closed) {
        System.out.printf(
            "%-6d %-12s %6d %7d %8d%n",
            run.runId(), run.category(), run.pages(), run.errors(), run.workerCount());
        errors += run.errors();
      }
      return errors == 0 ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
    }
  }

  static String defaultId() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      host = "coordinator";
    }
    return host + "-" + ProcessHandle.current().pid();
  }
}
