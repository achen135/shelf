package com.achen.shelf.signal;

/**
 * Why the rule said what it said. Every code is a plain fact about the product's rollup row, so a
 * badge on the page can spell out "on sale, at the year's low" rather than just be a colour.
 *
 * <p>Stored by name in {@code deal_signals.reason_codes}; the names are part of the API.
 */
public enum ReasonCode {
  // -- why there is no call
  NO_PRICE("no live listing has a price"),
  OUT_OF_STOCK("nothing is in stock at the moment"),
  THIN_HISTORY("too few observations in the trailing year to place today's price"),

  // -- where today's price sits against the list price
  ON_SALE("at or below 90% of the list price — a sale by the rollup's own definition"),
  MILD_DISCOUNT("below the list price, but by less than 10%"),
  AT_LIST("at or above the list price"),

  // -- where it sits against the year
  YEAR_LOW("the lowest price of the trailing year"),
  NEAR_YEAR_LOW("within a few percent of the year's low"),
  LOW_PERCENTILE("cheaper than most of the trailing year"),
  HIGH_PERCENTILE("most of the trailing year was cheaper than this"),
  MIDDLING_SALE("a sale, but neither near the year's low nor unusually cheap for this product"),

  // -- whether waiting has ever paid for this product
  SALES_RECUR("this product goes on sale, often enough to wait for the next one"),
  SALES_RARE("this product goes on sale, but too rarely to count on one soon"),
  NO_SALE_HISTORY("no sale in the trailing year — nothing says a better price is coming"),

  // -- the split
  MOSTLY_SYNTHETIC("more than half of the year behind this call is synthetic backfill");

  private final String description;

  ReasonCode(String description) {
    this.description = description;
  }

  /** One plain-English line, for the page and the report. */
  public String description() {
    return description;
  }
}
