package com.achen.shelf.ingest;

/**
 * A request an ingester cannot continue without failed. Caught at the community boundary: the
 * community's line in the summary says FAILED and why, what was written stays written, and the next
 * community is read.
 */
final class IngestException extends Exception {
  private static final long serialVersionUID = 1L;

  IngestException(String message) {
    super(message);
  }
}
