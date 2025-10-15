package com.achen.shelf.crawl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP with the manners this project promised: one identifiable User-Agent, bounded retries, and
 * backoff that gets out of the way when a server says it is busy.
 *
 * <p>Retries cover transport failures and the statuses that mean "try later" (429, 5xx). A 4xx
 * other than 429 is not retried — it is an answer, and repeating the request would just be noise.
 * Backoff is exponential with full jitter, so that M2's worker pool cannot fall into a synchronised
 * retry storm against one domain; when a server sends {@code Retry-After}, that wins, because it is
 * the one number the other side actually asked for.
 */
public final class Fetcher {

  private static final Logger log = LoggerFactory.getLogger(Fetcher.class);
  private static final Set<Integer> RETRYABLE = Set.of(408, 425, 429, 500, 502, 503, 504);

  private final HttpClient client;
  private final String userAgent;
  private final int maxAttempts;
  private final Duration baseBackoff;
  private final Duration requestTimeout;

  public Fetcher(String userAgent, int maxAttempts, Duration baseBackoff, Duration requestTimeout) {
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    this.userAgent = userAgent;
    this.maxAttempts = maxAttempts;
    this.baseBackoff = baseBackoff;
    this.requestTimeout = requestTimeout;
  }

  /** A fetcher with the defaults a crawl uses. */
  public static Fetcher withDefaults(String userAgent) {
    return new Fetcher(userAgent, 3, Duration.ofMillis(500), Duration.ofSeconds(30));
  }

  /** Fetches a URL, retrying per the policy above. Never throws for an HTTP-level failure. */
  public FetchResult fetch(String url) {
    Integer lastStatus = null;
    String lastFailure = "no attempt was made";

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Accept-Encoding", "gzip")
                .timeout(requestTimeout)
                .GET()
                .build();
        HttpResponse<byte[]> response =
            client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        lastStatus = response.statusCode();

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
          return FetchResult.ok(url, response.statusCode(), decode(response), attempt, null);
        }
        if (!RETRYABLE.contains(response.statusCode())) {
          return FetchResult.failed(
              url, response.statusCode(), attempt, "HTTP " + response.statusCode());
        }
        lastFailure = "HTTP " + response.statusCode();
        if (attempt < maxAttempts) {
          Duration wait = retryAfter(response).orElse(backoff(attempt));
          sleep(wait, url, attempt, lastFailure);
        }
      } catch (IOException e) {
        lastFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (attempt < maxAttempts) {
          sleep(backoff(attempt), url, attempt, lastFailure);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return FetchResult.failed(url, lastStatus, attempt, "interrupted");
      }
    }
    return FetchResult.failed(url, lastStatus, maxAttempts, lastFailure);
  }

  /** Exponential backoff with full jitter: a random point in [0, base * 2^(attempt-1)]. */
  private Duration backoff(int attempt) {
    long ceiling = baseBackoff.toMillis() * (1L << (attempt - 1));
    return Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceiling + 1));
  }

  /**
   * Turns the response bytes into text, decompressing first when the server used gzip.
   *
   * <p>We ask for gzip because these payloads are large — a single collection page is 4.8MB of JSON
   * and 780KB compressed — and sending a retailer's bandwidth bill up tenfold for no reason is not
   * politeness. But java.net.http does NOT decompress for you: it hands back exactly what came over
   * the wire. Decoding those bytes as UTF-8 without inflating them first produces a string of
   * replacement characters, and the original bytes are then unrecoverable.
   */
  private static String decode(HttpResponse<byte[]> response) throws IOException {
    byte[] body = response.body();
    boolean gzipped =
        response
            .headers()
            .firstValue("Content-Encoding")
            .map(encoding -> encoding.toLowerCase(Locale.ROOT).contains("gzip"))
            .orElse(false);
    if (!gzipped) {
      return new String(body, StandardCharsets.UTF_8);
    }
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Optional<Duration> retryAfter(HttpResponse<byte[]> response) {
    return response
        .headers()
        .firstValue("Retry-After")
        .flatMap(
            raw -> {
              try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(raw.strip())));
              } catch (NumberFormatException e) {
                // The HTTP-date form is legal but rare; treating it as absent just means we
                // fall back to our own backoff, which is the conservative direction.
                return Optional.empty();
              }
            });
  }

  private static void sleep(Duration duration, String url, int attempt, String why) {
    log.debug("retrying {} in {}ms after attempt {} ({})", url, duration.toMillis(), attempt, why);
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
