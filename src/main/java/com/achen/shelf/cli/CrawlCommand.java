package com.achen.shelf.cli;

import com.achen.shelf.config.AppConfig;
import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.crawl.CrawlRunner;
import com.achen.shelf.crawl.CrawlSummary;
import com.achen.shelf.crawl.Fetcher;
import com.achen.shelf.crawl.RawStore;
import com.achen.shelf.db.Database;
import com.achen.shelf.resolve.ResolutionRun;
import com.achen.shelf.resolve.Resolver;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf crawl --category keyboards --once}: one single-process cycle, then a resolution pass
 * over what it wrote (M3).
 */
@CommandLine.Command(
    name = "crawl",
    mixinStandardHelpOptions = true,
    description = "Crawl a category's configured retailers and record prices.")
public final class CrawlCommand implements Callable<Integer> {

  @CommandLine.Option(
      names = {"-c", "--category"},
      required = true,
      description = "Category to crawl; must match a file in categories/.")
  private String category;

  @CommandLine.Option(
      names = "--once",
      description = "Run a single cycle and exit. Required until M2 adds scheduled cycles.")
  private boolean once;

  @Override
  public Integer call() throws Exception {
    if (!once) {
      System.err.println("shelf crawl: --once is required (repeating cycles arrive with M2)");
      return CommandLine.ExitCode.USAGE;
    }

    AppConfig app = AppConfig.fromEnv();
    CategoryConfig config = new CategoryConfigLoader().load(app.categoriesDir(), category);

    try (Database db = Database.open(app, 4)) {
      CrawlRunner runner =
          new CrawlRunner(db, Fetcher.withDefaults(app.userAgent()), new RawStore(app.rawDir()));
      CrawlSummary summary = runner.runOnce(config);
      print(summary);
      ResolutionRun.Summary resolved =
          new ResolutionRun(db, Resolver.Thresholds.defaults()).run(config);
      ResolveCommand.print(resolved);
      return summary.totalErrors() == 0 ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
    }
  }

  private static void print(CrawlSummary summary) {
    System.out.printf(
        "crawl run %d (%s): %d seeded products%n",
        summary.runId(), summary.category(), summary.seededProducts());
    System.out.printf(
        "%-22s %-5s %6s %8s %8s %7s%n", "retailer", "mode", "pages", "offers", "prices", "errors");
    for (CrawlSummary.RetailerSummary r : summary.retailers()) {
      System.out.printf(
          "%-22s %-5s %6d %8d %8d %7d%n",
          r.retailer(),
          r.mode(),
          r.pages(),
          r.offersWritten(),
          r.observationsWritten(),
          r.errors());
    }
    System.out.printf(
        "%-22s %-5s %6d %8d %8d %7d%n",
        "TOTAL",
        "",
        summary.totalPages(),
        summary.totalOffersWritten(),
        summary.totalObservations(),
        summary.totalErrors());
  }
}
