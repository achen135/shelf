package com.achen.shelf.resolve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Blocks a listing, scores every candidate in its block, and turns the best score into a decision.
 *
 * <p>Two thresholds. At or above {@code auto} the listing is linked with no human involved. Between
 * {@code review} and {@code auto} the best candidate is recorded as a proposal and the listing
 * waits in the review queue. Below {@code review} the listing is simply unresolved — most listings
 * in a category are products the catalog does not carry, and those should not clutter a queue.
 */
public final class Resolver {

  /** The two operating points; see {@link #defaults()} for where they sit and why. */
  public record Thresholds(double auto, double review) {
    public Thresholds {
      if (!(review >= 0 && review <= auto && auto <= 1)) {
        throw new IllegalArgumentException(
            "thresholds must satisfy 0 <= review <= auto <= 1, got review="
                + review
                + " auto="
                + auto);
      }
    }

    /**
     * {@code auto} 0.9: only a model that appears whole, with nothing counted against it, links on
     * its own. {@code review} 0.4: a model whose tokens are all present but scattered, or whole
     * with one qualifier or one spec conflict against it, or missing one word of a multi-word
     * model, is worth a human's glance; a missing numbered token is not. Chosen against the labeled
     * set — see docs/benchmarks/m3-entity-resolution.md for the sweep.
     */
    public static Thresholds defaults() {
      return new Thresholds(0.9, 0.4);
    }
  }

  /** Where a listing landed. */
  public enum Outcome {
    /** Linked. */
    AUTO,
    /** Best candidate recorded for a human. */
    REVIEW,
    /** No candidate worth recording. */
    NONE
  }

  /** A candidate with its score. */
  public record Scored(Candidate candidate, Scorer.Score score) {}

  /** The resolver's verdict on one listing. */
  public record Decision(Listing listing, Optional<Scored> best, Outcome outcome) {}

  private final Catalog catalog;
  private final Scorer scorer;
  private final Thresholds thresholds;

  public Resolver(Catalog catalog, Scorer scorer, Thresholds thresholds) {
    this.catalog = catalog;
    this.scorer = scorer;
    this.thresholds = thresholds;
  }

  public Thresholds thresholds() {
    return thresholds;
  }

  public Catalog catalog() {
    return catalog;
  }

  /** Every candidate in the listing's block, scored, best first. */
  public List<Scored> rank(Listing listing) {
    List<Scored> scored = new ArrayList<>();
    for (Candidate c : catalog.block(listing)) {
      scored.add(new Scored(c, scorer.score(listing, c, catalog.siblingTokens(c))));
    }
    // Ties are broken toward the longer model: the more specific product is the one a listing
    // that mentions both was about ("q6 he" over "q6").
    scored.sort(
        Comparator.comparingDouble((Scored s) -> s.score().value())
            .reversed()
            .thenComparing(s -> -s.candidate().modelNorm().length()));
    return scored;
  }

  /** The decision for one listing at this resolver's thresholds. */
  public Decision decide(Listing listing) {
    List<Scored> ranked = rank(listing);
    if (ranked.isEmpty()) {
      return new Decision(listing, Optional.empty(), Outcome.NONE);
    }
    Scored best = ranked.get(0);
    return new Decision(listing, Optional.of(best), outcomeFor(best.score().value()));
  }

  /** The outcome a score maps to at these thresholds. */
  public Outcome outcomeFor(double score) {
    if (score >= thresholds.auto()) {
      return Outcome.AUTO;
    }
    if (score >= thresholds.review()) {
      return Outcome.REVIEW;
    }
    return Outcome.NONE;
  }
}
