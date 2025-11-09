package com.achen.shelf.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The config loader accepts what the format allows and rejects everything else, loudly. */
class CategoryConfigLoaderTest {

  private static final Path FIXTURES = Path.of("src/test/resources/fixtures/categories");
  private final CategoryConfigLoader loader = new CategoryConfigLoader();

  @Test
  void loadsAWellFormedConfig() {
    CategoryConfig cfg = loader.load(FIXTURES.resolve("good-minimal.yaml"));

    assertThat(cfg.name()).isEqualTo("widgets");
    assertThat(cfg.specSchema()).containsOnlyKeys("finish", "wireless", "port_count", "weight_kg");
    assertThat(cfg.specSchema().get("finish").type()).isEqualTo(SpecType.STRING);
    assertThat(cfg.specSchema().get("finish").enumValues()).containsExactly("matte", "gloss");
    assertThat(cfg.specSchema().get("weight_kg").unit()).isEqualTo("kg");

    assertThat(cfg.retailers()).hasSize(1);
    Retailer retailer = cfg.retailers().get(0);
    assertThat(retailer.name()).isEqualTo("shop_one");
    assertThat(retailer.enabled()).isTrue();
    assertThat(retailer.brandSource()).isEqualTo(BrandSource.FIXED);
    assertThat(retailer.brand()).isEqualTo("ShopOne");
    assertThat(retailer.fetch().mode()).isEqualTo(FetchMode.API);
    assertThat(retailer.fetch().pageSize()).isEqualTo(100);
    assertThat(retailer.fetch().maxPages()).isEqualTo(2);
    assertThat(retailer.fetch().maxRps()).isEqualTo(0.5);
    assertThat(retailer.fetch().auth().mode()).isEqualTo(AuthSpec.Mode.NONE);

    assertThat(cfg.seedProducts()).hasSize(1);
    assertThat(cfg.seedProducts().get(0).spec()).containsEntry("finish", "matte");

    // No resolution section: both knobs default to off.
    assertThat(cfg.resolution()).isEqualTo(ResolutionConfig.NONE);
  }

  @Test
  void loadsTheResolutionSection() {
    CategoryConfig cfg = loader.load(Path.of("categories"), "keyboards");

    assertThat(cfg.resolution().identityFields()).isEmpty();
    assertThat(cfg.resolution().nonProductPhrases())
        .contains("bundle", "custom order", "with pbtfans", "module");
  }

  @Test
  void rejectsAResolutionSectionThatNamesUnknownFieldsOrBlankPhrases() {
    assertThatThrownBy(() -> loader.load(FIXTURES.resolve("bad-resolution.yaml")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("resolution.identity_fields: 'colour' is not a field")
        .hasMessageContaining("resolution.non_product_phrases: contains a blank phrase")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("'finish'"));
  }

  @Test
  void theShippedKeyboardsConfigIsValid() {
    // The one config that actually runs. If a hand edit breaks it, this fails before a crawl does.
    CategoryConfig cfg = loader.load(Path.of("categories"), "keyboards");

    assertThat(cfg.name()).isEqualTo("keyboards");
    assertThat(cfg.seedProducts()).hasSizeGreaterThanOrEqualTo(30);
    assertThat(cfg.enabledRetailers()).isNotEmpty();
    assertThat(cfg.enabledRetailers())
        .allSatisfy(
            r -> {
              assertThat(r.fetch().maxRps())
                  .isLessThanOrEqualTo(CategoryConfigLoader.MAX_ALLOWED_RPS);
              assertThat(r.fetch().respectRobots()).isTrue();
              assertThat(r.parser()).isNotBlank();
            });
    // Retailers awaiting credentials stay in the file, disabled, with their TODO attached.
    assertThat(cfg.retailer("bestbuy")).isPresent();
    assertThat(cfg.retailer("bestbuy").orElseThrow().enabled()).isFalse();
    assertThat(cfg.retailer("bestbuy").orElseThrow().notes()).contains("BESTBUY_API_KEY");
  }

  @Test
  void rejectsAnUnknownKey() {
    assertThatThrownBy(() -> loader.load(FIXTURES.resolve("bad-unknown-key.yaml")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("bad-unknown-key.yaml")
        .hasMessageContaining("octopus");
  }

  @Test
  void rejectsAnUnknownFetchMode() {
    assertThatThrownBy(() -> loader.load(FIXTURES.resolve("bad-fetch-mode.yaml")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("carrier_pigeon")
        .hasMessageContaining("api");
  }

  @Test
  void reportsEveryProblemAtOnce() {
    // A config with eight mistakes should not take eight edit-run cycles to fix.
    assertThatThrownBy(() -> loader.load(FIXTURES.resolve("bad-many-problems.yaml")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("`enum` is only supported for string fields")
        .hasMessageContaining("`base_url` must be an absolute http(s) URL")
        .hasMessageContaining("must be a path starting with '/'")
        .hasMessageContaining("`max_rps` must be > 0 and <= 2.0")
        .hasMessageContaining("robots.txt is always honoured")
        .hasMessageContaining("duplicate retailer name")
        .hasMessageContaining("must not end with '/'")
        .hasMessageContaining("`brand` is required when brand_source is `fixed`")
        .hasMessageContaining("`parser` is required")
        .hasMessageContaining("value 'neon' is not one of")
        .hasMessageContaining("unknown field 'colour'");
  }

  @Test
  void rejectsAConfigWithNoRetailers() {
    assertThatThrownBy(() -> loader.load(FIXTURES.resolve("bad-empty-retailers.yaml")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("`retailers` must declare at least one retailer");
  }

  @Test
  void rejectsMalformedYaml() {
    assertThatThrownBy(() -> loader.load(FIXTURES.resolve("not-yaml.yaml")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("not-yaml.yaml");
  }

  @Test
  void reportsAMissingCategoryByPath() {
    assertThatThrownBy(() -> loader.load(FIXTURES, "no-such-category"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("no config for category 'no-such-category'")
        .hasMessageContaining("no-such-category.yaml");
  }
}
