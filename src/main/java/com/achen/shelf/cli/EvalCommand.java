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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/** {@code shelf eval} — the measured claims. {@code resolution} today; {@code backtest} in M5. */
@CommandLine.Command(
    name = "eval",
    mixinStandardHelpOptions = true,
    description = "Evaluate a subsystem against labeled data.",
    subcommands = {EvalCommand.Resolution.class})
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
}
