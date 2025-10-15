package com.achen.shelf.crawl;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.SeedProduct;
import com.achen.shelf.db.ProductDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The catalog bootstrapped from a category's seed products, and the M1 rule for linking a listing
 * to one.
 *
 * <p>The rule: a listing matches a seed when they share a normalized brand and the seed's
 * normalized model appears in the normalized listing title on whole-token boundaries. Where several
 * seeds match, the longest model wins — "Keychron Q6 HE" beats "Keychron Q6" on the title "Keychron
 * Q6 HE QMK Wireless Custom Keyboard", and "q6" never matches "q65" because the token boundaries
 * forbid it.
 *
 * <p>Everything else is left unlinked, with {@code offers.product_id} null and {@code
 * resolution_status = 'pending'}. That is the deliberate M1 position: this is exact matching after
 * normalization, not entity resolution. M3 replaces it with blocking, scoring, a threshold and a
 * review queue, and reports precision/recall against labels — numbers this rule could not honestly
 * produce, because a rule that only ever matches what it is certain of has nothing interesting to
 * say about precision.
 */
public final class SeedCatalog {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** One seeded product: its normalized model and the catalog row it became. */
  private record Seed(String modelNorm, long productId) {}

  private final Map<String, List<Seed>> byBrand;

  private SeedCatalog(Map<String, List<Seed>> byBrand) {
    this.byBrand = byBrand;
  }

  /** Upserts every seed product into the catalog and indexes them for matching. */
  public static SeedCatalog bootstrap(CategoryConfig category, ProductDao products)
      throws SQLException {
    Map<String, List<Seed>> byBrand = new HashMap<>();
    for (SeedProduct seed : category.seedProducts()) {
      String brandNorm = Normalizer.normalize(seed.brand());
      String modelNorm = Normalizer.normalize(seed.model());
      long id =
          products.upsert(
              category.name(),
              seed.brand(),
              seed.model(),
              brandNorm,
              modelNorm,
              seed.canonicalName(),
              toJson(seed.spec()));
      byBrand.computeIfAbsent(brandNorm, k -> new ArrayList<>()).add(new Seed(modelNorm, id));
    }
    // Longest model first, so the first containment hit is also the most specific one.
    byBrand
        .values()
        .forEach(
            seeds ->
                seeds.sort(Comparator.comparingInt((Seed s) -> s.modelNorm().length()).reversed()));
    return new SeedCatalog(byBrand);
  }

  /** Returns the product a listing belongs to, if the M1 rule can say so with certainty. */
  public Optional<Long> match(String brand, String title) {
    List<Seed> candidates = byBrand.get(Normalizer.normalize(brand));
    if (candidates == null) {
      return Optional.empty();
    }
    String titleNorm = Normalizer.normalize(title);
    for (Seed seed : candidates) {
      if (Normalizer.containsModel(titleNorm, seed.modelNorm())) {
        return Optional.of(seed.productId());
      }
    }
    return Optional.empty();
  }

  /** How many products the catalog was seeded with. */
  public int size() {
    return byBrand.values().stream().mapToInt(List::size).sum();
  }

  private static String toJson(Map<String, Object> spec) {
    try {
      return JSON.writeValueAsString(spec);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("a validated spec map should always serialize", e);
    }
  }
}
