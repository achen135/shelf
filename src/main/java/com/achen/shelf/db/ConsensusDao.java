package com.achen.shelf.db;

import com.achen.shelf.consensus.ConsensusRule;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the linked mentions a consensus is made of and writes {@code consensus_scores} (M10). */
public final class ConsensusDao {

  private ConsensusDao() {}

  /** Every product id in a category, in id order. */
  public static List<Long> productIds(Connection c, String category) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("select id from products where category = ? order by id")) {
      ps.setString(1, category);
      try (ResultSet rs = ps.executeQuery()) {
        List<Long> out = new ArrayList<>();
        while (rs.next()) {
          out.add(rs.getLong(1));
        }
        return out;
      }
    }
  }

  /**
   * The linked mentions of a category's products posted after {@code since}, grouped by product. A
   * raw mention with no posting time cannot be placed in a window and is left out.
   */
  public static Map<Long, List<ConsensusRule.Mention>> linkedMentions(
      Connection c, String category, Instant since, Map<String, Double> weights)
      throws SQLException {
    String sql =
        """
        select m.product_id, m.id, r.community, m.sentiment, r.posted_at
        from mentions m
        join raw_mentions r on r.id = m.raw_mention_id
        where r.category = ? and m.match_status = 'auto'
          and r.posted_at is not null and r.posted_at > ?
        order by m.product_id, r.posted_at desc, m.id
        """;
    Map<Long, List<ConsensusRule.Mention>> out = new LinkedHashMap<>();
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, category);
      ps.setTimestamp(2, Timestamp.from(since));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String community = rs.getString(3);
          out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
              .add(
                  new ConsensusRule.Mention(
                      rs.getLong(2),
                      community,
                      rs.getString(4),
                      weights.getOrDefault(community, 1.0),
                      rs.getTimestamp(5).toInstant()));
        }
      }
    }
    return out;
  }

  /** Writes one product's row, replacing what was there. */
  public static void upsert(
      Connection c, long productId, ConsensusRule.Consensus k, int windowDays, Instant asOf)
      throws SQLException {
    String sql =
        """
        insert into consensus_scores
          (product_id, score, mention_count, positive_count, negative_count, neutral_count,
           positive_share, source_diversity, window_days, quote_mention_ids, as_of, computed_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
        on conflict (product_id) do update set
          score = excluded.score, mention_count = excluded.mention_count,
          positive_count = excluded.positive_count, negative_count = excluded.negative_count,
          neutral_count = excluded.neutral_count, positive_share = excluded.positive_share,
          source_diversity = excluded.source_diversity, window_days = excluded.window_days,
          quote_mention_ids = excluded.quote_mention_ids, as_of = excluded.as_of,
          computed_at = now()
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, productId);
      if (k.score().isPresent()) {
        ps.setDouble(2, k.score().get());
      } else {
        ps.setNull(2, Types.DOUBLE);
      }
      ps.setInt(3, k.mentionCount());
      ps.setInt(4, k.positiveCount());
      ps.setInt(5, k.negativeCount());
      ps.setInt(6, k.neutralCount());
      if (k.positiveShare().isPresent()) {
        ps.setDouble(7, k.positiveShare().get());
      } else {
        ps.setNull(7, Types.DOUBLE);
      }
      ps.setInt(8, k.sourceDiversity());
      ps.setInt(9, windowDays);
      ps.setArray(10, c.createArrayOf("bigint", k.quoteMentionIds().toArray()));
      ps.setTimestamp(11, Timestamp.from(asOf));
      ps.executeUpdate();
    }
  }
}
