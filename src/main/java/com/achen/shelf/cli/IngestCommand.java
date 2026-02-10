package com.achen.shelf.cli;

import com.achen.shelf.config.AppConfig;
import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.consensus.ConsensusRun;
import com.achen.shelf.crawl.Fetcher;
import com.achen.shelf.db.Database;
import com.achen.shelf.ingest.IngestRunner;
import com.achen.shelf.ingest.IngestSummary;
import com.achen.shelf.mention.MentionRun;
import com.achen.shelf.resolve.Resolver;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf ingest --category keyboards}: read the category's enabled communities into {@code
 * raw_mentions} (M8) and print what each contributed.
 *
 * <p>One shot; run it again to refresh (see {@link IngestRunner} for why there is no cycle). A
 * community whose credentials are not in the environment is skipped and says so. Then the chain: a
 * mention pass over what is now staged (M9) and the consensus pass (M10) — ingest → mentions →
 * consensus, the way {@code shelf crawl --once} runs crawl → resolution → rollups → signals.
 */
@CommandLine.Command(
    name = "ingest",
    mixinStandardHelpOptions = true,
    description = "Read a category's communities (Reddit, YouTube) into raw_mentions (M8).")
public final class IngestCommand implements Callable<Integer> {

  @CommandLine.Option(
      names = {"-c", "--category"},
      required = true,
      description = "Category to ingest; must match a file in categories/.")
  private String category;

  @Override
  public Integer call() throws Exception {
    AppConfig app = AppConfig.fromEnv();
    CategoryConfig config = new CategoryConfigLoader().load(app.categoriesDir(), category);
    if (config.enabledCommunities().isEmpty()) {
      System.err.printf(
          "shelf ingest: %s has no enabled communities (add a `communities:` block)%n", category);
      return CommandLine.ExitCode.USAGE;
    }
    try (Database db = Database.open(app, 2)) {
      IngestSummary summary =
          new IngestRunner(db, Fetcher.withDefaults(app.userAgent())).run(config);
      print(summary);
      MentionsCommand.print(new MentionRun(db, Resolver.Thresholds.defaults()).run(config));
      ConsensusCommand.print(new ConsensusRun(db, Clock.systemUTC()).run(config));
      return summary.failures() == 0 ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
    }
  }

  static void print(IngestSummary summary) {
    System.out.printf("ingest (%s): %d ms%n", summary.category(), summary.took().toMillis());
    System.out.printf(
        "%-24s %-8s %-15s %6s %9s %9s %7s %9s%n",
        "community", "source", "status", "items", "comments", "requests", "new", "refreshed");
    for (IngestSummary.CommunitySummary c : summary.communities()) {
      System.out.printf(
          "%-24s %-8s %-15s %6d %9d %9d %7d %9d%s%n",
          c.community(),
          c.source().name().toLowerCase(Locale.ROOT),
          c.status().name().toLowerCase(Locale.ROOT),
          c.items(),
          c.comments(),
          c.requests(),
          c.inserted(),
          c.refreshed(),
          c.failure() == null ? "" : "   " + c.failure());
    }
    System.out.printf(
        "%-24s %-8s %-15s %6d %9d %9s %7d %9d%n",
        "TOTAL",
        "",
        "",
        summary.totalItems(),
        summary.totalComments(),
        "",
        summary.totalInserted(),
        summary.totalRefreshed());
    System.out.printf(
        "raw_mentions: %d rows in %s; %d stale youtube rows pruned (30-day retention)%n",
        summary.rowsInCategory(), summary.category(), summary.pruned());
  }
}
