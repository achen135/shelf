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
 * @param weight how much a mention from here counts in the consensus score (M10); 1.0 is the
 *     default and what every shipped community carries — there is no measured basis yet to trust
 *     one channel over another, and the field exists so that a basis can be expressed as config
 *     when there is one
 * @param notes free text; kept in config so the reason a community is disabled travels with it
 */
public record Community(
    String name,
    CommunitySource source,
    String id,
    boolean enabled,
    IngestSpec ingest,
    double weight,
    String notes) {

  /** The M8 shape: weight 1.0. */
  public Community(
      String name,
      CommunitySource source,
      String id,
      boolean enabled,
      IngestSpec ingest,
      String notes) {
    this(name, source, id, enabled, ingest, 1.0, notes);
  }

  @JsonCreator
  static Community fromYaml(
      @JsonProperty("name") String name,
      @JsonProperty("source") CommunitySource source,
      @JsonProperty("id") String id,
      @JsonProperty("enabled") Boolean enabled,
      @JsonProperty("ingest") IngestSpec ingest,
      @JsonProperty("weight") Double weight,
      @JsonProperty("notes") String notes) {
    return new Community(
        name, source, id, enabled == null || enabled, ingest, weight == null ? 1.0 : weight, notes);
  }
}
