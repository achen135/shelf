package com.achen.shelf.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf api} — not implemented yet.
 *
 * <p>M6 — the Javalin query API behind the demo page. The subcommand exists now so the CLI surface
 * in docs/Spec.md §3 is real and the compose file can reference it, but it exits non-zero rather
 * than pretending to work.
 */
@CommandLine.Command(
    name = "api",
    mixinStandardHelpOptions = true,
    description = "Serve the query API (M6).")
public final class ApiCommand implements Callable<Integer> {

  @Override
  public Integer call() {
    System.err.println("shelf api: not implemented yet — see docs/Spec.md §7");
    return CommandLine.ExitCode.SOFTWARE;
  }
}
