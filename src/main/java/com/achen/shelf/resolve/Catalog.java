package com.achen.shelf.resolve;

import com.achen.shelf.crawl.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The catalog for one category, indexed for blocking.
 *
 * <p>Blocking is by normalized brand — the same key {@code products} is unique on, so there is one
 * normalization path from a seed to a block, not two. A listing whose brand names no block (a
 * retailer that files a product under a store brand, or no brand at all) falls back to whichever
 * catalog brands appear in its title as whole tokens; that recovers a "Third Party" listing whose
 * title still starts with the manufacturer, and nothing else.
 *
 * <p>The index also knows, for each product, which model tokens its <em>siblings</em> in the block
 * carry that it does not — "he" for a Keychron Q2 when a Q6 HE is in the catalog, "max" when a Q15
 * Max is. The scorer uses those as the qualifiers that mark a listing as a related-but-different
 * product.
 */
public final class Catalog {

  private final Map<String, List<Candidate>> byBrand;
  private final Map<Long, Set<String>> siblingTokens;
  private final int size;

  private Catalog(Map<String, List<Candidate>> byBrand, Map<Long, Set<String>> siblingTokens) {
    this.byBrand = byBrand;
    this.siblingTokens = siblingTokens;
    this.size = byBrand.values().stream().mapToInt(List::size).sum();
  }

  /** Indexes the given products. */
  public static Catalog of(Collection<Candidate> products) {
    Map<String, List<Candidate>> byBrand = new HashMap<>();
    for (Candidate c : products) {
      byBrand.computeIfAbsent(c.brandNorm(), k -> new ArrayList<>()).add(c);
    }
    Map<Long, Set<String>> siblingTokens = new HashMap<>();
    for (List<Candidate> block : byBrand.values()) {
      Set<String> blockTokens = new HashSet<>();
      for (Candidate c : block) {
        blockTokens.addAll(Scorer.tokens(c.modelNorm()));
      }
      for (Candidate c : block) {
        Set<String> others = new LinkedHashSet<>(blockTokens);
        others.removeAll(Scorer.tokens(c.modelNorm()));
        siblingTokens.put(c.productId(), Set.copyOf(others));
      }
    }
    return new Catalog(Map.copyOf(byBrand), Map.copyOf(siblingTokens));
  }

  /** The candidates a listing is scored against: its brand's block, or the title fallback. */
  public List<Candidate> block(Listing listing) {
    List<Candidate> block = byBrand.get(listing.brandNorm());
    if (block != null) {
      return block;
    }
    List<Candidate> fallback = new ArrayList<>();
    for (Map.Entry<String, List<Candidate>> e : byBrand.entrySet()) {
      if (Normalizer.containsModel(listing.titleNorm(), e.getKey())) {
        fallback.addAll(e.getValue());
      }
    }
    return fallback;
  }

  /** Model tokens carried by other products in this product's block and not by it. */
  public Set<String> siblingTokens(Candidate candidate) {
    return siblingTokens.getOrDefault(candidate.productId(), Set.of());
  }

  /** Every product, in no particular order. */
  public List<Candidate> all() {
    List<Candidate> all = new ArrayList<>(size);
    byBrand.values().forEach(all::addAll);
    return all;
  }

  public int size() {
    return size;
  }
}
