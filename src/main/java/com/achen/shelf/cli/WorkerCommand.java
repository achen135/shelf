package com.achen.shelf.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf worker} — not implemented yet.
 *
 * <p>M2 — claims tasks with FOR UPDATE SKIP LOCKED, holds a lease, heartbeats, and writes results.
 * The subcommand exists now so the CLI surface in docs/Spec.md §3 is real and the compose file can
 * reference it, but it exits non-zero rather than pretending to work.
 */
@CommandLine.Command(
    name = "worker",
    mixinStandardHelpOptions = true,
    description = "Claim and execute crawl tasks (M2).")
public final class WorkerCommand implements Callable<Integer> {

  @Override
  public Integer call() {
    System.err.println("shelf worker: not implemented yet — see docs/Spec.md §7");
    return CommandLine.ExitCode.SOFTWARE;
  }
}
