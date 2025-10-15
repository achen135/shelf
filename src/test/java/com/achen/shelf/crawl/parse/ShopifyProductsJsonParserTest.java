package com.achen.shelf.crawl.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.achen.shelf.config.AuthSpec;
import com.achen.shelf.config.BrandSource;
import com.achen.shelf.config.FetchMode;
import com.achen.shelf.config.FetchSpec;
import com.achen.shelf.config.Retailer;
import com.achen.shelf.crawl.ParsedOffer;
import com.achen.shelf.testing.FixtureServer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden-file tests against bodies captured from live storefronts (see fixtures/README.md).
 *
 * <p>These are the tests that fail when a retailer changes its data, which is the whole point: the
 * alternative is a crawl that quietly writes fewer rows and a price history with a hole in it.
 */
class ShopifyProductsJsonParserTest {

  private final Parser parser = new ShopifyProductsJsonParser(new KeyboardSpecExtractor());

  private static Retailer retailer(String name, String baseUrl, BrandSource source, String brand) {
    return new Retailer(
        name,
        baseUrl,
        true,
        source,
        brand,
        new FetchSpec(FetchMode.API, List.of("/x.json"), 250, 1, 0.2, true, AuthSpec.none()),
        "shopify_products_json",
        null);
  }

  @Test
  void parsesEveryVariantAsItsOwnOffer() {
    List<ParsedOffer> offers =
        parser.parse(
            retailer("keychron", "https://www.keychron.com", BrandSource.FIXED, "Keychron"),
            FixtureServer.Fixtures.read("shopify/keychron-products.json"),
            "https://www.keychron.com/collections/all-keyboards/products.json");

    // 3 products x 2 variants in the fixture: a variant is a separately purchasable SKU.
    assertThat(offers).hasSize(6);
    assertThat(offers).extracting(ParsedOffer::url).doesNotHaveDuplicates();
  }

  @Test
  void readsPriceStockAndSkuFromTheVariant() {
    List<ParsedOffer> offers =
        parser.parse(
            retailer("keychron", "https://www.keychron.com", BrandSource.FIXED, "Keychron"),
            FixtureServer.Fixtures.read("shopify/keychron-products.json"),
            "https://www.keychron.com/x.json");

    ParsedOffer q6he =
        offers.stream().filter(o -> "Q6H-M1".equals(o.retailerSku())).findFirst().orElseThrow();

    assertThat(q6he.priceCents()).isEqualTo(22999);
    assertThat(q6he.currency()).isEqualTo("USD");
    assertThat(q6he.inStock()).isFalse();
    assertThat(q6he.url())
        .isEqualTo(
            "https://www.keychron.com/products/keychron-q6-he-qmk-wireless-custom-keyboard?variant=42069797568601");
    assertThat(q6he.title())
        .isEqualTo(
            "Keychron Q6 HE QMK Wireless Custom Keyboard — Fully Assembled Knob / Carbon Black"
                + " / Gateron Double-Rail Magnetic Nebula Switch");
  }

  @Test
  void usesTheConfiguredBrandWhenVendorIsAProductSeries() {
    // Keychron's `vendor` is "Q HE series" / "K Ultra series"; trusting it would create one
    // bogus brand per series.
    List<ParsedOffer> offers =
        parser.parse(
            retailer("keychron", "https://www.keychron.com", BrandSource.FIXED, "Keychron"),
            FixtureServer.Fixtures.read("shopify/keychron-products.json"),
            "https://www.keychron.com/x.json");

    assertThat(offers).extracting(ParsedOffer::brand).containsOnly("Keychron");
  }

  @Test
  void usesTheVendorFieldAtAMultiBrandRetailer() {
    List<ParsedOffer> offers =
        parser.parse(
            retailer(
                "mechanicalkeyboards", "https://mechanicalkeyboards.com", BrandSource.VENDOR, null),
            FixtureServer.Fixtures.read("shopify/mechanicalkeyboards-products.json"),
            "https://mechanicalkeyboards.com/x.json");

    assertThat(offers).extracting(ParsedOffer::brand).containsOnly("HHKB", "Ducky", "Wooting");
  }

