package com.achen.shelf.cli;

import com.achen.shelf.config.AppConfig;
import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.consensus.ConsensusRun;
import com.achen.shelf.db.Database;
import java.time.Clock;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf consensus --category keyboards} — recompute every product's consensus from its
 * linked mentions in the trailing window, by hand (M10). The same pass {@code shelf mentions} ends
 * with; useful on its own when only the clock has moved.
 */
@CommandLine.Command(
    name = "consensus",
    mixinStandardHelpOptions = true,
    description = "Recompute the community consensus for every product (M10).")
public final class ConsensusCommand implements Callable<Integer> {

  @CommandLine.Option(
      names = {"-c", "--category"},
      required = true,
      description = "Category to score; must match a file in categories/.")
  private String category;

  @Override
  public Integer call() throws Exception {
    AppConfig app = AppConfig.fromEnv();
    CategoryConfig config = new CategoryConfigLoader().load(app.categoriesDir(), category);
    try (Database db = Database.open(app, 2)) {
      print(new ConsensusRun(db, Clock.systemUTC()).run(config));
      return CommandLine.ExitCode.OK;
    }
  }

  static void print(ConsensusRun.Summary s) {
    System.out.printf(
        "consensus (%s, as of %s, %d-day window): %d products, %d with a mention (%d mentions) —"
            + " %d liked / %d mixed / %d disliked; %d ms%n",
        s.category(),
        s.asOf(),
        ConsensusRun.WINDOW_DAYS,
        s.products(),
        s.withMentions(),
        s.mentions(),
        s.liked(),
        s.mixed(),
        s.disliked(),
        s.took().toMillis());
  }
}
