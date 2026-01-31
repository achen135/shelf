package com.achen.shelf.db;

import com.achen.shelf.ingest.Mention;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Writes {@code raw_mentions} (M8). */
public final class RawMentionDao {

  /** What one upsert batch did: how many rows were new and how many were re-sighted. */
  public record Written(int inserted, int refreshed) {
    public Written plus(Written other) {
      return new Written(inserted + other.inserted, refreshed + other.refreshed);
    }
  }

  private final Database db;

  public RawMentionDao(Database db) {
    this.db = db;
  }

  /**
   * Upserts a batch on a caller-owned connection.
   *
   * <p>On conflict the text and {@code fetched_at} are refreshed (an edited post or comment reads
   * as its latest wording; the 30-day clock restarts) and nothing else changes. {@code xmax = 0} is
   * Postgres's way of telling an inserted row from an updated one in the same statement, which is
   * what lets the summary say "12 new, 88 seen again" rather than just "100".
   */
  public Written upsert(Connection c, String category, List<Mention> mentions, Instant fetchedAt)
      throws SQLException {
    String sql =
        """
        insert into raw_mentions
          (category, source, source_id, community, parent_source_id, title, text, author_ref,
           posted_at, fetched_at, permalink)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        on conflict (source, source_id) do update
          set text = excluded.text,
              title = excluded.title,
              fetched_at = excluded.fetched_at
        returning (xmax = 0) as inserted
        """;
    int inserted = 0;
    int refreshed = 0;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (Mention m : mentions) {
        ps.setString(1, category);
        ps.setString(2, m.source().dbValue());
        ps.setString(3, m.sourceId());
        ps.setString(4, m.community());
        setNullable(ps, 5, m.parentSourceId());
        setNullable(ps, 6, m.title());
        ps.setString(7, m.text());
        setNullable(ps, 8, m.authorRef());
        if (m.postedAt() == null) {
          ps.setNull(9, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
          ps.setTimestamp(9, Timestamp.from(m.postedAt()));
        }
        ps.setTimestamp(10, Timestamp.from(fetchedAt));
        ps.setString(11, m.permalink());
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next() && rs.getBoolean(1)) {
            inserted++;
          } else {
            refreshed++;
          }
        }
      }
    }
    return new Written(inserted, refreshed);
  }

  /**
   * Deletes rows of the given sources not re-sighted within {@code maxAge} of {@code now} — the
   * "delete or refresh after 30 days" rule for API data, applied by the ingester after every run.
   */
  public int pruneStale(List<Mention.Source> sources, Duration maxAge, Instant now)
      throws SQLException {
    String sql = "delete from raw_mentions where source = any (?) and fetched_at < ?";
    try (Connection c = db.connection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setArray(
          1, c.createArrayOf("text", sources.stream().map(Mention.Source::dbValue).toArray()));
      ps.setTimestamp(2, Timestamp.from(now.minus(maxAge)));
      return ps.executeUpdate();
    }
  }

  /** How many rows a category holds, per source — what {@code shelf ingest} prints last. */
  public long count(String category) throws SQLException {
    try (Connection c = db.connection();
        PreparedStatement ps =
            c.prepareStatement("select count(*) from raw_mentions where category = ?")) {
      ps.setString(1, category);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static void setNullable(PreparedStatement ps, int index, String value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
  }
}
