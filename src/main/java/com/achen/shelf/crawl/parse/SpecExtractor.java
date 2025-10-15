package com.achen.shelf.crawl.parse;

import java.util.List;
import java.util.Map;

/**
 * Pulls category spec values out of the free text a retailer publishes.
 *
 * <p>This is the one genuinely category-shaped piece of the crawl: what counts as a "layout" or a
 * "switch type" is a fact about keyboards, not about crawling. Keeping it behind an interface is
 * what lets M7 add monitors by writing a new implementation and a new config file, with nothing in
 * the fetch/parse/store path changing.
 */
@FunctionalInterface
public interface SpecExtractor {

  /** An extractor that claims nothing; the default for a category that has not defined one. */
  SpecExtractor NONE = (title, tags, description) -> Map.of();

  /**
   * Returns the spec values that can be read with confidence from a listing. Values are raw; the
   * caller validates them against the category's spec schema and drops what does not fit.
   *
   * @param title the listing title
   * @param tags retailer-supplied tags, possibly empty
   * @param description the listing description, HTML or plain text, possibly null
   */
  Map<String, Object> extract(String title, List<String> tags, String description);
}
