package com.achen.shelf.ingest;

import com.achen.shelf.config.Community;
import com.achen.shelf.db.RawMentionDao;

/** One community's running tallies; a class rather than a record so the helpers can bump them. */
final class Counters {
  int items;
  int comments;
  int commentsUnavailable;
  int requests;
  int inserted;
  int refreshed;

  void write(RawMentionDao.Written w) {
    inserted += w.inserted();
    refreshed += w.refreshed();
  }

  IngestSummary.CommunitySummary summary(Community c, IngestSummary.Status status, String failure) {
    return new IngestSummary.CommunitySummary(
        c.name(),
        c.source(),
        status,
        items,
        comments,
        commentsUnavailable,
        requests,
        inserted,
        refreshed,
        failure);
  }
}
