package com.achen.shelf.crawl;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.SeedProduct;
import com.achen.shelf.db.Jsonb;
import com.achen.shelf.db.ProductDao;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The catalog bootstrapped from a category's seed products.
 *
 * <p>Every crawl starts by upserting the seeds into {@code products}, so the catalog exists before
 * the first listing is written and its identity — {@code (category, brand_norm, model_norm)}, via
 * {@link Normalizer} — is decided by configuration, not by whatever a retailer happened to say.
 *
 * <p>Until M3 this class also linked listings to seeds by exact model containment. That rule is now
 * one feature of the resolver's scorer ({@code resolve/Scorer}), which runs over pending offers
 * after a cycle closes and is measured against labels; the crawl itself no longer links anything.
 */
public final class SeedCatalog {

  private final List<Long> productIds;

  private SeedCatalog(List<Long> productIds) {
    this.productIds = List.copyOf(productIds);
  }

  /** Upserts every seed product into the catalog. */
  public static SeedCatalog bootstrap(CategoryConfig category, ProductDao products)
      throws SQLException {
    List<Long> ids = new ArrayList<>();
    for (SeedProduct seed : category.seedProducts()) {
      ids.add(
          products.upsert(
              category.name(),
              seed.brand(),
              seed.model(),
              Normalizer.normalize(seed.brand()),
              Normalizer.normalize(seed.model()),
              seed.canonicalName(),
              Jsonb.fromMap(seed.spec()),
              seed.aliases()));
    }
    return new SeedCatalog(ids);
  }

  /** How many products the catalog was seeded with. */
  public int size() {
    return productIds.size();
  }
}
