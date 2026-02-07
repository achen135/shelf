package com.achen.shelf.cli;

import com.achen.shelf.config.AppConfig;
import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.db.Database;
import com.achen.shelf.mention.MentionRun;
import com.achen.shelf.resolve.Resolver;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf mentions --category keyboards} — one mention-resolution pass (M9): every raw mention
 * of the category read, the products it names decided, the sentence around each read for sentiment
 * and rank, the {@code mentions} rows rewritten.
 *
 * <p>Run it after {@code shelf ingest}, and again after any edit to the category's seeds, aliases
 * or sentiment words: a pass is a full re-decision, cheap, and a repeat changes nothing. A human's
 * rows are never touched.
 */
@CommandLine.Command(
    name = "mentions",
    mixinStandardHelpOptions = true,
    description = "Resolve raw_mentions to catalog products and read their sentiment (M9).")
public final class MentionsCommand implements Callable<Integer> {

  @CommandLine.Option(
      names = {"-c", "--category"},
      required = true,
      description = "Category to resolve; must match a file in categories/.")
  private String category;

  @CommandLine.Option(
      names = "--auto",
      defaultValue = "0.9",
      description =
          "Score at or above which a mention is linked without review (default ${DEFAULT-VALUE}).")
  private double auto;

  @CommandLine.Option(
      names = "--review",
      defaultValue = "0.4",
      description =
          "Score at or above which a mention is proposed for review (default ${DEFAULT-VALUE}).")
  private double review;

  @Override
  public Integer call() throws Exception {
    AppConfig app = AppConfig.fromEnv();
    CategoryConfig config = new CategoryConfigLoader().load(app.categoriesDir(), category);
    try (Database db = Database.open(app, 2)) {
      print(new MentionRun(db, new Resolver.Thresholds(auto, review)).run(config));
      return CommandLine.ExitCode.OK;
    }
  }

  static void print(MentionRun.Summary s) {
    System.out.printf(
        "mentions (%s): %d raw mentions against %d products — %d name a product; %d linked,"
            + " %d proposed for review, %d left as a human decided; sentiment %d positive /"
            + " %d negative / %d neutral; %d with an explicit rank; %d ms%n",
        s.category(),
        s.rawMentions(),
        s.catalogSize(),
        s.rawWithAMatch(),
        s.autoLinked(),
        s.proposed(),
        s.keptByHuman(),
        s.positive(),
        s.negative(),
        s.neutral(),
        s.ranked(),
        s.took().toMillis());
  }
}
