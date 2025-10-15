package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * How one retailer's listings are fetched.
 *
 * @param mode api (structured JSON) or html (a public page)
 * @param listPaths path templates relative to the retailer's base_url. {@code {page}} and {@code
 *     {limit}} are substituted per request. These are the ONLY paths the crawler visits for this
 *     retailer — nothing is discovered by following links off a fetched page.
 * @param pageSize items requested per page, substituted for {@code {limit}}
 * @param maxPages hard cap on pages fetched per list path per run
 * @param maxRps ceiling on requests per second for this retailer's domain
 * @param respectRobots must be true; present so the config states the posture explicitly
 * @param auth credentials, by environment variable name
 */
public record FetchSpec(
    FetchMode mode,
    List<String> listPaths,
    int pageSize,
    int maxPages,
    double maxRps,
    boolean respectRobots,
    AuthSpec auth) {

  public FetchSpec {
    listPaths = listPaths == null ? List.of() : List.copyOf(listPaths);
    auth = auth == null ? AuthSpec.none() : auth;
  }

  @JsonCreator
  static FetchSpec fromYaml(
      @JsonProperty("mode") FetchMode mode,
      @JsonProperty("list_paths") List<String> listPaths,
      @JsonProperty("page_size") Integer pageSize,
      @JsonProperty("max_pages") Integer maxPages,
      @JsonProperty("max_rps") Double maxRps,
      @JsonProperty("respect_robots") Boolean respectRobots,
      @JsonProperty("auth") AuthSpec auth) {
    return new FetchSpec(
        mode,
        listPaths,
        pageSize == null ? 250 : pageSize,
        maxPages == null ? 1 : maxPages,
        maxRps == null ? 0.2 : maxRps,
        respectRobots == null || respectRobots,
        auth);
  }
}
