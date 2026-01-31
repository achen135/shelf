package com.achen.shelf.ingest;

import java.time.Instant;
import java.util.Locale;

/**
 * One thing somebody said, as an ingester read it — a {@code raw_mentions} row before it is one.
 *
 * @param source which platform and which kind of thing
 * @param sourceId the platform's own stable id for it; with {@code source}, the idempotency key
 * @param community the config name of the community it was read from
 * @param parentSourceId for a comment, the post or video it is under; null for a post or video
 * @param title the post or video title; null for a comment
 * @param text the body — a post's self-text, a video's description, a comment's text
 * @param authorRef the platform's author reference, for attribution; null when unavailable
 * @param postedAt when it was posted, if the platform said
 * @param permalink where to read it in context
 */
public record Mention(
    Source source,
    String sourceId,
    String community,
    String parentSourceId,
    String title,
    String text,
    String authorRef,
    Instant postedAt,
    String permalink) {

  /** The four kinds of thing an ingester reads; the {@code raw_mentions.source} check. */
  public enum Source {
    REDDIT_POST,
    REDDIT_COMMENT,
    YOUTUBE_VIDEO,
    YOUTUBE_COMMENT;

    /** The value stored in {@code raw_mentions.source}. */
    public String dbValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public Mention {
    if (sourceId == null || sourceId.isBlank()) {
      throw new IllegalArgumentException("a mention needs a source id");
    }
    text = text == null ? "" : text;
  }
}
