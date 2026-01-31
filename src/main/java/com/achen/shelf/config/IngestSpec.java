package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How one community is read (M8). The counterpart of {@link FetchSpec} for a platform API.
 *
 * <p>There are no list paths here because the crawl surface is not the config author's to choose: a
 * subreddit has one listing of new posts and a channel one playlist of uploads, and the ingester
 * knows both. What the author does choose is how much of it to read and how fast.
 *
 * @param windowDays how far back to read; a post or video older than this is not fetched
 * @param maxItems most posts or videos read per community per run, the cap the window is under
 * @param commentsPerItem most top-level comments read per post or video; 0 reads none
 * @param maxRps ceiling on requests per second against the platform; Reddit's free tier is 100 a
 *     minute per client, so the shipped configs stay well under it
 * @param auth credentials, by environment variable name — never a value
 */
public record IngestSpec(
    int windowDays, int maxItems, int commentsPerItem, double maxRps, AuthSpec auth) {

  public IngestSpec {
    auth = auth == null ? AuthSpec.none() : auth;
  }

  @JsonCreator
  static IngestSpec fromYaml(
      @JsonProperty("window_days") Integer windowDays,
      @JsonProperty("max_items") Integer maxItems,
      @JsonProperty("comments_per_item") Integer commentsPerItem,
      @JsonProperty("max_rps") Double maxRps,
      @JsonProperty("auth") AuthSpec auth) {
    return new IngestSpec(
        windowDays == null ? 30 : windowDays,
        maxItems == null ? 100 : maxItems,
        commentsPerItem == null ? 100 : commentsPerItem,
        maxRps == null ? 1.0 : maxRps,
        auth);
  }
}
