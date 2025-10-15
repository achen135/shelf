package com.achen.shelf.crawl.parse;

import com.achen.shelf.config.BrandSource;
import com.achen.shelf.config.Retailer;
import com.achen.shelf.crawl.Money;
import com.achen.shelf.crawl.ParsedOffer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses a Shopify storefront's public product JSON ({@code /collections/<handle>/products.json}).
 *
 * <p>Five of the six keyboard retailers surveyed in M0 run on Shopify and expose this endpoint, so
 * one parser covers them all; the per-retailer differences that do exist (chiefly whether {@code
 * vendor} holds a brand or a product series) are configuration, not code.
 *
 * <p>One Shopify product is many variants — a switch choice, a colour, a layout — and each variant
 * is a separately purchasable SKU at its own URL and its own price. Each therefore becomes one
 * offer. Collapsing a product to a single price would throw away exactly the variation the deal
 * signal exists to track.
 */
public final class ShopifyProductsJsonParser implements Parser {

  private static final Logger log = LoggerFactory.getLogger(ShopifyProductsJsonParser.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Shopify's products.json does not carry a currency, so this is the one value taken on trust: all
   * five configured storefronts price in USD. A retailer pricing in anything else would need the
   * currency stated in its config before it could be enabled.
   */
  private static final String ASSUMED_CURRENCY = "USD";

  private final SpecExtractor specExtractor;

  public ShopifyProductsJsonParser(SpecExtractor specExtractor) {
    this.specExtractor = specExtractor;
  }

  @Override
  public List<ParsedOffer> parse(Retailer retailer, String body, String sourceUrl) {
    JsonNode root;
    try {
      root = MAPPER.readTree(body);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ParseException(sourceUrl + " is not valid JSON", e);
    }
    JsonNode products = root.path("products");
    if (!products.isArray()) {
      throw new ParseException(
          sourceUrl + " has no `products` array (got: " + root.getNodeType() + ")");
    }

    List<ParsedOffer> offers = new ArrayList<>();
    for (JsonNode product : products) {
      offers.addAll(parseProduct(retailer, product));
    }
    return offers;
  }

  private List<ParsedOffer> parseProduct(Retailer retailer, JsonNode product) {
    String handle = product.path("handle").asText("");
    String title = product.path("title").asText("");
    if (handle.isEmpty() || title.isEmpty()) {
      log.warn("{}: skipping a product with no handle/title", retailer.name());
      return List.of();
    }

    String brand =
        retailer.brandSource() == BrandSource.FIXED
            ? retailer.brand()
            : product.path("vendor").asText("");
    if (brand.isBlank()) {
      log.warn("{}: no brand for '{}', skipping", retailer.name(), title);
      return List.of();
    }

    List<String> tags = new ArrayList<>();
    product.path("tags").forEach(tag -> tags.add(tag.asText("")));
    tags.add(product.path("product_type").asText(""));
    String description = plainText(product.path("body_html").asText(""));

    List<ParsedOffer> offers = new ArrayList<>();
    for (JsonNode variant : product.path("variants")) {
      parseVariant(retailer, variant, handle, title, brand, tags, description)
          .ifPresent(offers::add);
    }
    return offers;
  }

  private java.util.Optional<ParsedOffer> parseVariant(
      Retailer retailer,
      JsonNode variant,
      String handle,
      String productTitle,
      String brand,
      List<String> tags,
      String description) {

    java.util.Optional<Integer> priceCents = Money.toCents(variant.path("price").asText(""));
    if (priceCents.isEmpty() || priceCents.get() == 0) {
      // Stores use 0.00 as a placeholder for "not for sale yet"; recording it as a price would
      // poison every trailing-minimum and percentile this project is built to compute.
      log.debug(
          "{}: skipping variant of '{}' with no usable price ('{}')",
          retailer.name(),
          productTitle,
          variant.path("price").asText(""));
      return java.util.Optional.empty();
    }

    long variantId = variant.path("id").asLong();
    String url = retailer.baseUrl() + "/products/" + handle + "?variant=" + variantId;

    String variantTitle = variant.path("title").asText("");
    String fullTitle =
        variantTitle.isBlank() || "Default Title".equals(variantTitle)
            ? productTitle
            : productTitle + " — " + variantTitle;

    List<String> allTags = new ArrayList<>(tags);
    allTags.add(variantTitle);
    Map<String, Object> spec = specExtractor.extract(fullTitle, allTags, description);

    String sku = variant.path("sku").asText("");
    return java.util.Optional.of(
        new ParsedOffer(
            retailer.name(),
            url,
            brand,
            fullTitle,
            null, // the model is resolved against the catalog, not guessed at here
            priceCents.get(),
            0, // Shopify's product JSON carries no shipping cost; 0 means "unknown", per the schema
            ASSUMED_CURRENCY,
            variant.path("available").asBoolean(false),
            sku.isBlank() ? null : sku,
            spec));
  }

  private static String plainText(String html) {
    return html.isBlank() ? "" : Jsoup.parse(html).text();
  }
}
