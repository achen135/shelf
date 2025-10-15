package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** Reads and writes {@code crawl_runs}, the per-cycle bookkeeping row. */
public final class CrawlRunDao {

  private final Database db;

  public CrawlRunDao(Database db) {
    this.db = db;
  }

  /**
   * Opens a run and returns its id.
   *
   * <p>{@code started_at} is passed in rather than defaulted, because it doubles as the {@code
   * observed_at} of every observation in the run: one crawl cycle is one point in time, so a
   * repeated cycle cannot half-overwrite an earlier one.
   */
  public long open(String category, Instant startedAt, int workerCount) throws SQLException {
    String sql =
        "insert into crawl_runs (category, started_at, worker_count) values (?, ?, ?) returning id";
    try (Connection c = db.connection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, category);
      ps.setTimestamp(2, Timestamp.from(startedAt));
      ps.setInt(3, workerCount);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** Closes a run with its page and error counts. */
  public void finish(long runId, int pages, int errors, Instant finishedAt) throws SQLException {
    String sql = "update crawl_runs set finished_at = ?, pages = ?, errors = ? where id = ?";
    try (Connection c = db.connection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(finishedAt));
      ps.setInt(2, pages);
      ps.setInt(3, errors);
      ps.setLong(4, runId);
      ps.executeUpdate();
    }
  }
}
