package com.achen.shelf.cli;

import picocli.CommandLine;

/**
 * The {@code shelf} entry point.
 *
 * <p>One binary, one subcommand per role: the crawl today, the distributed coordinator and worker
 * in M2, the query API in M6. They share a process so that a demo is {@code docker compose up} and
 * a different {@code command:} per service, not a different image.
 */
@CommandLine.Command(
    name = "shelf",
    mixinStandardHelpOptions = true,
    version = "shelf 0.1.0",
    description = "Price & deal intelligence for enthusiast product categories.",
    subcommands = {
      CrawlCommand.class,
      MigrateCommand.class,
      CoordinatorCommand.class,
      WorkerCommand.class,
      ApiCommand.class
    })
public final class Main implements Runnable {

  @picocli.CommandLine.Spec CommandLine.Model.CommandSpec spec;

  @Override
  public void run() {
    // No subcommand given: print help and exit non-zero, rather than doing something surprising.
    throw new CommandLine.ParameterException(spec.commandLine(), "a subcommand is required");
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new Main()).execute(args);
    System.exit(exitCode);
  }
}
