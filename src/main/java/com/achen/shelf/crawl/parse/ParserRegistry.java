package com.achen.shelf.crawl.parse;

import com.achen.shelf.config.CategoryConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the {@code parser:} id in a category config to an implementation.
 *
 * <p>The indirection is what keeps "adding a category is config plus parsers" true: a new category
 * registers its parsers and its spec extractor here and changes nothing else. An unknown id fails
 * at the start of a crawl, with the known ids in the message, rather than at the first fetch.
 */
public final class ParserRegistry {

  private final Map<String, Parser> parsers;

  private ParserRegistry(Map<String, Parser> parsers) {
    this.parsers = Map.copyOf(parsers);
  }

  /**
   * Builds the registry for a category.
   *
   * <p>The spec extractor is chosen by category name — the one place the pipeline knows keyboards
   * exist. M7 adds a branch here for monitors and, per docs/Spec.md §7, nothing else in the crawl.
   */
  public static ParserRegistry forCategory(CategoryConfig category) {
    SpecExtractor extractor =
        switch (category.name()) {
          case "keyboards" -> new KeyboardSpecExtractor();
          default -> SpecExtractor.NONE;
        };
    Map<String, Parser> parsers = new LinkedHashMap<>();
    parsers.put("shopify_products_json", new ShopifyProductsJsonParser(extractor));
    parsers.put("shopify_collection_html", new ShopifyCollectionHtmlParser(extractor));
    return new ParserRegistry(parsers);
  }

  /** Returns the parser for an id, or throws naming what is available. */
  public Parser get(String parserId) {
    Parser parser = parsers.get(parserId);
    if (parser == null) {
      throw new ParseException(
          "no parser registered as '" + parserId + "'; known parsers: " + parsers.keySet());
    }
    return parser;
  }

  /** True if an id resolves — used to check a config before a crawl starts fetching. */
  public boolean has(String parserId) {
    return parsers.containsKey(parserId);
  }
}
