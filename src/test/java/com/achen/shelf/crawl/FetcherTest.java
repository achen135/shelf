package com.achen.shelf.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.testing.FixtureServer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The fetcher's retry policy and manners, exercised over real HTTP against a local server. */
class FetcherTest {

  private FixtureServer server;
  private Fetcher fetcher;

  @BeforeEach
  void setUp() {
    server = FixtureServer.start();
    // Short backoff: the policy under test is which statuses retry and how often, not how long.
    fetcher =
        new Fetcher(
            "ShelfBot/0.1 (+https://example.test/shelf)",
            3,
            Duration.ofMillis(5),
            Duration.ofSeconds(5));
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void returnsTheBodyOnSuccess() {
    server.serve("/ok", "{\"products\":[]}", "application/json");

    FetchResult result = fetcher.fetch(server.url("/ok"));

    assertThat(result.succeeded()).isTrue();
    assertThat(result.status()).contains(200);
    assertThat(result.body()).contains("{\"products\":[]}");
    assertThat(result.attempts()).isEqualTo(1);
  }

  @Test
  void sendsAnIdentifiableUserAgent() {
    // The politeness posture in docs/Design Decisions.md is only real if the header actually
    // goes out with a contact point in it.
    server.serve("/ok", "hi", "text/plain");

    fetcher.fetch(server.url("/ok"));

    assertThat(server.userAgents()).singleElement().asString().startsWith("ShelfBot/0.1 (+");
  }

  @Test
  void retriesServerErrorsAndSucceeds() {
    server.serveSequence(
        "/flaky",
        List.of(
            FixtureServer.Response.status(503, "busy"),
            FixtureServer.Response.status(500, "oops"),
            FixtureServer.Response.ok("recovered", "text/plain")));

    FetchResult result = fetcher.fetch(server.url("/flaky"));

    assertThat(result.succeeded()).isTrue();
    assertThat(result.body()).contains("recovered");
    assertThat(result.attempts()).isEqualTo(3);
    assertThat(server.hits("/flaky")).isEqualTo(3);
  }

  @Test
  void givesUpAfterTheAttemptLimit() {
    server.serve("/down", "still down", "text/plain");
    server.serveSequence("/down", List.of(FixtureServer.Response.status(500, "down")));

    FetchResult result = fetcher.fetch(server.url("/down"));

    assertThat(result.succeeded()).isFalse();
    assertThat(result.attempts()).isEqualTo(3);
    assertThat(result.status()).contains(500);
    assertThat(result.failure()).contains("HTTP 500");
  }

  @Test
  void doesNotRetryAClientError() {
    // A 404 is an answer. Asking again three times is just noise on someone else's server.
    server.serveSequence("/gone", List.of(FixtureServer.Response.status(404, "not found")));

    FetchResult result = fetcher.fetch(server.url("/gone"));

    assertThat(result.succeeded()).isFalse();
    assertThat(result.attempts()).isEqualTo(1);
    assertThat(server.hits("/gone")).isEqualTo(1);
  }

  @Test
  void retriesRateLimiting() {
    server.serveSequence(
        "/limited",
        List.of(
            FixtureServer.Response.status(429, "slow down"),
            FixtureServer.Response.ok("thanks", "text/plain")));

    FetchResult result = fetcher.fetch(server.url("/limited"));

    assertThat(result.succeeded()).isTrue();
    assertThat(result.attempts()).isEqualTo(2);
  }

  @Test
  void decompressesAGzippedResponse() {
    // Every one of the configured retailers answers with gzip once a client advertises it, and
    // java.net.http does not decompress: without inflating, the body decodes to replacement
    // characters and every parser sees garbage. This is the bug the first real crawl hit — six
    // fetches, six 200s, zero rows.
    String json = "{\"products\":[{\"title\":\"Keychron Q6 HE\"}]}";
    server.serveGzipped("/gz", json, "application/json");

    FetchResult result = fetcher.fetch(server.url("/gz"));

    assertThat(result.succeeded()).isTrue();
    assertThat(result.body()).contains(json);
  }

  @Test
  void stillReadsAnUncompressedResponse() {
    server.serve("/plain", "{\"products\":[]}", "application/json");

    assertThat(fetcher.fetch(server.url("/plain")).body()).contains("{\"products\":[]}");
  }

  @Test
  void reportsATimeoutAsAFailureRatherThanHanging() {
    Fetcher impatient =
        new Fetcher(
            "ShelfBot/0.1 (+https://example.test/shelf)",
            1,
            Duration.ofMillis(5),
            Duration.ofMillis(150));
    server.serveSequence(
        "/slow",
        List.of(
            FixtureServer.Response.ok("eventually", "text/plain")
                .delayedBy(Duration.ofSeconds(2))));

    FetchResult result = impatient.fetch(server.url("/slow"));

    assertThat(result.succeeded()).isFalse();
    assertThat(result.failure()).isPresent();
  }
}
