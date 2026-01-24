package com.achen.shelf.crawl.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.config.AuthSpec;
import com.achen.shelf.config.BrandSource;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.config.FetchMode;
import com.achen.shelf.config.FetchSpec;
import com.achen.shelf.config.Retailer;
import com.achen.shelf.config.SpecValidator;
import com.achen.shelf.crawl.ParsedOffer;
import com.achen.shelf.testing.FixtureServer;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden-file tests for the monitors category (M7): the unchanged Shopify parser over bodies
 * captured from three monitor storefronts, with the monitor spec extractor behind it, every spec
 * then validated against the shipped {@code categories/monitors.yaml}.
 *
 * <p>These are the tests that fail when one of these retailers changes its data — and the proof
 * that the parser needed no change for a second category: the registry hands it a different
 * extractor and the fixtures are different, nothing else.
 */
class MonitorFixturesTest {

  private final ParserRegistry registry =
      ParserRegistry.forCategory(
          new CategoryConfigLoader().load(Path.of("categories"), "monitors"));
  private final Parser parser = registry.get("shopify_products_json");
  private final SpecValidator validator =
      new SpecValidator(
          new CategoryConfigLoader().load(Path.of("categories"), "monitors").specSchema());

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
  void pixioColourwaysAreSeparateOffersWithTheSameSpec() {
    List<ParsedOffer> offers =
        parser.parse(
            retailer("pixio", "https://www.pixiogaming.com", BrandSource.FIXED, "Pixio"),
            FixtureServer.Fixtures.read("shopify/pixio-products.json"),
            "https://www.pixiogaming.com/collections/monitors/products.json");

    // 2 + 1 + 1 variants in the fixture.
    assertThat(offers).hasSize(4);
    assertThat(offers).extracting(ParsedOffer::brand).containsOnly("Pixio");
    assertThat(offers).extracting(ParsedOffer::url).doesNotHaveDuplicates();

    List<ParsedOffer> pxc278 =
        offers.stream().filter(o -> o.title().startsWith("PXC278 Wave")).toList();
    assertThat(pxc278).hasSize(2);
    assertThat(pxc278)
        .extracting(ParsedOffer::title)
        .containsExactly(
            "PXC278 Wave 27\" Curved Gaming Monitor — White",
            "PXC278 Wave 27\" Curved Gaming Monitor — Pink");
    assertThat(pxc278).extracting(ParsedOffer::priceCents).containsOnly(28999);
    assertThat(pxc278)
        .extracting(ParsedOffer::retailerSku)
        .containsExactly("PXC278WAVEW", "PXC278WAVEK");
    for (ParsedOffer o : pxc278) {
      SpecValidator.Result r = validator.validate(o.specFields());
      assertThat(r.isClean()).as(r.warnings().toString()).isTrue();
      assertThat(r.accepted())
          .containsEntry("panel_type", "va")
          .containsEntry("resolution", "1440p")
          .containsEntry("refresh_hz", 180L)
          .containsEntry("size_in", 27.0)
          .containsEntry("curved", Boolean.TRUE);
    }
  }

  @Test
  void pixioRefurbishedStockIsItsOwnListingAndSaysSo() {
    List<ParsedOffer> offers =
        parser.parse(
            retailer("pixio", "https://www.pixiogaming.com", BrandSource.FIXED, "Pixio"),
            FixtureServer.Fixtures.read("shopify/pixio-products.json"),
            "https://www.pixiogaming.com/x.json");

    ParsedOffer refurb =
        offers.stream().filter(o -> "PXC279-R".equals(o.retailerSku())).findFirst().orElseThrow();
    // The title is what the resolver's non_product_phrases ("refurbished") will read.
    assertThat(refurb.title()).isEqualTo("PXC279 Curved Gaming Monitor - Certified Refurbished");
    assertThat(refurb.priceCents()).isEqualTo(17599);
    assertThat(refurb.inStock()).isTrue();
    assertThat(refurb.specFields())
        .containsEntry("resolution", "1080p")
        .containsEntry("refresh_hz", 240L)
        .containsEntry("panel_type", "va");
  }

