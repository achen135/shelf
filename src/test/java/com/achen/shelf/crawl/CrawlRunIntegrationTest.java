package com.achen.shelf.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.testing.FixtureServer;
import com.achen.shelf.testing.PostgresTestBase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole M1 pipeline against a real Postgres and a real HTTP server: config → robots → fetch →
 * parse → validate → upsert → observe.
 *
 * <p>The test the milestone exists to pass. Everything below the assertions is the production path
 * — the only substitutions are where the bytes come from (a local fixture server instead of a
 * retailer) and what time it is.
 */
class CrawlRunIntegrationTest extends PostgresTestBase {

  private FixtureServer server;
  private CategoryConfig category;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() throws IOException {
    server = FixtureServer.start();
    server.serveFixture("/robots.txt", "robots/shopify-style.txt", "text/plain");
    server.serveFixture(
        "/collections/keyboards/products.json?limit=250&page=1",
        "shopify/keychron-products.json",
        "application/json");
    server.serveFixture(
        "/collections/keyboards/products.json?limit=250&page=2",
        "shopify/empty-products.json",
        "application/json");
    server.serveFixture(
        "/collections/html-keyboards", "html/mechanicalkeyboards-collection.html", "text/html");

    category = loadCategoryPointingAt(server.baseUrl());
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private CategoryConfig loadCategoryPointingAt(String baseUrl) throws IOException {
    String port = baseUrl.substring(baseUrl.lastIndexOf(':') + 1);
    String yaml =
        FixtureServer.Fixtures.read("categories/fixture-server.yaml.template")
            .replace("__PORT__", port);
    Path file = tempDir.resolve("keyboards.yaml");
    Files.writeString(file, yaml, StandardCharsets.UTF_8);
    return new CategoryConfigLoader().load(tempDir, "keyboards");
  }

  private CrawlRunner runnerAt(Instant now) {
    return new CrawlRunner(
        DB,
        new Fetcher(
            "ShelfBot/0.1 (+https://example.test/shelf)",
            2,
            Duration.ofMillis(5),
            Duration.ofSeconds(5)),
        new RawStore(tempDir.resolve("raw")),
        Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void writesProductsOffersAndObservations() throws SQLException, InterruptedException {
    CrawlSummary summary = runnerAt(Instant.parse("2026-09-10T12:00:00Z")).runOnce(category);

    // 3 JSON products x 2 variants, plus 4 cards on the HTML page.
    assertThat(count("select count(*) from offers")).isEqualTo(10);
    assertThat(count("select count(*) from price_observations")).isEqualTo(10);
    assertThat(count("select count(*) from products")).isEqualTo(4); // the seeds
    assertThat(summary.totalErrors()).isZero();

    // Every observation belongs to this run and carries the run's start instant.
    assertThat(count("select count(distinct observed_at) from price_observations")).isEqualTo(1);
    assertThat(
            count(
                "select count(*) from price_observations"
                    + " where observed_at = timestamptz '2026-09-10T12:00:00Z'"))
        .isEqualTo(10);
    assertThat(count("select count(*) from crawl_runs where finished_at is not null")).isEqualTo(1);
  }

  @Test
  void recordsBothFetchModes() throws SQLException, InterruptedException {
    CrawlSummary summary = runnerAt(Instant.parse("2026-09-10T12:00:00Z")).runOnce(category);

    CrawlSummary.RetailerSummary json =
        summary.retailers().stream()
            .filter(r -> r.retailer().equals("json_store"))
            .findFirst()
            .orElseThrow();
    CrawlSummary.RetailerSummary html =
        summary.retailers().stream()
            .filter(r -> r.retailer().equals("html_store"))
            .findFirst()
            .orElseThrow();

    assertThat(json.mode()).isEqualTo("api");
    assertThat(json.offersWritten()).isEqualTo(6);
    // Two pages fetched: the second came back empty, which is what stopped the paging. It
    // still cost a request, so it still counts — `pages` is the throughput number M2 reports.
    assertThat(json.pages()).isEqualTo(2);
    assertThat(json.offersSeen()).isEqualTo(6);
    assertThat(html.mode()).isEqualTo("html");
    assertThat(html.offersWritten()).isEqualTo(4);
  }

  @Test
  void obeysRobotsAndSkipsDisabledRetailers() throws SQLException, InterruptedException {
    CrawlSummary summary = runnerAt(Instant.parse("2026-09-10T12:00:00Z")).runOnce(category);

    CrawlSummary.RetailerSummary forbidden =
        summary.retailers().stream()
            .filter(r -> r.retailer().equals("forbidden_store"))
            .findFirst()
            .orElseThrow();

    // robots.txt disallows /collections/*sort_by*, so the URL must never have been requested.
    assertThat(forbidden.skippedByRobots()).isEqualTo(1);
    assertThat(forbidden.pages()).isZero();
    assertThat(server.hits("/collections/keyboards/products.json?sort_by=price&page=1")).isZero();

    // The disabled retailer is not in the run at all, and /collections/never was never fetched.
    assertThat(summary.retailers())
        .extracting(CrawlSummary.RetailerSummary::retailer)
        .doesNotContain("disabled_store");
    assertThat(server.hits("/collections/never/products.json?page=1")).isZero();
  }

  @Test
  void cachesRobotsInPostgresRatherThanRefetchingPerRetailer()
      throws SQLException, InterruptedException {
    runnerAt(Instant.parse("2026-09-10T12:00:00Z")).runOnce(category);

    // Three enabled retailers share a host: one robots.txt fetch between them, cached for M2's
    // workers to share too.
    assertThat(server.hits("/robots.txt")).isEqualTo(1);
    assertThat(count("select count(*) from robots_cache")).isEqualTo(1);
  }

  @Test
  void linksListingsToSeededProductsOnlyWhenCertain() throws SQLException, InterruptedException {
    runnerAt(Instant.parse("2026-09-10T12:00:00Z")).runOnce(category);

    // Q6 HE (2 variants) + K2 Ultra (2 variants) from the JSON store, Wooting 80HE from the
    // HTML store. The K5 Ultra listings and the rest of the HTML cards stay unresolved.
    assertThat(count("select count(*) from offers where product_id is not null")).isEqualTo(5);
    assertThat(count("select count(*) from offers where resolution_status = 'auto'")).isEqualTo(5);
    assertThat(count("select count(*) from offers where resolution_status = 'pending'"))
        .isEqualTo(5);

    // The seeded "Q65" must not collect the "Q6 HE" listings: model matching is on whole tokens.
    assertThat(
            count(
                "select count(*) from offers o join products p on p.id = o.product_id"
                    + " where p.model = 'Q65'"))
        .isZero();
  }

  @Test
  void storesEveryFetchedBodyAndAnAuditRow() throws SQLException, InterruptedException {
    runnerAt(Instant.parse("2026-09-10T12:00:00Z")).runOnce(category);

    // Two JSON pages (the second empty) plus one HTML page; robots.txt is not a crawl fetch.
    assertThat(count("select count(*) from raw_fetches")).isEqualTo(3);
    assertThat(count("select count(*) from raw_fetches where status = 200")).isEqualTo(3);
    assertThat(count("select count(*) from raw_fetches where body_ref is not null")).isEqualTo(3);
  }

  @Test
  void replayingTheSameCycleWritesNothingTwice() throws SQLException, InterruptedException {
    // The property M2's kill-worker test depends on: a task repicked after its lease expired
    // re-runs the same cycle, and must not leave a second price point behind. Same clock means
    // the same observed_at, which is the idempotency key.
    Instant runStart = Instant.parse("2026-09-10T12:00:00Z");
    runnerAt(runStart).runOnce(category);

    CrawlSummary replay = runnerAt(runStart).runOnce(category);

    assertThat(count("select count(*) from offers")).isEqualTo(10);
    assertThat(count("select count(*) from price_observations")).isEqualTo(10);
    assertThat(replay.totalObservations()).isZero(); // nothing new was inserted
    assertThat(count("select count(*) from products")).isEqualTo(4);
  }

  @Test
  void aLaterCycleAddsOnePricePointPerOfferAndNoNewOffers()
      throws SQLException, InterruptedException {
    // The other half of the same rule: two cycles at different times are two observations, which
    // is the time series this project exists to build.
    runnerAt(Instant.parse("2026-09-10T12:00:00Z")).runOnce(category);
    runnerAt(Instant.parse("2026-09-11T12:00:00Z")).runOnce(category);

    assertThat(count("select count(*) from offers")).isEqualTo(10);
    assertThat(count("select count(*) from price_observations")).isEqualTo(20);
    assertThat(count("select count(distinct observed_at) from price_observations")).isEqualTo(2);
    assertThat(count("select count(*) from crawl_runs")).isEqualTo(2);
  }

  @Test
  void validatesSpecsAgainstTheCategorySchema() throws SQLException, InterruptedException {
    runnerAt(Instant.parse("2026-09-10T12:00:00Z")).runOnce(category);

    // Seeds carry no spec in this config, so products.spec stays an empty object rather than
    // accumulating whatever the listings happened to say.
    assertThat(count("select count(*) from products where spec = '{}'::jsonb")).isEqualTo(4);
  }
}