  @Test
  void keepsVariantsOfOneProductThatAreDifferentlyPriced() {
    List<ParsedOffer> offers =
        parser.parse(
            retailer(
                "mechanicalkeyboards", "https://mechanicalkeyboards.com", BrandSource.VENDOR, null),
            FixtureServer.Fixtures.read("shopify/mechanicalkeyboards-products.json"),
            "https://mechanicalkeyboards.com/x.json");

    List<Integer> wooting =
        offers.stream()
            .filter(o -> o.title().startsWith("Wooting 60HE V2"))
            .map(ParsedOffer::priceCents)
            .toList();

    assertThat(wooting).containsExactlyInAnyOrder(18999, 24999);
  }

  @Test
  void skipsPlaceholderPricedVariants() {
    // Stores list unreleased products at 0.00; recording that as a price would corrupt every
    // trailing minimum and percentile computed from it.
    List<ParsedOffer> offers =
        parser.parse(
            retailer("kbdfans", "https://kbdfans.com", BrandSource.FIXED, "KBDfans"),
            FixtureServer.Fixtures.read("shopify/kbdfans-products.json"),
            "https://kbdfans.com/x.json");

    assertThat(offers).extracting(ParsedOffer::priceCents).doesNotContain(0);
    assertThat(offers).extracting(ParsedOffer::title).noneMatch(t -> t.contains("SOLAR"));
    assertThat(offers).hasSize(2);
  }

  @Test
  void extractsSpecsFromTagsAndTitle() {
    List<ParsedOffer> offers =
        parser.parse(
            retailer("keychron", "https://www.keychron.com", BrandSource.FIXED, "Keychron"),
            FixtureServer.Fixtures.read("shopify/keychron-products.json"),
            "https://www.keychron.com/x.json");

    ParsedOffer q6he =
        offers.stream().filter(o -> "Q6H-M1".equals(o.retailerSku())).findFirst().orElseThrow();

    assertThat(q6he.specFields())
        .containsEntry("layout_size", "full") // tag "100% Layout"
        .containsEntry("case_material", "aluminum") // tag "CaseMaterial:All-metal"
        .containsEntry("mount_type", "gasket") // tag "MountStyle:Gasket Mount"
        .containsEntry("keycap_material", "pbt") // tag "KeycapsType:Double-shot PBT"
        .containsEntry("hot_swap", Boolean.TRUE) // tag "SwitchMount:Hot-swappable"
        .containsEntry("switch_type", "magnetic"); // "HE" in the title
    // Tagged both "Wired Keyboard" and "Connectivity:wireless": too ambiguous to claim.
    assertThat(q6he.specFields()).doesNotContainKey("connectivity");
  }

  @Test
  void returnsNothingForAnEmptyCollectionPage() {
    List<ParsedOffer> offers =
        parser.parse(
            retailer("keychron", "https://www.keychron.com", BrandSource.FIXED, "Keychron"),
            FixtureServer.Fixtures.read("shopify/empty-products.json"),
            "https://www.keychron.com/x.json?page=99");

    assertThat(offers).isEmpty();
  }

  @Test
  void rejectsABodyThatIsNotTheExpectedShape() {
    Retailer r = retailer("keychron", "https://www.keychron.com", BrandSource.FIXED, "Keychron");

    assertThatThrownBy(() -> parser.parse(r, "<html>maintenance</html>", "https://x.test/y.json"))
        .isInstanceOf(ParseException.class)
        .hasMessageContaining("not valid JSON");
    assertThatThrownBy(() -> parser.parse(r, "{\"items\":[]}", "https://x.test/y.json"))
        .isInstanceOf(ParseException.class)
        .hasMessageContaining("no `products` array");
  }
}
