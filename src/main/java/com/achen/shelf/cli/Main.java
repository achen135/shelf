package com.achen.shelf.cli;

/**
 * Entry point. Subcommands: {@code crawl}, {@code coordinator}, {@code worker}, {@code api}.
 *
 * <p>TODO(M0): wire picocli and a {@code crawl --category <name> --once} subcommand (M1).
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    System.err.println(
        "shelf: not implemented yet — see docs/Spec.md §7 (M0/M1). args=" + String.join(" ", args));
    System.exit(2);
  }
}
