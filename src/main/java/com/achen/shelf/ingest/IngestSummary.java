package com.achen.shelf.ingest;

import com.achen.shelf.config.CommunitySource;
import java.time.Duration;
import java.util.List;

/**
 * What one {@code shelf ingest} run did, per community and in total — returned rather than only
 * logged so the CLI can print it and the tests can assert on it.
 *
 * @param category the category ingested
 * @param communities one entry per configured, enabled community, skipped ones included
 * @param pruned {@code youtube_*} rows deleted for going 30 days without a re-sighting
 * @param rowsInCategory {@code raw_mentions} rows the category holds after the run
 * @param took wall clock
 */
public record IngestSummary(
    String category,
    List<CommunitySummary> communities,
    int pruned,
    long rowsInCategory,
    Duration took) {

  public IngestSummary {
    communities = List.copyOf(communities);
  }

  /** Why a community's line reads the way it does. */
  public enum Status {
    /** Read to the end of its window, or its cap. */
    OK,
    /** Its credentials' environment variables are unset; nothing was requested. */
    NO_CREDENTIALS,
    /** A request the ingester could not do without failed; whatever came before it was written. */
    FAILED
  }

  /**
   * One community's contribution.
   *
   * @param community config name
   * @param source which platform
   * @param status see {@link Status}
   * @param items posts or videos read
   * @param comments comments read
   * @param commentsUnavailable posts or videos whose comments the platform would not serve
   * @param requests HTTP requests made
   * @param inserted rows new to {@code raw_mentions}
   * @param refreshed rows already there, re-sighted
   * @param failure what went wrong, when the status is FAILED
   */
  public record CommunitySummary(
      String community,
      CommunitySource source,
      Status status,
      int items,
      int comments,
      int commentsUnavailable,
      int requests,
      int inserted,
      int refreshed,
      String failure) {

    static CommunitySummary skipped(String community, CommunitySource source, String why) {
      return new CommunitySummary(community, source, Status.NO_CREDENTIALS, 0, 0, 0, 0, 0, 0, why);
    }
  }

  public int totalInserted() {
    return communities.stream().mapToInt(CommunitySummary::inserted).sum();
  }

  public int totalRefreshed() {
    return communities.stream().mapToInt(CommunitySummary::refreshed).sum();
  }

  public int totalItems() {
    return communities.stream().mapToInt(CommunitySummary::items).sum();
  }

  public int totalComments() {
    return communities.stream().mapToInt(CommunitySummary::comments).sum();
  }

  public long failures() {
    return communities.stream().filter(c -> c.status() == Status.FAILED).count();
  }
}
