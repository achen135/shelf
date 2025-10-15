package com.achen.shelf.crawl.parse;

import com.achen.shelf.config.BrandSource;
import com.achen.shelf.config.Retailer;
import com.achen.shelf.crawl.Money;
import com.achen.shelf.crawl.ParsedOffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses a rendered Shopify collection page with Jsoup — the {@code mode: html} path.
 *
 * <p>No keyboard retailer needs this today: every one that survived the M0 survey serves product
 * JSON, which is cheaper to fetch and unambiguous to read, so preferring HTML would mean pulling a
 * megabyte of markup for data already available as 40KB of JSON. It exists because the html mode is
 * part of the config format and must actually work — the storefronts that disable the .json
 * endpoint still render these pages, and M7's category is unlikely to be as fortunate.
 *
 * <p>Selectors target Shopify's Dawn-derived card markup, verified against a live page saved as the
 * golden fixture this parser is tested with. A page-level offer has no variant, so one card is one
 * offer, priced at whatever the card shows ("From $129.00" → 12900).
 */
public final class ShopifyCollectionHtmlParser implements Parser {

  private static final Logger log = LoggerFactory.getLogger(ShopifyCollectionHtmlParser.class);

  private final SpecExtractor specExtractor;

  public ShopifyCollectionHtmlParser(SpecExtractor specExtractor) {
    this.specExtractor = specExtractor;
  }

  @Override
  public List<ParsedOffer> parse(Retailer retailer, String body, String sourceUrl) {
    Document doc = Jsoup.parse(body, retailer.baseUrl());
    List<ParsedOffer> offers = new ArrayList<>();

    for (Element card : doc.select("li.collection-product-card, li.grid__item .card-wrapper")) {
      Element link = card.selectFirst("h3.card__title a[href], a.full-unstyled-link[href]");
      if (link == null) {
        continue;
      }
      String title = link.text().strip();
      String url = link.absUrl("href");
      if (title.isEmpty() || url.isEmpty()) {
        continue;
      }

      String brand =
          retailer.brandSource() == BrandSource.FIXED
              ? retailer.brand()
              : text(card, ".caption-with-letter-spacing.subtitle");
      if (brand == null || brand.isBlank()) {
        log.warn("{}: no vendor on the card for '{}', skipping", retailer.name(), title);
        continue;
      }

      // On a discounted card the sale price is the one a buyer pays; the regular price is struck
      // through next to it. Reading the wrong one would report a discount that does not exist.
      String priceText = text(card, ".price__sale .price-item--sale");
      if (priceText == null || priceText.isBlank()) {
        priceText = text(card, ".price__regular .price-item--regular");
      }
      java.util.Optional<Integer> priceCents = Money.fromText(priceText);
      if (priceCents.isEmpty()) {
        log.debug("{}: no price on the card for '{}', skipping", retailer.name(), title);
        continue;
      }

      boolean soldOut = card.selectFirst(".badge--soldout") != null;
      Map<String, Object> spec = specExtractor.extract(title, List.of(), null);

      offers.add(
          new ParsedOffer(
              retailer.name(),
              url,
              brand.strip(),
              title,
              null,
              priceCents.get(),
              0,
              "USD",
              !soldOut,
              null,
              spec));
    }
    return offers;
  }

  private static String text(Element card, String selector) {
    Element found = card.selectFirst(selector);
    return found == null ? null : found.text().strip();
  }
}
