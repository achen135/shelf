package com.achen.shelf.cli;

import com.achen.shelf.backfill.SyntheticBackfill;
import com.achen.shelf.config.AppConfig;
import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.db.Database;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf backfill --category keyboards} — write a labeled synthetic year of prices behind
 * every observed offer (M4).
 *
 * <p>Synthetic, and marked so on every row ({@code price_observations.source}); see docs/Design
 * Decisions.md for why there is no real source to seed from. Safe to repeat: offers that already
 * have synthetic history are skipped. Follow it with {@code shelf rollup}.
 */
@CommandLine.Command(
    name = "backfill",
    mixinStandardHelpOptions = true,
    description = "Write a labeled synthetic price history behind every observed offer (M4).")
public final class BackfillCommand implements Callable<Integer> {

  @CommandLine.Option(
      names = {"-c", "--category"},
      required = true,
      description = "Category to backfill; must match a file in categories/.")
  private String category;

  @CommandLine.Option(
      names = "--days",
      defaultValue = "365",
      description =
          "Days of history to write before the earliest real observation (default ${DEFAULT-VALUE}).")
  private int days;

  @CommandLine.Option(
      names = "--seed",
      defaultValue = "20251129",
      description =
          "Generator seed; the same seed always writes the same rows (default ${DEFAULT-VALUE}).")
  private long seed;

  @Override
  public Integer call() throws Exception {
    AppConfig app = AppConfig.fromEnv();
    CategoryConfig config = new CategoryConfigLoader().load(app.categoriesDir(), category);
    try (Database db = Database.open(app, 2)) {
      SyntheticBackfill.Summary s = new SyntheticBackfill(db, seed).run(config, days);
      if (s.runId() == null) {
        System.out.printf("backfill (%s): nothing to write%n", s.category());
      } else {
        System.out.printf(
            "backfill (%s): %d synthetic observation(s) for %d offer(s), %s to %s, run %d, in %d ms%n"
                + "next: shelf rollup --category %s%n",
            s.category(),
            s.rows(),
            s.offers(),
            s.from(),
            s.toExclusive().minusDays(1),
            s.runId(),
            s.took().toMillis(),
            s.category());
      }
      return CommandLine.ExitCode.OK;
    }
  }
}
