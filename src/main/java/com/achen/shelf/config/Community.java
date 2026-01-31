package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One community within a category (M8): a subreddit or a YouTube channel whose posts, videos and
 * comments are worth reading for what people say about the category's products.
 *
 * <p>Same shape as {@link Retailer} on purpose — a stable name, the platform-specific id, an
 * enabled flag, a how-to-read block with credentials by env var name, and notes that travel with
 * the entry — so that the file a person hand-edits has one idiom, not two.
 *
 * @param name stable id, used as {@code raw_mentions.community} and in log lines
 * @param source which platform, and so which ingester
 * @param id the subreddit name (without {@code r/}) or the channel id ({@code UC…}) or handle
 *     ({@code @name})
 * @param enabled false keeps the entry (and its reason) in config without reading it
 * @param ingest how to read it
 * @param notes free text; kept in config so the reason a community is disabled travels with it
 */
public record Community(
    String name,
    CommunitySource source,
    String id,
    boolean enabled,
    IngestSpec ingest,
    String notes) {

  @JsonCreator
  static Community fromYaml(
      @JsonProperty("name") String name,
      @JsonProperty("source") CommunitySource source,
      @JsonProperty("id") String id,
      @JsonProperty("enabled") Boolean enabled,
      @JsonProperty("ingest") IngestSpec ingest,
      @JsonProperty("notes") String notes) {
    return new Community(name, source, id, enabled == null || enabled, ingest, notes);
  }
}
