package com.achen.shelf.crawl.parse;

import com.achen.shelf.config.Retailer;
import com.achen.shelf.crawl.ParsedOffer;
import java.util.List;

/**
 * Turns one fetched document into offers.
 *
 * <p>Parsers are pure: body in, offers out, no I/O and no database. That is what lets every one of
 * them be tested against a saved fixture, which in turn is what makes a retailer changing its
 * markup a test failure rather than a silent drop in row counts.
 */
@FunctionalInterface
public interface Parser {

  /**
   * Parses a response body.
   *
   * @param retailer the retailer config the body came from
   * @param body the response body
   * @param sourceUrl the URL fetched, used to resolve relative links
   * @return every offer found; empty is a valid answer, an exception is not
   */
  List<ParsedOffer> parse(Retailer retailer, String body, String sourceUrl);
}
