package com.achen.shelf.cli;

import com.achen.shelf.config.AppConfig;
import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.db.Database;
import com.achen.shelf.rollup.RollupRun;
import java.time.Clock;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf rollup --category keyboards} — retire unseen offers and recompute every rollup in
 * the category, by hand.
 *
 * <p>The same pass the coordinator runs when a cycle closes, over the whole category instead of one
 * run's touched set: after a backfill, after a rescore, or to rebuild {@code price_rollups} from
 * scratch — every row is a function of the observations, so this always converges. {@code --run}
 * scopes it to one run, exactly as the hook does.
 */
@CommandLine.Command(
    name = "rollup",
    mixinStandardHelpOptions = true,
    description = "Retire unseen offers and recompute price rollups (M4).")
public final class RollupCommand implements Callable<Integer> {

  @CommandLine.Option(
      names = {"-c", "--category"},
      required = true,
      description = "Category to roll up; must match a file in categories/.")
  private String category;

  @CommandLine.Option(
      names = "--run",
      description = "Recompute only what this crawl run touched (default: the whole category).")
  private Long runId;

  @CommandLine.Option(
      names = "--retire-after",
      defaultValue = "3",
      description =
          "Consecutive completed cycles an offer may go unseen before it stops counting as"
              + " current (default ${DEFAULT-VALUE}).")
  private int retireAfter;

  @Override
  public Integer call() throws Exception {
    AppConfig app = AppConfig.fromEnv();
    CategoryConfig config = new CategoryConfigLoader().load(app.categoriesDir(), category);
    try (Database db = Database.open(app, 2)) {
      RollupRun.Scope scope =
          runId == null ? new RollupRun.Scope.All() : new RollupRun.Scope.Run(runId);
      RollupRun.Summary summary =
          new RollupRun(db, new RollupRun.Settings(retireAfter), Clock.systemUTC())
              .run(config, scope);
      print(summary);
      return CommandLine.ExitCode.OK;
    }
  }

  static void print(RollupRun.Summary s) {
    System.out.printf(
        "rollups (%s, as of %s): %d offer(s) retired; %d offer and %d product rollup(s) recomputed"
            + " in %d ms%n",
        s.category(),
        s.asOf(),
        s.retired(),
        s.offersRecomputed(),
        s.productsRecomputed(),
        s.took().toMillis());
  }
}
