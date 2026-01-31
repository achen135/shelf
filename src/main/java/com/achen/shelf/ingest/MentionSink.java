package com.achen.shelf.ingest;

import com.achen.shelf.db.RawMentionDao;
import java.sql.SQLException;
import java.util.List;

/**
 * Where an ingester hands each page's mentions as soon as it has them.
 *
 * <p>A page at a time, not a run at a time, so that a run interrupted halfway has still written
 * what it read — the same reason {@code PageCrawler} writes per page. The runner's sink upserts one
 * batch in one transaction.
 */
@FunctionalInterface
public interface MentionSink {
  RawMentionDao.Written write(List<Mention> batch) throws SQLException;
}
