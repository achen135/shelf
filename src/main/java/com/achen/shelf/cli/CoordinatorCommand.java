package com.achen.shelf.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf coordinator} — not implemented yet.
 *
 * <p>M2 — the advisory-lock leader that enqueues cycles into crawl_tasks and reaps expired leases.
 * The subcommand exists now so the CLI surface in docs/Spec.md §3 is real and the compose file can
 * reference it, but it exits non-zero rather than pretending to work.
 */
@CommandLine.Command(
    name = "coordinator",
    mixinStandardHelpOptions = true,
    description = "Enqueue crawl cycles and reap expired leases (M2).")
public final class CoordinatorCommand implements Callable<Integer> {

  @Override
  public Integer call() {
    System.err.println("shelf coordinator: not implemented yet — see docs/Spec.md §7");
    return CommandLine.ExitCode.SOFTWARE;
  }
}
