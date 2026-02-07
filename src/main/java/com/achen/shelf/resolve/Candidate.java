package com.achen.shelf.resolve;

import com.achen.shelf.crawl.Normalizer;
import java.util.List;
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
 * @param aliasesNorm the seed's declared aliases, normalized (M9) — what mention resolution reads
 *     beside the model; listing resolution ignores them
 */
public record Candidate(
    long productId,
    String brandNorm,
    String modelNorm,
    String canonicalName,
    Map<String, Object> spec,
    List<String> aliasesNorm) {

  public Candidate {
    spec = spec == null ? Map.of() : Map.copyOf(spec);
    aliasesNorm =
        aliasesNorm == null
            ? List.of()
            : aliasesNorm.stream().map(Normalizer::normalize).filter(a -> !a.isEmpty()).toList();
  }

  /** A candidate with no aliases — the v1 shape. */
  public Candidate(
      long productId,
      String brandNorm,
      String modelNorm,
      String canonicalName,
      Map<String, Object> spec) {
    this(productId, brandNorm, modelNorm, canonicalName, spec, List.of());
  }

  /** The model and every alias: each phrase the product may be named by in free text. */
  public List<String> namesNorm() {
    List<String> out = new java.util.ArrayList<>(aliasesNorm.size() + 1);
    out.add(modelNorm);
    out.addAll(aliasesNorm);
    return List.copyOf(out);
  }
}
