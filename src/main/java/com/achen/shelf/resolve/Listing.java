package com.achen.shelf.resolve;

import java.util.Map;

/**
 * An offer as the resolver sees it: the normalized facts the crawl recorded, nothing else.
 *
 * @param offerId the offer row id
 * @param brandNorm the normalized brand, the blocking key; may be empty
 * @param titleNorm the listing title, normalized with {@code crawl/Normalizer}
 * @param spec the per-listing spec the crawl validated and stored
 */
public record Listing(long offerId, String brandNorm, String titleNorm, Map<String, Object> spec) {

  public Listing {
    brandNorm = brandNorm == null ? "" : brandNorm;
    titleNorm = titleNorm == null ? "" : titleNorm;
    spec = spec == null ? Map.of() : Map.copyOf(spec);
  }
}
