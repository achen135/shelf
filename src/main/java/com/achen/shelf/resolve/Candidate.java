package com.achen.shelf.resolve;

import java.util.Map;

/**
 * A catalog product a listing can resolve to.
 *
 * @param productId the catalog row id
 * @param brandNorm normalized brand — the block it belongs to
 * @param modelNorm normalized model — what the scorer looks for in a title
 * @param canonicalName for humans: the review queue and the eval report
 * @param spec the product's current canonical spec (seed values plus what has been derived from
 *     linked offers); may be empty
 */
public record Candidate(
    long productId,
    String brandNorm,
    String modelNorm,
    String canonicalName,
    Map<String, Object> spec) {

  public Candidate {
    spec = spec == null ? Map.of() : Map.copyOf(spec);
  }
}
