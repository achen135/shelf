package com.achen.shelf.crawl.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.config.AuthSpec;
import com.achen.shelf.config.BrandSource;
import com.achen.shelf.config.FetchMode;
import com.achen.shelf.config.FetchSpec;
import com.achen.shelf.config.Retailer;
import com.achen.shelf.crawl.ParsedOffer;
import com.achen.shelf.testing.FixtureServer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Golden-file test for the {@code mode: html} path, against a real collection page. */
class ShopifyCollectionHtmlParserTest {

  private final Parser parser = new ShopifyCollectionHtmlParser(new KeyboardSpecExtractor());

  private final Retailer retailer =
      new Retailer(
          "mechanicalkeyboards",
          "https://mechanicalkeyboards.com",
          true,
          BrandSource.VENDOR,
          null,
          new FetchSpec(
              FetchMode.HTML,
              List.of("/collections/75-keyboards"),
              1,
              1,
              0.2,
              true,
              AuthSpec.none()),
          "shopify_collection_html",
          null);

  private List<ParsedOffer> parseFixture() {
    return parser.parse(
        retailer,
        FixtureServer.Fixtures.read("html/mechanicalkeyboards-collection.html"),
        "https://mechanicalkeyboards.com/collections/75-keyboards");
  }

  @Test
  void readsEveryProductCard() {
    List<ParsedOffer> offers = parseFixture();

    assertThat(offers).hasSize(4);
    assertThat(offers)
        .extracting(ParsedOffer::title)
        .contains("WOBKEY Rainy 75 Pro Keyboard", "Wooting 80HE Magnetic 75% Hotswap RGB Keyboard");
  }

  @Test
  void resolvesRelativeCardLinksAgainstTheRetailer() {
    assertThat(parseFixture())
        .extracting(ParsedOffer::url)
        .allSatisfy(url -> assertThat(url).startsWith("https://mechanicalkeyboards.com/products/"));
  }

  @Test
  void readsTheVendorOffTheCard() {
    assertThat(parseFixture())
        .extracting(ParsedOffer::brand)
        .contains("WOBKEY", "Wooting", "Ducky");
  }

  @Test
  void takesTheSalePriceNotTheStruckThroughOne() {
    // The WOBKEY card is on sale: "From $129.00" with $159.00 struck through. Reading the
    // compare-at price would invent a discount that is not on offer.
    ParsedOffer wobkey =
        parseFixture().stream()
            .filter(o -> o.title().startsWith("WOBKEY"))
            .findFirst()
            .orElseThrow();

    assertThat(wobkey.priceCents()).isEqualTo(12900);
  }

  @Test
  void extractsSpecsFromTheCardTitle() {
    ParsedOffer wooting =
        parseFixture().stream()
            .filter(o -> o.title().startsWith("Wooting 80HE"))
            .findFirst()
            .orElseThrow();

    assertThat(wooting.specFields())
        .containsEntry("layout_size", "75")
        .containsEntry("hot_swap", Boolean.TRUE);
  }

  @Test
  void ignoresMarkupWithNoCards() {
    assertThat(
            parser.parse(
                retailer, "<html><body><p>nothing here</p></body></html>", "https://x.test"))
        .isEmpty();
  }
}
