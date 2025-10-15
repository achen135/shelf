package com.achen.shelf.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fixture server is test infrastructure, so it gets tests of its own: a scripted failure that
 * silently served a 200 would turn M1's retry tests into assertions about nothing.
 */
class FixtureServerTest {

  private FixtureServer server;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeEach
  void setUp() {
    server = FixtureServer.start();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create(server.url(path)))
            .header("User-Agent", "TestAgent/1.0")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void servesRegisteredBodies() throws Exception {
    server.serve("/hello", "world", "text/plain");

    HttpResponse<String> response = get("/hello");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("world");
    assertThat(response.headers().firstValue("Content-Type")).contains("text/plain");
  }

  @Test
  void answers404ForAnythingUnregistered() throws Exception {
    assertThat(get("/nope").statusCode()).isEqualTo(404);
  }

  @Test
  void walksAScriptedSequenceAndThenRepeatsTheLastResponse() throws Exception {
    server.serveSequence(
        "/flaky",
        List.of(
            FixtureServer.Response.status(500, "first"),
            FixtureServer.Response.ok("second", "text/plain")));

    assertThat(get("/flaky").statusCode()).isEqualTo(500);
    assertThat(get("/flaky").body()).isEqualTo("second");
    assertThat(get("/flaky").body()).isEqualTo("second");
    assertThat(server.hits("/flaky")).isEqualTo(3);
  }

  @Test
  void distinguishesPathsWithQueryStrings() throws Exception {
    // Paging is expressed in the query string, so a fixture must be able to answer per page.
    server.serve("/list?page=1", "one", "text/plain");
    server.serve("/list?page=2", "two", "text/plain");

    assertThat(get("/list?page=1").body()).isEqualTo("one");
    assertThat(get("/list?page=2").body()).isEqualTo("two");
  }

  @Test
  void recordsTheUserAgentItWasCalledWith() throws Exception {
    server.serve("/x", "ok", "text/plain");

    get("/x");

    assertThat(server.userAgents()).containsExactly("TestAgent/1.0");
  }

  @Test
  void readsFixturesFromTheResourceTree() {
    assertThat(FixtureServer.Fixtures.read("categories/good-minimal.yaml"))
        .contains("name: widgets");
    assertThatThrownBy(() -> FixtureServer.Fixtures.read("nope/missing.json"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no such fixture");
  }
}
