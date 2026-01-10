package com.achen.shelf.cli;

import com.achen.shelf.config.AppConfig;
import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.db.Database;
import com.achen.shelf.resolve.Catalog;
import com.achen.shelf.resolve.Labels;
import com.achen.shelf.resolve.ResolutionEval;
import com.achen.shelf.resolve.ResolutionRun;
import com.achen.shelf.resolve.Resolver;
import com.achen.shelf.resolve.Scorer;
import com.achen.shelf.signal.Backtest;
import com.achen.shelf.signal.BacktestReport;
import com.achen.shelf.signal.DealRule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/** {@code shelf eval} — the measured claims: {@code resolution} (M3) and {@code backtest} (M5). */
@CommandLine.Command(
    name = "eval",
    mixinStandardHelpOptions = true,
    description = "Evaluate a subsystem against labeled data or held-out history.",
    subcommands = {EvalCommand.Resolution.class, EvalCommand.SignalBacktest.class})
public final class EvalCommand implements Runnable {

  @CommandLine.Spec CommandLine.Model.CommandSpec spec;

  @Override
  public void run() {
    throw new CommandLine.ParameterException(spec.commandLine(), "a subcommand is required");
  }

  /**
   * {@code shelf eval resolution --category keyboards --labels
   * data/labels/keyboards-resolution.tsv}
   *
   * <p>Loads the labels into {@code resolution_labels} (keyed by offer URL and seed brand + model),
   * runs the resolver over each labeled offer against the catalog as it stands, and reports
   * precision / recall at the operating point and across a threshold sweep. Labels whose offer is
   * not in the database — a listing that has since disappeared, or a database that has not been
   * crawled — are reported and skipped, never guessed at.
   */
  @CommandLine.Command(
      name = "resolution",
      mixinStandardHelpOptions = true,
      description = "Precision / recall of entity resolution against hand-labeled pairs (M3).")
  public static final class Resolution implements Callable<Integer> {

    @CommandLine.Option(
        names = {"-c", "--category"},
        required = true,
        description = "Category the labels belong to.")
    private String category;

    @CommandLine.Option(
        names = "--labels",
        required = true,
        description = "Tab-separated label file (offer_url, title, brand, model, match, note).")
    private Path labels;

    @CommandLine.Option(names = "--out", description = "Also write the report here.")
    private Path out;

    @CommandLine.Option(
        names = "--auto",
        defaultValue = "0.9",
        description = "Auto-link threshold to report at (default ${DEFAULT-VALUE}).")
    private double auto;

    @CommandLine.Option(
        names = "--review",
        defaultValue = "0.4",
        description = "Review threshold to report at (default ${DEFAULT-VALUE}).")
    private double review;

    @Override
    public Integer call() throws Exception {
      AppConfig app = AppConfig.fromEnv();
      CategoryConfig config = new CategoryConfigLoader().load(app.categoriesDir(), category);
      List<Labels.Label> labelRows = Labels.read(labels);

      try (Database db = Database.open(app, 2)) {
        Resolver.Thresholds thresholds = new Resolver.Thresholds(auto, review);
        Catalog catalog = new ResolutionRun(db, thresholds).loadCatalog(config);
        Resolver resolver =
            new Resolver(
                catalog,
                new Scorer(
                    config.resolution().identityFields(), config.resolution().nonProductPhrases()),
                thresholds);
        ResolutionEval.Loaded loaded = ResolutionEval.load(db, catalog, labelRows);
        ResolutionEval.Report report = ResolutionEval.evaluate(resolver, loaded.pairs());
        int withSpec = (int) catalog.all().stream().filter(c -> !c.spec().isEmpty()).count();
        String text =
            ResolutionEval.render(report, category, catalog.size(), withSpec, loaded.skipped());
        System.out.print(text);
        if (out != null) {
          Files.writeString(out, text, StandardCharsets.UTF_8);
          System.out.printf("written to %s%n", out);
        }
        return CommandLine.ExitCode.OK;
      }
    }
  }

  /**
   * {@code shelf eval backtest --category keyboards [--horizon 30] [--out …]}
   *
   * <p>Recomputes every product's rollup row as of the end of every day in the category's history —
   * the rollup statement itself, so nothing after the day leaks in — asks the rule what it would
   * have said, and judges each call by whether a lower price came within the horizon, beside
   * "always buy" and "buy below median". The report states how much of the history behind it is
   * synthetic; on a corpus with a backfilled year, that is most of it, and the report says so.
   */
  @CommandLine.Command(
      name = "backtest",
      mixinStandardHelpOptions = true,
      description =
          "Hit rate of the buy / wait signal over the trailing year vs naive baselines (M5).")
  public static final class SignalBacktest implements Callable<Integer> {

    @CommandLine.Option(
        names = {"-c", "--category"},
        required = true,
        description = "Category to backtest.")
    private String category;

    @CommandLine.Option(
        names = "--horizon",
        defaultValue = "30",
        description = "Days ahead a call is judged over (default ${DEFAULT-VALUE}).")
    private int horizon;

    @CommandLine.Option(
        names = "--warmup",
        defaultValue = "90",
        description = "Days of history before the first scored day (default ${DEFAULT-VALUE}).")
    private int warmup;

    @CommandLine.Option(
        names = "--tolerance",
        defaultValue = "0.02",
        description =
            "A drop is this share below today's price or more (default ${DEFAULT-VALUE}).")
    private double tolerance;

    @CommandLine.Option(
        names = "--parallelism",
        defaultValue = "4",
        description = "As-of recomputes in flight at once (default ${DEFAULT-VALUE}).")
    private int parallelism;

    @CommandLine.Option(
        names = "--buy",
        defaultValue = "0.20",
        description = "The rule's buy percentile to report at (default ${DEFAULT-VALUE}).")
    private double buy;

    @CommandLine.Option(
        names = "--wait",
        defaultValue = "0.50",
        description = "The rule's wait percentile to report at (default ${DEFAULT-VALUE}).")
    private double wait;

    @CommandLine.Option(names = "--out", description = "Also write the report here.")
    private Path out;

    @Override
    public Integer call() throws Exception {
      AppConfig app = AppConfig.fromEnv();
      CategoryConfig config = new CategoryConfigLoader().load(app.categoriesDir(), category);
      Backtest.Settings settings = new Backtest.Settings(horizon, warmup, tolerance, parallelism);
      DealRule rule =
          new DealRule(
              DealRule.Thresholds.defaults().withBuyPercentile(buy).withWaitPercentile(wait));
      try (Database db = Database.open(app, parallelism + 1)) {
        Backtest backtest = new Backtest(db, settings);
        Backtest.Grid grid = backtest.grid(config);
        String text = BacktestReport.render(backtest.evaluate(grid, rule), category);
        System.out.print(text);
        if (out != null) {
          Files.writeString(out, text, StandardCharsets.UTF_8);
          System.out.printf("written to %s%n", out);
        }
        return CommandLine.ExitCode.OK;
      }
    }
  }
}
