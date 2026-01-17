package com.achen.shelf.cli;

import com.achen.shelf.api.ApiServer;
import com.achen.shelf.config.AppConfig;
import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.db.Database;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine;

/**
 * {@code shelf api [--port 8080]} — serve the query API and the demo page.
 *
 * <p>Read-only: it never writes to the database, never crawls, and serves every category file in
 * the categories directory. The compose file runs it as the {@code api} service on :8080.
 */
@CommandLine.Command(
    name = "api",
    mixinStandardHelpOptions = true,
    description = "Serve the query API and the demo page (M6).")
public final class ApiCommand implements Callable<Integer> {

  @CommandLine.Option(
      names = "--host",
      defaultValue = "0.0.0.0",
      description = "Interface to bind (default ${DEFAULT-VALUE}).")
  private String host;

  @CommandLine.Option(
      names = "--port",
      defaultValue = "8080",
      description = "Port to listen on (default ${DEFAULT-VALUE}).")
  private int port;

  @CommandLine.Option(
      names = "--db-pool",
      defaultValue = "8",
      description =
          "Connections in the pool; also the number of queries in flight at once (default"
              + " ${DEFAULT-VALUE}).")
  private int dbPool;

  @Override
  public Integer call() throws Exception {
    AppConfig app = AppConfig.fromEnv();
    List<CategoryConfig> categories = new CategoryConfigLoader().loadAll(app.categoriesDir());
    CountDownLatch stopped = new CountDownLatch(1);
    try (Database db = Database.open(app, dbPool);
        ApiServer server = new ApiServer(db, categories, Clock.systemUTC()).start(host, port)) {
      Runtime.getRuntime().addShutdownHook(new Thread(stopped::countDown, "api-shutdown"));
      System.out.printf(
          "shelf api listening on http://%s:%d — categories %s%n",
          host, server.port(), categories.stream().map(CategoryConfig::name).toList());
      stopped.await();
      return CommandLine.ExitCode.OK;
    }
  }
}
