package com.achen.shelf.crawl;

import java.util.Optional;

/**
 * The outcome of one fetch, after any retries.
 *
 * @param url what was requested
 * @param status the final HTTP status, or empty when every attempt failed before a response
 * @param body the response body, empty unless the fetch succeeded
 * @param attempts how many HTTP requests were actually made
 * @param bodyRef path of the stored body under the raw directory, if it was stored
 * @param failure why the fetch failed, when it did
 */
public record FetchResult(
    String url,
    Optional<Integer> status,
    Optional<String> body,
    int attempts,
    Optional<String> bodyRef,
    Optional<String> failure) {

  public boolean succeeded() {
    return body.isPresent();
  }

  static FetchResult ok(String url, int status, String body, int attempts, String bodyRef) {
    return new FetchResult(
        url,
        Optional.of(status),
        Optional.of(body),
        attempts,
        Optional.ofNullable(bodyRef),
        Optional.empty());
  }

  static FetchResult failed(String url, Integer status, int attempts, String failure) {
    return new FetchResult(
        url,
        Optional.ofNullable(status),
        Optional.empty(),
        attempts,
        Optional.empty(),
        Optional.of(failure));
  }
}
