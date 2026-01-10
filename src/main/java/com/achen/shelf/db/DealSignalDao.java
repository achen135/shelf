package com.achen.shelf.db;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code deal_signals}: one row per product holding the rule's call and the
 * reasons behind it.
 *
 * <p>The row is a function of the product's rollup row, so it is rewritten in place — the same
 * shape as {@code price_rollups}' product rows, one step further down the post-cycle chain.
 */
public final class DealSignalDao {

  /** One product's signal. {@code computedAt} is null on a row that has not been written yet. */
  public record Signal(
      long productId,
      String signal,
      List<String> reasonCodes,
      Long bestOfferId,
      Instant asOf,
      Instant computedAt) {
    public Signal {
      reasonCodes = List.copyOf(reasonCodes);
    }
  }

  private static final String SELECT =
      "select product_id, signal, reason_codes, best_offer_id, as_of, computed_at from deal_signals";

  private DealSignalDao() {}

  /** Writes every given signal, replacing the product's previous row. Returns rows written. */
  public static int upsert(Connection c, Collection<Signal> signals) throws SQLException {
    if (signals.isEmpty()) {
      return 0;
    }
    String sql =
        """
        insert into deal_signals (product_id, signal, reason_codes, best_offer_id, as_of, computed_at)
        values (?, ?, ?, ?, ?, now())
        on conflict (product_id) do update set
          signal = excluded.signal,
          reason_codes = excluded.reason_codes,
          best_offer_id = excluded.best_offer_id,
          as_of = excluded.as_of,
          computed_at = excluded.computed_at
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (Signal s : signals) {
        ps.setLong(1, s.productId());
        ps.setString(2, s.signal());
        ps.setArray(3, c.createArrayOf("text", s.reasonCodes().toArray()));
        if (s.bestOfferId() == null) {
          ps.setNull(4, Types.BIGINT);
        } else {
          ps.setLong(4, s.bestOfferId());
        }
        ps.setTimestamp(5, Timestamp.from(s.asOf()));
        ps.addBatch();
      }
      return Arrays.stream(ps.executeBatch()).sum();
    }
  }

  /** A product's signal, if one has been computed. */
  public static Optional<Signal> forProduct(Connection c, long productId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(SELECT + " where product_id = ?")) {
      ps.setLong(1, productId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(read(rs)) : Optional.empty();
      }
    }
  }

  /** Every signal in a category, by product id. */
  public static List<Signal> inCategory(Connection c, String category) throws SQLException {
    String sql =
        SELECT
            + " where product_id in (select id from products where category = ?)"
            + " order by product_id";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, category);
      try (ResultSet rs = ps.executeQuery()) {
        List<Signal> out = new ArrayList<>();
        while (rs.next()) {
          out.add(read(rs));
        }
        return out;
      }
    }
  }

  private static Signal read(ResultSet rs) throws SQLException {
    Array codes = rs.getArray(3);
    long bestOffer = rs.getLong(4);
    Long bestOfferOrNull = rs.wasNull() ? null : bestOffer;
    return new Signal(
        rs.getLong(1),
        rs.getString(2),
        codes == null ? List.of() : Arrays.asList((String[]) codes.getArray()),
        bestOfferOrNull,
        rs.getTimestamp(5).toInstant(),
        rs.getTimestamp(6).toInstant());
  }
}
