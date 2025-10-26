package com.achen.shelf.testing;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A real {@code shelf} subprocess — a separate JVM running {@code com.achen.shelf.cli.Main} — that
 * a test can kill.
 *
 * <p>The recovery tests exist to show what happens when a node dies, and a node dying is a process
 * being killed, not a thread being interrupted: nothing gets to run a finally block, flush a buffer
 * or roll back a transaction. {@link #kill()} is {@code destroyForcibly()}, which on the platforms
 * this runs on is SIGKILL — the same thing {@code docker kill} sends. The subprocess inherits the
 * test JVM's classpath, so it runs the exact classes under test.
 */
public final class ShelfProcess implements AutoCloseable {

  private final String name;
  private final Process process;
  private final Path logFile;

  private ShelfProcess(String name, Process process, Path logFile) {
    this.name = name;
    this.process = process;
    this.logFile = logFile;
  }

  /**
   * Launches {@code shelf <args>} with the given environment on top of the test JVM's own.
   *
   * @param name a label for the log file
   * @param logDir where stdout+stderr go
   * @param env environment variables (the {@code SHELF_*} settings)
   * @param args CLI arguments after {@code shelf}
   */
  public static ShelfProcess start(
      String name, Path logDir, Map<String, String> env, String... args) {
    String java =
        ProcessHandle.current()
            .info()
            .command()
            .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    List<String> command = new ArrayList<>();
    command.add(java);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add("com.achen.shelf.cli.Main");
    command.addAll(List.of(args));

    try {
      Files.createDirectories(logDir);
      Path log = logDir.resolve(name + ".log");
      ProcessBuilder pb =
          new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
      pb.environment().putAll(env);
      return new ShelfProcess(name, pb.start(), log);
    } catch (IOException e) {
      throw new UncheckedIOException("could not start shelf " + String.join(" ", args), e);
    }
  }

  public String name() {
    return name;
  }

  public long pid() {
    return process.pid();
  }

  public boolean isAlive() {
    return process.isAlive();
  }

  /** SIGKILL, then wait until the OS confirms the process is gone. */
  public void kill() {
    process.destroyForcibly();
    try {
      process.waitFor(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Waits for a normal exit and returns the exit code. */
  public int waitFor(Duration timeout) throws InterruptedException {
    if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      throw new AssertionError(name + " did not exit within " + timeout + ":\n" + log());
    }
    return process.exitValue();
  }

  /** Everything the process has written so far. */
  public String log() {
    try {
      return Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      return "(could not read " + logFile + ": " + e.getMessage() + ")";
    }
  }

  public File logFile() {
    return logFile.toFile();
  }

  @Override
  public void close() {
    if (process.isAlive()) {
      kill();
    }
  }
}
