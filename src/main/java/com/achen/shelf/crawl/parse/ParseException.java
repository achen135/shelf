package com.achen.shelf.crawl.parse;

/** Thrown when a response body is not the shape its parser expects at all. */
public final class ParseException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ParseException(String message, Throwable cause) {
    super(message, cause);
  }

  public ParseException(String message) {
    super(message);
  }
}
