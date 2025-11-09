package com.achen.shelf.cli;

import com.achen.shelf.config.AppConfig;
import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.ResolutionDao;
import com.achen.shelf.resolve.ResolutionRun;
import com.achen.shelf.resolve.Resolver;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf review} — the human half of entity resolution.
 *
 * <p>{@code list} shows the offers the resolver was not sure about, with its proposal and the
 * score. {@code accept} confirms a proposal (or names a different product); {@code reject} says the
 * listing is not a catalog product. Both are final as far as the machine is concerned: a reviewed
 * or rejected offer is never re-scored.
 */
@CommandLine.Command(
    name = "review",
    mixinStandardHelpOptions = true,
    description = "Work the entity-resolution review queue (M3).",
    subcommands = {
      ReviewCommand.ListQueue.class,
      ReviewCommand.Accept.class,
      ReviewCommand.Reject.class
    })
public final class ReviewCommand implements Runnable {

  @CommandLine.Spec CommandLine.Model.CommandSpec spec;

  @Override
  public void run() {
    throw new CommandLine.ParameterException(
        spec.commandLine(), "a subcommand is required: list, accept or reject");
  }

  /** {@code shelf review list --category keyboards [--limit N]}. */
  @CommandLine.Command(
      name = "list",
      mixinStandardHelpOptions = true,
      description = "Show pending offers with a proposed product, best score first.")
  public static final class ListQueue implements Callable<Integer> {

    @CommandLine.Option(
        names = {"-c", "--category"},
        required = true,
        description = "Category whose queue to show.")
    private String category;

    @CommandLine.Option(
        names = "--limit",
        defaultValue = "50",
        description = "At most this many rows (default ${DEFAULT-VALUE}).")
    private int limit;

    @Override
    public Integer call() throws Exception {
      AppConfig app = AppConfig.fromEnv();
      CategoryConfig config = new CategoryConfigLoader().load(app.categoriesDir(), category);
      try (Database db = Database.open(app, 2)) {
        List<ResolutionDao.ReviewItem> queue =
            new ResolutionDao(db).reviewQueue(ResolutionRun.retailerNames(config), limit);
        System.out.printf(
            "%-8s %-6s %-22s %-40s %s%n", "offer", "score", "retailer", "proposed", "title");
        for (ResolutionDao.ReviewItem item : queue) {
          System.out.printf(
              "%-8d %-6.2f %-22s %-40s %s%n",
              item.offerId(),
              item.score(),
              item.retailer(),
              trim(item.candidateName(), 40),
              trim(item.title(), 90));
        }
        System.out.printf("%d shown%n", queue.size());
        return CommandLine.ExitCode.OK;
      }
    }

    private static String trim(String s, int width) {
      return s == null ? "" : s.length() <= width ? s : s.substring(0, width - 1) + "…";
    }
  }

  /** {@code shelf review accept --category keyboards <offer> [--product <id>]}. */
  @CommandLine.Command(
      name = "accept",
      mixinStandardHelpOptions = true,
      description = "Confirm the proposed product for an offer, or name a different one.")
  public static final class Accept implements Callable<Integer> {

    @CommandLine.Option(
        names = {"-c", "--category"},
        required = true,
        description = "The offer's category (its product's spec is re-derived).")
    private String category;

    @CommandLine.Parameters(index = "0", description = "Offer id, from `review list`.")
    private long offerId;

    @CommandLine.Option(
        names = "--product",
        description = "Link to this product id instead of the proposed one.")
    private Long productId;

    @Override
    public Integer call() throws Exception {
      AppConfig app = AppConfig.fromEnv();
      CategoryConfig config = new CategoryConfigLoader().load(app.categoriesDir(), category);
      try (Database db = Database.open(app, 2)) {
        ResolutionDao dao = new ResolutionDao(db);
        ResolutionRun run = new ResolutionRun(db, Resolver.Thresholds.defaults());
        Optional<Long> linked =
            db.transaction(
                c -> {
                  if (!dao.accept(c, offerId, productId)) {
                    return Optional.<Long>empty();
                  }
                  Optional<Long> product = dao.linkedProduct(c, offerId);
                  run.deriveSpecs(c, config, product.map(List::of).orElse(List.of()));
                  return product;
                });
        if (linked.isEmpty()) {
          System.err.printf(
              "offer %d is not pending with a proposal (give --product to link it anyway)%n",
              offerId);
          return CommandLine.ExitCode.USAGE;
        }
        System.out.printf("offer %d linked to product %d (reviewed)%n", offerId, linked.get());
        return CommandLine.ExitCode.OK;
      }
    }
  }

  /** {@code shelf review reject <offer>}. */
  @CommandLine.Command(
      name = "reject",
      mixinStandardHelpOptions = true,
      description = "Mark an offer as not a catalog product; it is never re-scored.")
  public static final class Reject implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Offer id, from `review list`.")
    private long offerId;

    @Override
    public Integer call() throws Exception {
      AppConfig app = AppConfig.fromEnv();
      try (Database db = Database.open(app, 2)) {
        boolean done = db.transaction(c -> new ResolutionDao(db).reject(c, offerId));
        if (!done) {
          System.err.printf("offer %d is not pending%n", offerId);
          return CommandLine.ExitCode.USAGE;
        }
        System.out.printf("offer %d rejected%n", offerId);
        return CommandLine.ExitCode.OK;
      }
    }
  }
}
