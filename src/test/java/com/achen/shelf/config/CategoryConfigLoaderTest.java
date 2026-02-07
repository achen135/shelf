package com.achen.shelf.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.achen.shelf.testing.FixtureServer;
import java.nio.charset.StandardCharsets;
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
    // No communities section (M8): the category simply has no community track.
    assertThat(cfg.communities()).isEmpty();
    assertThat(cfg.enabledCommunities()).isEmpty();
  }

  @Test
  void loadsTheCommunitiesSection() {
    CategoryConfig cfg = loader.load(FIXTURES.resolve("good-communities.yaml"));

    assertThat(cfg.communities()).hasSize(2);
    Community reddit = cfg.communities().get(0);
    assertThat(reddit.name()).isEqualTo("r_widgets");
    assertThat(reddit.source()).isEqualTo(CommunitySource.REDDIT);
    assertThat(reddit.id()).isEqualTo("widgets");
    assertThat(reddit.enabled()).isFalse();
    assertThat(reddit.ingest().windowDays()).isEqualTo(14);
    assertThat(reddit.ingest().maxItems()).isEqualTo(50);
    assertThat(reddit.ingest().commentsPerItem()).isEqualTo(20);
    assertThat(reddit.ingest().maxRps()).isEqualTo(0.5);
    assertThat(reddit.ingest().auth().mode()).isEqualTo(AuthSpec.Mode.OAUTH2_CLIENT_CREDENTIALS);
    assertThat(reddit.ingest().auth().envVars()).containsExactly("W_REDDIT_ID", "W_REDDIT_SECRET");
    assertThat(reddit.notes()).isEqualTo("waiting on approval");

    // Defaults: enabled, 30 days, 100 items, 100 comments each, 1 rps.
    Community youtube = cfg.communities().get(1);
    assertThat(youtube.source()).isEqualTo(CommunitySource.YOUTUBE);
    assertThat(youtube.id()).isEqualTo("@WidgetReviews");
    assertThat(youtube.enabled()).isTrue();
    assertThat(youtube.ingest().windowDays()).isEqualTo(30);
    assertThat(youtube.ingest().maxItems()).isEqualTo(100);
    assertThat(youtube.ingest().commentsPerItem()).isEqualTo(100);
    assertThat(youtube.ingest().maxRps()).isEqualTo(1.0);
    assertThat(youtube.ingest().auth().envVars()).containsExactly("W_YOUTUBE_KEY");

    assertThat(cfg.enabledCommunities()).extracting(Community::name).containsExactly("yt_widgets");
  }

  @Test
  void rejectsABadCommunitiesSectionNamingEveryProblem() {
    assertThatThrownBy(() -> loader.load(FIXTURES.resolve("bad-communities.yaml")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("communities.r_widgets: `id` is the bare subreddit name")
        .hasMessageContaining("communities.r_widgets.ingest: `max_rps` must be > 0 and <= 2.0")
        .hasMessageContaining("communities.r_widgets.ingest.auth: `env_vars` must name exactly two")
        .hasMessageContaining("communities.r_widgets: duplicate community name")
        .hasMessageContaining("communities.r_widgets.ingest: `window_days` must be > 0")
        .hasMessageContaining("communities.r_widgets.ingest.auth: `env_vars` must name exactly one")
        .hasMessageContaining("communities.no_source: `source` is required");
  }

  @Test
  void loadsAliasesAndTheSentimentSection() {
    CategoryConfig cfg = loader.load(Path.of("categories"), "keyboards");

    assertThat(cfg.seedProducts())
        .filteredOn(s -> s.model().equals("Q8"))
        .singleElement()
        .satisfies(s -> assertThat(s.aliases()).containsExactly("Q8 Alice"));
    assertThat(cfg.seedProducts())
        .filteredOn(s -> s.model().equals("Q1 Pro"))
        .singleElement()
        .satisfies(s -> assertThat(s.aliases()).isEmpty());
    assertThat(cfg.sentiment().positive()).contains("creamy", "thocky");
    assertThat(cfg.sentiment().negative()).contains("mushy", "pingy");
    // A file without the section: nothing configured.
    assertThat(loader.load(FIXTURES.resolve("good-minimal.yaml")).sentiment())
        .isEqualTo(SentimentConfig.NONE);
  }

  @Test
  void rejectsBlankOrRepeatedAliasesAndAWordThatIsBothPraiseAndComplaint() {
    String yaml =
        FixtureServer.Fixtures.read("categories/good-minimal.yaml")
                .replace(
                    "canonical_name: \"ShopOne W1\", spec: { finish: matte, port_count: 2 } }",
                    "canonical_name: \"ShopOne W1\", aliases: [\"W-1\", \" \", \"w-1\"] }")
            + "sentiment:\n  positive: [crisp, \"\"]\n  negative: [crisp]\n";
    assertThatThrownBy(
            () ->
                loader.load(
                    new java.io.ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                    "inline"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("seed_products[0].aliases: contains a blank alias")
        .hasMessageContaining("seed_products[0].aliases: 'w-1' is listed twice")
        .hasMessageContaining("sentiment.positive: contains a blank phrase")
        .hasMessageContaining("sentiment: [crisp] listed as both positive and negative");
  }

  @Test
  void rejectsAnUnknownCommunitySource() {
    String yaml =
        FixtureServer.Fixtures.read("categories/good-communities.yaml")
            .replace("source: youtube", "source: tiktok");
    assertThatThrownBy(
            () ->
                loader.load(
                    new java.io.ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                    "inline"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("unknown community source 'tiktok'");
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
    // Communities (M8): the subreddits wait on Reddit's approval, disabled with the reason;
    // the channels are enabled and read with a key the environment supplies.
    assertThat(cfg.communities()).isNotEmpty();
    assertThat(cfg.communities())
        .filteredOn(c -> c.source() == CommunitySource.REDDIT)
        .isNotEmpty()
        .allSatisfy(
            c -> {
              assertThat(c.enabled()).isFalse();
              assertThat(c.notes()).contains("Responsible Builder Policy");
            });
    assertThat(cfg.enabledCommunities())
        .isNotEmpty()
        .allSatisfy(
            c -> {
              assertThat(c.source()).isEqualTo(CommunitySource.YOUTUBE);
              assertThat(c.ingest().auth().envVars()).containsExactly("YOUTUBE_API_KEY");
              assertThat(c.ingest().maxRps())
                  .isLessThanOrEqualTo(CategoryConfigLoader.MAX_ALLOWED_RPS);
            });
  }

  @Test
  void theShippedMonitorsConfigIsValid() {
    // The second category (M7). The same loader, the same rules; a hand edit fails here first.
    CategoryConfig cfg = loader.load(Path.of("categories"), "monitors");

    assertThat(cfg.name()).isEqualTo("monitors");
    assertThat(cfg.specSchema())
        .containsKeys("panel_type", "resolution", "refresh_hz", "size_in", "hdr");
    assertThat(cfg.seedProducts()).hasSizeGreaterThanOrEqualTo(100);
    assertThat(cfg.enabledRetailers()).hasSize(6);
    assertThat(cfg.enabledRetailers())
        .allSatisfy(
            r -> {
              assertThat(r.fetch().maxRps())
                  .isLessThanOrEqualTo(CategoryConfigLoader.MAX_ALLOWED_RPS);
              assertThat(r.fetch().respectRobots()).isTrue();
              assertThat(r.parser()).isEqualTo("shopify_products_json");
            });
    assertThat(cfg.resolution().identityFields()).containsExactly("size_in");
    assertThat(cfg.resolution().nonProductPhrases())
        .contains("used", "open box", "refurbished", "combo");
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
