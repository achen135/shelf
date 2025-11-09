package com.achen.shelf.cli;

import com.achen.shelf.config.AppConfig;
import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.db.Database;
import com.achen.shelf.resolve.ResolutionRun;
import com.achen.shelf.resolve.Resolver;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf resolve --category keyboards} — one entity-resolution pass, by hand.
 *
 * <p>The same pass the coordinator runs when a cycle closes. Useful after editing a category's
 * seeds, or to see the numbers without waiting for a crawl. Idempotent: it only ever decides
 * pending offers — after a scorer or config change, {@code --rescore} reopens the machine's earlier
 * links (and only those) so they are decided again.
 */
@CommandLine.Command(
    name = "resolve",
    mixinStandardHelpOptions = true,
    description = "Link pending offers to catalog products and fill the review queue (M3).")
public final class ResolveCommand implements Callable<Integer> {

  @CommandLine.Option(
      names = {"-c", "--category"},
      required = true,
      description = "Category to resolve; must match a file in categories/.")
  private String category;

  @CommandLine.Option(
      names = "--auto",
      defaultValue = "0.9",
      description =
          "Score at or above which an offer is linked without review (default ${DEFAULT-VALUE}).")
  private double auto;

  @CommandLine.Option(
      names = "--review",
      defaultValue = "0.4",
      description =
          "Score at or above which the best candidate is kept for review (default ${DEFAULT-VALUE}).")
  private double review;

  @CommandLine.Option(
      names = "--rescore",
      description =
          "First return every auto link to pending, so the pass decides them again under the"
              + " current scorer and config. Human decisions are never reopened.")
  private boolean rescore;

  @Override
  public Integer call() throws Exception {
    AppConfig app = AppConfig.fromEnv();
    CategoryConfig config = new CategoryConfigLoader().load(app.categoriesDir(), category);
    try (Database db = Database.open(app, 2)) {
      ResolutionRun.Summary summary =
          new ResolutionRun(db, new Resolver.Thresholds(auto, review)).run(config, rescore);
      print(summary);
      return CommandLine.ExitCode.OK;
    }
  }

  static void print(ResolutionRun.Summary s) {
    System.out.printf(
        "resolution (%s, %d catalog products): %d pending considered — %d auto-linked,"
            + " %d for review, %d unmatched; specs derived for %d product(s); review queue: %d%n",
        s.category(),
        s.catalogSize(),
        s.considered(),
        s.autoLinked(),
        s.queuedForReview(),
        s.unmatched(),
        s.productsWithSpecDerived(),
        s.reviewQueueSize());
  }
}
