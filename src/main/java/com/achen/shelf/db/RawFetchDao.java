package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Writes the {@code raw_fetches} audit log.
 *
 * <p>Append-only on purpose: a retry that failed twice before succeeding is three rows, because the
 * point of this table is to be able to reconstruct what the crawler actually did — including the
 * attempts that a "current state" table would hide.
 */
public final class RawFetchDao {

  private final Database db;

  public RawFetchDao(Database db) {
    this.db = db;
  }

  /** Records one HTTP attempt and where its body was stored. */
  public void record(String url, long crawlRunId, Integer status, String bodyRef)
      throws SQLException {
    try (Connection c = db.connection()) {
      record(c, url, crawlRunId, status, bodyRef);
    }
  }

  /** As above, on a caller-owned connection. */
  public void record(Connection c, String url, long crawlRunId, Integer status, String bodyRef)
      throws SQLException {
    String sql =
        "insert into raw_fetches (url, crawl_run_id, status, body_ref) values (?, ?, ?, ?)";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, url);
      ps.setLong(2, crawlRunId);
      if (status == null) {
        ps.setNull(3, Types.INTEGER);
      } else {
        ps.setInt(3, status);
      }
      if (bodyRef == null) {
        ps.setNull(4, Types.VARCHAR);
      } else {
        ps.setString(4, bodyRef);
      }
      ps.executeUpdate();
    }
  }
}
