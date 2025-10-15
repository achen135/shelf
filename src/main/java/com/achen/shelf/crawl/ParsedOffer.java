package com.achen.shelf.crawl;

import java.util.Map;

/**
 * One purchasable SKU as a parser found it, before anything has touched the database.
 *
 * <p>Deliberately a plain value: parsers do no I/O and no resolution, which is what makes them
 * testable against saved fixtures alone.
 *
 * @param retailer the config name of the retailer it came from
 * @param url canonical, absolute URL of this SKU — the natural key for {@code offers}
 * @param brand manufacturer, per the retailer's brand_source
 * @param title the listing's own title
 * @param model model designation if the parser could isolate one, else null
 * @param priceCents current price in minor units; never negative
 * @param shippingCents shipping in minor units, 0 when included or unknown
 * @param currency ISO 4217 code
 * @param inStock whether the retailer says it can be bought now
 * @param retailerSku the retailer's own id for this SKU, when exposed
 * @param specFields raw spec values, still to be validated against the category spec schema
 */
public record ParsedOffer(
    String retailer,
    String url,
    String brand,
    String title,
    String model,
    int priceCents,
    int shippingCents,
    String currency,
    boolean inStock,
    String retailerSku,
    Map<String, Object> specFields) {

  public ParsedOffer {
    specFields = specFields == null ? Map.of() : Map.copyOf(specFields);
    if (priceCents < 0) {
      throw new IllegalArgumentException("priceCents must not be negative: " + priceCents);
    }
  }
}