  @Test
  void focusCameraUsesTheVendorFieldIncludingWhenItIsNotABrand() {
    List<ParsedOffer> offers =
        parser.parse(
            retailer("focuscamera", "https://www.focuscamera.com", BrandSource.VENDOR, null),
            FixtureServer.Fixtures.read("shopify/focuscamera-products.json"),
            "https://www.focuscamera.com/collections/monitors/products.json");

    assertThat(offers).hasSize(3);
    // The used listing of the same Dell carries the retailer's department as its vendor. It is
    // still crawled and priced; the config's notes say why it never reaches a product.
    assertThat(offers)
        .extracting(ParsedOffer::brand)
        .containsExactly("Dell", "SAMSUNG", "Used Department");

    ParsedOffer dell = offers.get(0);
    assertThat(dell.priceCents()).isEqualTo(63900);
    assertThat(dell.inStock()).isTrue();
    assertThat(dell.retailerSku()).isEqualTo("DELL-U2725QE");
    assertThat(dell.url())
        .isEqualTo(
            "https://www.focuscamera.com/products/dell-dell-u2725qe-computers-tablets-monitors?variant=42378021961776");
    SpecValidator.Result r = validator.validate(dell.specFields());
    assertThat(r.isClean()).as(r.warnings().toString()).isTrue();
    assertThat(r.accepted())
        .containsEntry("panel_type", "ips")
        .containsEntry("resolution", "4k")
        .containsEntry("aspect_ratio", "16:9")
        .containsEntry("refresh_hz", 120L)
        .containsEntry("size_in", 27.0)
        .containsEntry("curved", Boolean.FALSE)
        .containsEntry("usb_c", Boolean.TRUE);

    ParsedOffer used = offers.get(2);
    assertThat(used.priceCents()).isEqualTo(50499);
    assertThat(used.specFields()).containsEntry("size_in", 27.0).containsEntry("resolution", "4k");
  }

  @Test
  void focusCameraTagSaladIsReadAsWritten() {
    // The S80UD is a 60 Hz IPS panel; Focus Camera's free-text tags on it say "240Hz" and "OLED".
    // The extractor records what the listing says — the retailer's data, not a guess — and the
    // M7 benchmark doc names this listing as the reason the multi-brand retailer's free tags are
    // the least trustworthy input in the category.
    List<ParsedOffer> offers =
        parser.parse(
            retailer("focuscamera", "https://www.focuscamera.com", BrandSource.VENDOR, null),
            FixtureServer.Fixtures.read("shopify/focuscamera-products.json"),
            "https://www.focuscamera.com/x.json");

    ParsedOffer samsung = offers.get(1);
    assertThat(samsung.inStock()).isFalse();
    assertThat(samsung.priceCents()).isEqualTo(39999);
    assertThat(samsung.specFields())
        .containsEntry("resolution", "4k")
        .containsEntry("size_in", 32.0)
        .containsEntry("refresh_hz", 240L)
        .containsEntry("panel_type", "oled");
  }

  @Test
  void innocnTitlesCarryTheModelLastAndBundlesParseLikeAnythingElse() {
    List<ParsedOffer> offers =
        parser.parse(
            retailer("innocn", "https://innocn.com", BrandSource.FIXED, "INNOCN"),
            FixtureServer.Fixtures.read("shopify/innocn-products.json"),
            "https://innocn.com/en-us/collections/brand-new/products.json");

    assertThat(offers).hasSize(3);
    assertThat(offers).extracting(ParsedOffer::brand).containsOnly("INNOCN");
    // The product URL is built from the base URL, not the localized collection path.
    assertThat(offers.get(0).url())
        .isEqualTo(
            "https://innocn.com/products/innocn-27-4k-120hz-ips-hdr500-professional-monitor-cb27u1?variant=53192027668704");

    ParsedOffer cb27u1 = offers.get(0);
    assertThat(cb27u1.title())
        .isEqualTo("INNOCN | 27\" 4K 120Hz IPS HDR500 Professional Monitor | CB27U1");
    assertThat(cb27u1.priceCents()).isEqualTo(34999);
    assertThat(cb27u1.specFields())
        .containsEntry("size_in", 27.0)
        .containsEntry("resolution", "4k")
        .containsEntry("refresh_hz", 120L)
        .containsEntry("hdr", "hdr500")
        .containsEntry("panel_type", "ips");

    ParsedOffer ultrawide = offers.get(1);
    assertThat(ultrawide.specFields())
        .containsEntry("resolution", "dual-1440p")
        .containsEntry("aspect_ratio", "32:9")
        .containsEntry("curved", Boolean.TRUE)
        .containsEntry("size_in", 49.0)
        .containsEntry("refresh_hz", 240L);

    // The bundle is a valid offer at its own price; it is the resolver, reading "combo" from the
    // category's non_product_phrases, that keeps it off the product's price.
    ParsedOffer combo = offers.get(2);
    assertThat(combo.title()).isEqualTo("GA27S1Q ×2 + Monitor Arm Combo");
    assertThat(combo.priceCents()).isEqualTo(122997);
    assertThat(combo.specFields()).isEmpty();
  }

  @Test
  void everyFixtureSpecFitsTheShippedSchema() {
    for (String fixture : List.of("pixio", "focuscamera", "innocn")) {
      List<ParsedOffer> offers =
          parser.parse(
              retailer(fixture, "https://example.test", BrandSource.FIXED, "X"),
              FixtureServer.Fixtures.read("shopify/" + fixture + "-products.json"),
              "https://example.test/x.json");
      for (ParsedOffer o : offers) {
        SpecValidator.Result r = validator.validate(o.specFields());
        assertThat(r.warnings()).as(fixture + ": " + o.title()).isEmpty();
      }
    }
  }
}
