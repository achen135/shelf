package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/** Which platform a community lives on; picks the ingester (M8). */
public enum CommunitySource {
  /** A subreddit, read through the Reddit Data API. */
  REDDIT,
  /** A channel, read through the YouTube Data API. */
  YOUTUBE;

  @JsonCreator
  public static CommunitySource fromYaml(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      // See FetchMode.fromYaml: no cause, so this message survives Jackson's rewrapping.
      throw new ConfigException(
          "unknown community source '" + raw + "' (expected `reddit` or `youtube`)");
    }
  }
}
