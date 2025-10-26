package com.achen.shelf.crawl;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.Retailer;
import com.achen.shelf.config.SpecValidator;
import com.achen.shelf.crawl.parse.ParseException;
import com.achen.shelf.crawl.parse.ParserRegistry;
import com.achen.shelf.db.ProductDao;
import java.sql.SQLException;

/**
 * Everything a page crawl needs that is decided per category: the config, its parsers, its spec
 * validator, and the seeded catalog listings are matched against.
 *
 * <p>Built once per run by {@link CrawlRunner}, and once per category per process by the M2 worker
 * — a worker serves whatever categories the queue hands it, and this is the unit it caches.
 *
 * @param category the category config
 * @param parsers the parser registry for it
 * @param specValidator validates extracted specs against its schema
 * @param catalog its seed products, upserted and indexed for matching
 */
public record CrawlContext(
    CategoryConfig category,
    ParserRegistry parsers,
    SpecValidator specValidator,
    SeedCatalog catalog) {

  /**
   * Loads a category's parsers, checks every enabled retailer names one that exists, and seeds the
   * catalog.
   */
  public static CrawlContext bootstrap(CategoryConfig category, ProductDao products)
      throws SQLException {
    ParserRegistry parsers = ParserRegistry.forCategory(category);
    for (Retailer retailer : category.enabledRetailers()) {
      if (!parsers.has(retailer.parser())) {
        throw new ParseException(
            "retailer '"
                + retailer.name()
                + "' names parser '"
                + retailer.parser()
                + "', which is not registered");
      }
    }
    return new CrawlContext(
        category,
        parsers,
        new SpecValidator(category.specSchema()),
        SeedCatalog.bootstrap(category, products));
  }

  /**
   * The retailer config by name, or an error naming it — a task for an unknown retailer is a bug.
   */
  public Retailer retailer(String name) {
    return category
        .retailer(name)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "category '" + category.name() + "' has no retailer '" + name + "'"));
  }
}
