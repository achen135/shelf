package com.achen.shelf.crawl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stores fetched response bodies on local disk under {@code data/raw/<run>/<sha256>}.
 *
 * <p>Local disk, not an object store: the bodies are large, regenerable, and only ever read back by
 * hand when a parser is being debugged, so paying for (or depending on) a hosted bucket buys
 * nothing here. The path is recorded in {@code raw_fetches.body_ref}, which keeps the decision
 * reversible — moving to a bucket later changes what a body_ref means and nothing else.
 *
 * <p>Content-addressed by SHA-256 so that a retailer returning an unchanged page across a retry
 * costs one file rather than several.
 */
public final class RawStore {

  private final Path root;

  public RawStore(Path root) {
    this.root = root;
  }

  /**
   * Writes a body for a run and returns its reference, relative to the raw root.
   *
   * @param runId the crawl run the fetch belongs to
   * @param body the response body
   * @param extension file extension, without the dot
   */
  public String store(long runId, String body, String extension) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    String name = sha256(bytes) + "." + extension;
    Path dir = root.resolve(String.valueOf(runId));
    Path file = dir.resolve(name);
    try {
      Files.createDirectories(dir);
      if (!Files.exists(file)) {
        Files.write(file, bytes);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("could not store raw body at " + file, e);
    }
    return runId + "/" + name;
  }

  /** Absolute path of a stored body, for tests and debugging. */
  public Path resolve(String bodyRef) {
    return root.resolve(bodyRef);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
    }
  }
}
