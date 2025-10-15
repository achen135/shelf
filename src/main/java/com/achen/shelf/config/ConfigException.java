package com.achen.shelf.config;

/**
 * Thrown when a category config is missing, unreadable, or violates the config format.
 *
 * <p>The message is meant to be the whole error report: it names the file and the offending path
 * inside it, because a bad config should fail the process immediately with something a human can
 * act on rather than surfacing later as a null field mid-crawl.
 */
public final class ConfigException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ConfigException(String message) {
    super(message);
  }

  public ConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}
