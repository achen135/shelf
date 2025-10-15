package com.achen.shelf.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.db.RobotsCacheDao;
import com.achen.shelf.testing.FixtureServer;
import com.achen.shelf.testing.PostgresTestBase;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * robots.txt handling: what it allows, what it forbids, and what happens when it is unavailable.
 */
class RobotsGateTest extends PostgresTestBase {

  private FixtureServer server;
  private Fetcher fetcher;

  @BeforeEach
  void setUp() {
    server = FixtureServer.start();
    fetcher =
        new Fetcher(
            "ShelfBot/0.1 (+https://example.test/shelf)",
            2,
            Duration.ofMillis(5),
            Duration.ofSeconds(5));
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private RobotsGate gate() {
    return new RobotsGate(fetcher, new RobotsCacheDao(DB));
  }

  @Test
  void appliesTheRulesAStoreActuallyPublishes() {
    server.serveFixture("/robots.txt", "robots/shopify-style.txt", "text/plain");
    RobotsGate gate = gate();

    assertThat(gate.allows(server.url("/collections/keyboards/products.json"))).isTrue();
    assertThat(gate.allows(server.url("/products/some-keyboard"))).isTrue();
    assertThat(gate.allows(server.url("/cart"))).isFalse();
    assertThat(gate.allows(server.url("/checkout"))).isFalse();
    assertThat(gate.allows(server.url("/collections/keyboards?sort_by=price"))).isFalse();
  }

  @Test
  void obeysARuleAimedAtUsSpecifically() {
    // A site that allows everyone else but not ShelfBot. The more specific group wins, and this
    // is the case where a crawler that "mostly" honours robots.txt would get it wrong.
    server.serveFixture("/robots.txt", "robots/disallow-shelfbot.txt", "text/plain");

    assertThat(gate().allows(server.url("/collections/keyboards"))).isFalse();
  }

  @Test
  void allowsEverythingWhenThereAreNoRules() {
    server.serveFixture("/robots.txt", "robots/allow-all.txt", "text/plain");

    assertThat(gate().allows(server.url("/anything"))).isTrue();
  }

  @Test
  void treatsAMissingRobotsFileAsNoRestrictions() {
    // 404: the site has no rules to state. crawler-commons' convention, and the web's.
    server.serveSequence("/robots.txt", List.of(FixtureServer.Response.status(404, "nope")));

    assertThat(gate().allows(server.url("/collections/keyboards"))).isTrue();
  }

  @Test
  void refusesToCrawlWhenTheSitesWishesAreUnknown() {
    // 5xx means we could not learn the rules. Guessing in our own favour is exactly what a
    // polite crawler must not do, so this fails closed.
    server.serveSequence("/robots.txt", List.of(FixtureServer.Response.status(503, "down")));

    assertThat(gate().allows(server.url("/collections/keyboards"))).isFalse();
  }

  @Test
  void fetchesRobotsOnceAndCachesItInPostgres() {
    server.serveFixture("/robots.txt", "robots/shopify-style.txt", "text/plain");
    RobotsGate gate = gate();

    gate.allows(server.url("/a"));
    gate.allows(server.url("/b"));
    gate.allows(server.url("/c"));

    assertThat(server.hits("/robots.txt")).isEqualTo(1);

    // A fresh gate — standing in for M2's second worker process — reads the cached copy
    // instead of asking the site again.
    gate().allows(server.url("/d"));
    assertThat(server.hits("/robots.txt")).isEqualTo(1);
  }

  @Test
  void cachesRobotsContainingBytesPostgresRejects() {
    // keychron.com's robots.txt contains a NUL byte, which is legal in a served file and
    // illegal in a Postgres text column. Caching must not fail, and the rules must still apply.
    server.serve("/robots.txt", "User-agent: *\u0000\nDisallow: /cart\n", "text/plain");
    RobotsGate gate = gate();

    assertThat(gate.allows(server.url("/collections/keyboards"))).isTrue();
    assertThat(gate.allows(server.url("/cart"))).isFalse();

    // A second gate reads it back from the cache rather than re-fetching.
    gate().allows(server.url("/x"));
    assertThat(server.hits("/robots.txt")).isEqualTo(1);
  }

  @Test
  void readsACrawlDelay() {
    server.serve("/robots.txt", "User-agent: *\nCrawl-delay: 7\nDisallow: /cart\n", "text/plain");

    assertThat(gate().crawlDelay(server.url("/"))).contains(Duration.ofSeconds(7));
  }

  @Test
  void reportsNoCrawlDelayWhenNoneIsAskedFor() {
    server.serveFixture("/robots.txt", "robots/allow-all.txt", "text/plain");

    assertThat(gate().crawlDelay(server.url("/"))).isEmpty();
  }
}
