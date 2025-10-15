package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One retailer within a category.
 *
 * @param name stable id, used as {@code offers.retailer} and in log lines
 * @param baseUrl absolute origin; every fetched URL is this plus a configured list path
 * @param enabled false keeps the entry (and its TODO) in config without crawling it
 * @param brandSource where the manufacturer name comes from — see {@link BrandSource}
 * @param brand the manufacturer, required when brandSource is FIXED
 * @param fetch how to fetch it
 * @param parser id of the parser that turns a response body into offers
 * @param notes free text; kept in config so the reason a retailer is disabled travels with it
 */
public record Retailer(
    String name,
    String baseUrl,
    boolean enabled,
    BrandSource brandSource,
    String brand,
    FetchSpec fetch,
    String parser,
    String notes) {

  @JsonCreator
  static Retailer fromYaml(
      @JsonProperty("name") String name,
      @JsonProperty("base_url") String baseUrl,
      @JsonProperty("enabled") Boolean enabled,
      @JsonProperty("brand_source") BrandSource brandSource,
      @JsonProperty("brand") String brand,
      @JsonProperty("fetch") FetchSpec fetch,
      @JsonProperty("parser") String parser,
      @JsonProperty("notes") String notes) {
    return new Retailer(
        name,
        baseUrl,
        enabled == null || enabled,
        brandSource == null ? BrandSource.VENDOR : brandSource,
        brand,
        fetch,
        parser,
        notes);
  }
}
