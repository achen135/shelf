package com.achen.shelf.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny HTTP server for tests, backed by the JDK's own {@code com.sun.net.httpserver}.
 *
 * <p>Everything the crawler talks to in a test goes through here: robots.txt, listing pages, and
 * deliberate failures. That keeps the test suite hermetic — no network, no live retailer, no
 * flakiness from someone else's rate limiting — while still exercising the real {@link
 * java.net.http.HttpClient} path end to end.
 *
 * <p>It also records what it was asked for, so tests can assert on crawler behaviour that is
 * invisible from the database: how many times a URL was retried, and whether a real User-Agent was
 * sent.
 */
public final class FixtureServer implements AutoCloseable {

  /** One scripted response. */
  public record Response(
      int status, String body, String contentType, Duration delay, boolean gzip) {

    public static Response ok(String body, String contentType) {
      return new Response(200, body, contentType, Duration.ZERO, false);
    }

    public static Response status(int status, String body) {
      return new Response(status, body, "text/plain; charset=utf-8", Duration.ZERO, false);
    }

    /** A response that arrives after {@code delay} — used to exercise client timeouts. */
    public Response delayedBy(Duration delay) {
      return new Response(status, body, contentType, delay, gzip);
    }

    /**
     * The same response, gzip-encoded with a {@code Content-Encoding: gzip} header — which is what
     * every real retailer sends back once a client advertises gzip support.
     */
    public Response gzipped() {
      return new Response(status, body, contentType, delay, true);
    }
  }

  private final HttpServer server;
  private final Map<String, List<Response>> scripted = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
  private final List<String> userAgents = java.util.Collections.synchronizedList(new ArrayList<>());
  private final Map<String, List<Instant>> requestTimes = new ConcurrentHashMap<>();

  private FixtureServer(HttpServer server) {
    this.server = server;
  }

  /** Starts a server on an ephemeral port. */
  public static FixtureServer start() {
    try {
      HttpServer http =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      FixtureServer fixture = new FixtureServer(http);
      http.createContext("/", fixture::dispatch);
      // One virtual thread per request. The default (null) executor handles requests on the
      // single dispatcher thread, so a response scripted to arrive after a delay would also
      // hold up every other request — and M2's tests have several workers fetching at once,
      // one of them deliberately stuck.
      http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      http.start();
      return fixture;
    } catch (IOException e) {
      throw new UncheckedIOException("could not start the fixture server", e);
    }
  }

  /** Serves {@code body} at {@code path} for every request. */
  public FixtureServer serve(String path, String body, String contentType) {
    scripted.put(path, List.of(Response.ok(body, contentType)));
    return this;
  }

  /** Serves a file from {@code src/test/resources/fixtures} at {@code path}. */
  public FixtureServer serveFixture(String path, String fixtureName, String contentType) {
    return serve(path, Fixtures.read(fixtureName), contentType);
  }

  /**
   * Serves each response in turn, repeating the last one once the list is exhausted. This is how
   * retry/backoff is tested: script {@code [500, 500, 200]} and assert the crawler got the 200.
   */
  public FixtureServer serveSequence(String path, List<Response> responses) {
    if (responses.isEmpty()) {
      throw new IllegalArgumentException("a scripted sequence needs at least one response");
    }
    scripted.put(path, List.copyOf(responses));
    return this;
  }

  /** Absolute URL for a path on this server. */
  public String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  /** The origin, with no trailing slash — suitable for a retailer's {@code base_url}. */
  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** How many requests this server has received for {@code path}. */
  public int hits(String path) {
    AtomicInteger n = hits.get(path);
    return n == null ? 0 : n.get();
  }

  /** When each request for {@code path} arrived, in arrival order — for asserting on pacing. */
  public List<Instant> requestTimes(String path) {
    List<Instant> times = requestTimes.get(path);
    if (times == null) {
      return List.of();
    }
    synchronized (times) {
      return List.copyOf(times);
    }
  }

  /** Every User-Agent header seen, in arrival order. */
  public List<String> userAgents() {
    synchronized (userAgents) {
      return List.copyOf(userAgents);
    }
  }

  private void dispatch(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String withQuery =
        exchange.getRequestURI().getQuery() == null
            ? path
            : path + "?" + exchange.getRequestURI().getQuery();

    String ua = exchange.getRequestHeaders().getFirst("User-Agent");
    userAgents.add(ua == null ? "" : ua);

    // Tests may register either the bare path or path+query; prefer the more specific match.
    List<Response> responses = scripted.get(withQuery);
    String key = withQuery;
    if (responses == null) {
      responses = scripted.get(path);
      key = path;
    }
    int n = hits.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    requestTimes
        .computeIfAbsent(key, k -> java.util.Collections.synchronizedList(new ArrayList<>()))
        .add(Instant.now());

    Response response =
        responses == null
            ? Response.status(404, "no fixture registered for " + withQuery)
            : responses.get(Math.min(n - 1, responses.size() - 1));

    if (!response.delay().isZero()) {
      try {
        Thread.sleep(response.delay().toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
    if (response.gzip()) {
      bytes = gzip(bytes);
      exchange.getResponseHeaders().add("Content-Encoding", "gzip");
    }
    exchange.getResponseHeaders().add("Content-Type", response.contentType());
    exchange.sendResponseHeaders(response.status(), bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static byte[] gzip(byte[] raw) throws IOException {
    var out = new java.io.ByteArrayOutputStream();
    try (var gz = new java.util.zip.GZIPOutputStream(out)) {
      gz.write(raw);
    }
    return out.toByteArray();
  }

  /** Serves a gzip-encoded body at {@code path}, as a real retailer would. */
  public FixtureServer serveGzipped(String path, String body, String contentType) {
    scripted.put(path, List.of(Response.ok(body, contentType).gzipped()));
    return this;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  /** Reads files out of {@code src/test/resources/fixtures}. */
  public static final class Fixtures {
    private Fixtures() {}

    /** Reads a fixture by name, e.g. {@code "shopify/keychron-collection.json"}. */
    public static String read(String name) {
      String resource = "/fixtures/" + name;
      try (InputStream in = Fixtures.class.getResourceAsStream(resource)) {
        if (in == null) {
          throw new IllegalArgumentException("no such fixture: src/test/resources" + resource);
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException("could not read fixture " + resource, e);
      }
    }
  }
}
