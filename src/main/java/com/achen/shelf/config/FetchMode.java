package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * How a retailer is fetched.
 *
 * <p>{@code API} means a structured endpoint parsed with Jackson — an official product API, or a
 * storefront's own public JSON. {@code HTML} means a public page fetched and parsed with Jsoup. The
 * distinction drives which parser the crawl uses and how loudly we rate-limit.
 */
public enum FetchMode {
  API,
  HTML;

  @JsonCreator
  public static FetchMode fromYaml(String raw) {
    if (raw == null) {
      throw new ConfigException("retailer fetch is missing `mode`");
    }
    try {
      return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      // No cause on purpose: Jackson reports the ROOT cause of anything a creator throws,
      // so chaining the IllegalArgumentException would replace this message with the far
      // less useful "No enum constant ...".
      throw new ConfigException("unknown fetch mode '" + raw + "' (expected `api` or `html`)");
    }
  }
}
