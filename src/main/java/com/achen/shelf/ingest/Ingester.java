package com.achen.shelf.ingest;

import com.achen.shelf.config.Community;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

/**
 * Reads one community and hands what it read to a sink, page by page.
 *
 * <p>One implementation per {@link com.achen.shelf.config.CommunitySource}. An ingester owns the
 * platform's API shape — endpoints, pagination, what a post or a comment looks like — and nothing
 * about the database.
 */
public interface Ingester {

  /**
   * Reads a community.
   *
   * @param community what to read and how much
   * @param credentials the values of the community's {@code auth.env_vars}, by name; never logged
   * @param now the run's instant: the window is measured back from it
   * @param sink where each page's mentions go
   * @return what was read and written, for the summary
   */
  IngestSummary.CommunitySummary ingest(
      Community community, Map<String, String> credentials, Instant now, MentionSink sink)
      throws InterruptedException, SQLException;
}
