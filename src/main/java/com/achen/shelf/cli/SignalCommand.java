package com.achen.shelf.cli;

import com.achen.shelf.config.AppConfig;
import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.db.Database;
import com.achen.shelf.signal.DealRule;
import com.achen.shelf.signal.SignalRun;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf signal --category keyboards} — recompute every product's buy / wait / neutral call
 * from its rollup row, by hand.
 *
 * <p>The same pass the coordinator runs after the rollup pass when a cycle closes, over the whole
 * category instead of one run's touched products: after {@code shelf rollup}, or after a change to
 * the rule.
 */
@CommandLine.Command(
    name = "signal",
    mixinStandardHelpOptions = true,
    description = "Recompute the buy / wait / neutral signal for every product (M5).")
public final class SignalCommand implements Callable<Integer> {

  @CommandLine.Option(
      names = {"-c", "--category"},
      required = true,
      description = "Category to decide; must match a file in categories/.")
  private String category;

  @Override
  public Integer call() throws Exception {
    AppConfig app = AppConfig.fromEnv();
    CategoryConfig config = new CategoryConfigLoader().load(app.categoriesDir(), category);
    try (Database db = Database.open(app, 2)) {
      print(new SignalRun(db, DealRule.defaults()).run(config));
      return CommandLine.ExitCode.OK;
    }
  }

  static void print(SignalRun.Summary s) {
    System.out.printf(
        "signals (%s, as of %s): %d product(s) — %d buy, %d wait, %d neutral; %d without a rollup"
            + " row skipped; %d ms%n",
        s.category(),
        s.asOf(),
        s.decided(),
        s.buys(),
        s.waits(),
        s.neutrals(),
        s.withoutRollup(),
        s.took().toMillis());
  }
}
