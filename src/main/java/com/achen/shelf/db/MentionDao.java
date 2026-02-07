package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The mention resolver's view (M9): raw mentions to read, {@code mentions} rows to write, the
 * labels the eval joins against.
 */
public final class MentionDao {

  /** A raw mention as the matcher reads it. */
  public record Raw(
      long id, String source, String sourceId, String community, String title, String text) {
    /** Title and body as one text, the title first, a line break between. */
    public String fullText() {
      return title == null || title.isBlank() ? text : title + "\n" + text;
    }
  }

  /** One decided (raw mention, product) pair, ready to write. */
  public record Decision(
      long rawMentionId,
      long productId,
      double score,
      String status,
      List<String> reasons,
      String matchedText,
      String sentiment,
      Double sentimentScore,
      Integer explicitRank,
      String extractionMethod) {
    public Decision {
      reasons = List.copyOf(reasons);
    }
  }

  private final Database db;

  public MentionDao(Database db) {
    this.db = db;
  }

  /** Every raw mention in a category, on a pooled connection. */
  public List<Raw> raw(String category) throws SQLException {
    try (Connection c = db.connection()) {
      return raw(c, category);
    }
  }

  /** Every raw mention in a category, in id order. */
  public List<Raw> raw(Connection c, String category) throws SQLException {
    String sql =
        "select id, source, source_id, community, title, text from raw_mentions"
            + " where category = ? order by id";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, category);
      try (ResultSet rs = ps.executeQuery()) {
        List<Raw> out = new ArrayList<>();
        while (rs.next()) {
          out.add(
              new Raw(
                  rs.getLong(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getString(6)));
        }
        return out;
      }
    }
  }

  /** A raw mention by its platform identity — how a label names it. */
  public Optional<Raw> raw(Connection c, String source, String sourceId) throws SQLException {
    String sql =
        "select id, source, source_id, community, title, text from raw_mentions"
            + " where source = ? and source_id = ?";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, source);
      ps.setString(2, sourceId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Raw(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6)));
      }
    }
  }

  /**
   * Replaces the machine's rows for a category with this pass's decisions, leaving a human's
   * ({@code reviewed} / {@code rejected}) where they are.
   *
   * @return how many decisions were written; the rest met a human's row and were left out
   */
  public int replaceMachineRows(Connection c, String category, List<Decision> decisions)
      throws SQLException {
    String delete =
        "delete from mentions where match_status in ('auto', 'pending')"
            + " and raw_mention_id in (select id from raw_mentions where category = ?)";
    try (PreparedStatement ps = c.prepareStatement(delete)) {
      ps.setString(1, category);
      ps.executeUpdate();
    }
    String insert =
        """
        insert into mentions
          (raw_mention_id, product_id, match_score, match_status, match_reasons, matched_text,
           sentiment, sentiment_score, explicit_rank, extraction_method)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        on conflict (raw_mention_id, product_id) do nothing
        """;
    int written = 0;
    try (PreparedStatement ps = c.prepareStatement(insert)) {
      for (Decision d : decisions) {
        ps.setLong(1, d.rawMentionId());
        ps.setLong(2, d.productId());
        ps.setDouble(3, d.score());
        ps.setString(4, d.status());
        ps.setArray(5, c.createArrayOf("text", d.reasons().toArray()));
        ps.setString(6, d.matchedText());
        if (d.sentiment() == null) {
          ps.setNull(7, Types.VARCHAR);
        } else {
          ps.setString(7, d.sentiment());
        }
        if (d.sentimentScore() == null) {
          ps.setNull(8, Types.DOUBLE);
        } else {
          ps.setDouble(8, d.sentimentScore());
        }
        if (d.explicitRank() == null) {
          ps.setNull(9, Types.INTEGER);
        } else {
          ps.setInt(9, d.explicitRank());
        }
        ps.setString(10, d.extractionMethod());
        written += ps.executeUpdate();
      }
    }
    return written;
  }

  /** Records a labeled pair. */
  public void upsertLabel(
      Connection c, long rawMentionId, long productId, boolean match, String note)
      throws SQLException {
    String sql =
        """
        insert into mention_labels (raw_mention_id, product_id, match, note)
        values (?, ?, ?, ?)
        on conflict (raw_mention_id, product_id)
          do update set match = excluded.match, note = excluded.note, labeled_at = now()
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, rawMentionId);
      ps.setLong(2, productId);
      ps.setBoolean(3, match);
      if (note == null || note.isBlank()) {
        ps.setNull(4, Types.VARCHAR);
      } else {
        ps.setString(4, note);
      }
      ps.executeUpdate();
    }
  }

  /** How many {@code mentions} rows a category has in a status. */
  public long count(Connection c, String category, String status) throws SQLException {
    String sql =
        "select count(*) from mentions m join raw_mentions r on r.id = m.raw_mention_id"
            + " where r.category = ? and m.match_status = ?";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, category);
      ps.setString(2, status);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
