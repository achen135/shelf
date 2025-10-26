package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** Calls the {@code ensure_price_partition} helper from {@code V1__core.sql}. */
public final class PartitionDao {

  private final Database db;

  public PartitionDao(Database db) {
    this.db = db;
  }

  /**
   * Makes sure the monthly partition covering {@code when} exists, returning its name.
   *
   * <p>Called at the start of every crawl run for this month and next, so a run that straddles a
   * month boundary — or the first run after one — cannot fail on a missing partition. M4 decides
   * whether this stays a per-run call or moves to pg_partman.
   */
  public String ensurePartition(Instant when) throws SQLException {
    try (Connection c = db.connection()) {
      return ensurePartition(c, when);
    }
  }

  /** As above, on a caller-owned connection (the coordinator opens cycles on its lock session). */
  public static String ensurePartition(Connection c, Instant when) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("select ensure_price_partition(?)")) {
      ps.setTimestamp(1, Timestamp.from(when));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }
}
