package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The resolver's view of {@code offers}: what it reads to decide, where it writes decisions, the
 * review queue a human works, and the labeled pairs the eval joins against.
 *
 * <p>Every write is guarded by {@code resolution_status = 'pending'}: the resolver only ever moves
 * an offer out of pending, and only a human ({@link #accept}, {@link #reject}) moves it anywhere
 * else. A crawl never touches these columns at all ({@link OfferDao}). So a link, once made, is
 * changed by exactly one actor, and a human's decision outranks the machine's for good.
 */
public final class ResolutionDao {

  /** An offer as the resolver reads it. */
  public record PendingOffer(
      long id,
      String retailer,
      String url,
      String title,
      String brandNorm,
      Map<String, Object> spec) {}

  /** A row of the review queue. */
  public record ReviewItem(
      long offerId,
      String retailer,
      String title,
      String url,
      long candidateProductId,
      String candidateName,
      double score) {}

  /** A labeled pair, as stored. */
  public record Label(long offerId, long productId, boolean match) {}

  private final Database db;

  public ResolutionDao(Database db) {
    this.db = db;
  }

  /**
   * Every pending offer at the given retailers — the ones a category's resolver decides on. Offers
   * a crawl has not yet stamped a brand on are included: blocking falls back to the title.
   */
  public List<PendingOffer> pending(Connection c, Collection<String> retailers)
      throws SQLException {
    String sql =
        """
        select id, retailer, url, title, brand_norm, spec::text
        from offers
        where resolution_status = 'pending' and retailer = any (?)
        order by id
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setArray(1, c.createArrayOf("text", retailers.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        List<PendingOffer> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(
              new PendingOffer(
                  rs.getLong(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5),
                  Jsonb.toMap(rs.getString(6))));
        }
        return rows;
      }
    }
  }

  /** One offer by URL, whatever its status — how the eval finds a labeled listing. */
  public Optional<PendingOffer> byUrl(Connection c, String url) throws SQLException {
    String sql =
        "select id, retailer, url, title, brand_norm, spec::text from offers where url = ?";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, url);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new PendingOffer(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                Jsonb.toMap(rs.getString(6))));
      }
    }
  }

  /**
   * Links a pending offer: {@code auto}, with the score that did it. Returns false if not pending.
   */
  public boolean link(Connection c, long offerId, long productId, double score)
      throws SQLException {
    String sql =
        """
        update offers
        set product_id = ?, resolution_status = 'auto', resolution_score = ?,
            candidate_product_id = null, resolved_at = now()
        where id = ? and resolution_status = 'pending'
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, productId);
      ps.setDouble(2, score);
      ps.setLong(3, offerId);
      return ps.executeUpdate() == 1;
    }
  }

  /**
   * Records the resolver's best guess for a pending offer without linking — the review queue — or,
   * with a null candidate, records only the score. Returns false if the offer is not pending.
   */
  public boolean propose(Connection c, long offerId, Long candidateProductId, double score)
      throws SQLException {
    String sql =
        """
        update offers
        set candidate_product_id = ?, resolution_score = ?, resolved_at = now()
        where id = ? and resolution_status = 'pending'
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      if (candidateProductId == null) {
        ps.setNull(1, Types.BIGINT);
      } else {
        ps.setLong(1, candidateProductId);
      }
      ps.setDouble(2, score);
      ps.setLong(3, offerId);
      return ps.executeUpdate() == 1;
    }
  }

  /** The review queue: pending offers with a proposed product, best score first. */
  public List<ReviewItem> reviewQueue(Collection<String> retailers, int limit) throws SQLException {
    String sql =
        """
        select o.id, o.retailer, o.title, o.url, p.id, p.canonical_name, o.resolution_score
        from offers o join products p on p.id = o.candidate_product_id
        where o.resolution_status = 'pending' and o.retailer = any (?)
        order by o.resolution_score desc, o.id
        limit ?
        """;
    try (Connection c = db.connection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setArray(1, c.createArrayOf("text", retailers.toArray()));
      ps.setInt(2, limit);
      try (ResultSet rs = ps.executeQuery()) {
        List<ReviewItem> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(
              new ReviewItem(
                  rs.getLong(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getLong(5),
                  rs.getString(6),
                  rs.getDouble(7)));
        }
        return rows;
      }
    }
  }

  /** How many offers are waiting for a human at the given retailers. */
  public int reviewQueueSize(Connection c, Collection<String> retailers) throws SQLException {
    String sql =
        """
        select count(*) from offers
        where resolution_status = 'pending' and candidate_product_id is not null
          and retailer = any (?)
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setArray(1, c.createArrayOf("text", retailers.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  /**
   * A human confirms a link: {@code reviewed}. With a null product the proposed candidate is
   * accepted; the offer must be pending and, in that case, must have a proposal. Returns false if
   * nothing matched.
   */
  public boolean accept(Connection c, long offerId, Long productId) throws SQLException {
    String sql =
        """
        update offers
        set product_id = coalesce(?, candidate_product_id), resolution_status = 'reviewed',
            candidate_product_id = null, resolved_at = now()
        where id = ? and resolution_status = 'pending'
          and coalesce(?, candidate_product_id) is not null
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      if (productId == null) {
        ps.setNull(1, Types.BIGINT);
        ps.setNull(3, Types.BIGINT);
      } else {
        ps.setLong(1, productId);
        ps.setLong(3, productId);
      }
      ps.setLong(2, offerId);
      return ps.executeUpdate() == 1;
    }
  }

  /** A human says this listing is not a catalog product: {@code rejected}, never revisited. */
  public boolean reject(Connection c, long offerId) throws SQLException {
    String sql =
        """
        update offers
        set resolution_status = 'rejected', candidate_product_id = null, resolved_at = now()
        where id = ? and resolution_status = 'pending'
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, offerId);
      return ps.executeUpdate() == 1;
    }
  }

  /**
   * Returns every {@code auto} link at the given retailers to {@code pending}, so the next pass
   * decides them again — the operator's move after a scorer change. Human decisions ({@code
   * reviewed}, {@code rejected}) are not touched. Returns how many were reset.
   */
  public int resetAutoLinks(Connection c, Collection<String> retailers) throws SQLException {
    String sql =
        """
        update offers
        set product_id = null, resolution_status = 'pending', resolution_score = null,
            candidate_product_id = null, resolved_at = null
        where resolution_status = 'auto' and retailer = any (?)
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setArray(1, c.createArrayOf("text", retailers.toArray()));
      return ps.executeUpdate();
    }
  }

  /** The product an offer is linked to, if any. */
  public Optional<Long> linkedProduct(Connection c, long offerId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("select product_id from offers where id = ?")) {
      ps.setLong(1, offerId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        long id = rs.getLong(1);
        return rs.wasNull() ? Optional.empty() : Optional.of(id);
      }
    }
  }

  /**
   * For each product, the most common value of each spec field across its linked offers, as a JSON
   * object — the derived half of a product's canonical spec. Ties break on the value's text so the
   * result is deterministic. Products with no linked offers are absent from the result.
   */
  public Map<Long, Map<String, Object>> derivedSpecs(Connection c, Collection<Long> productIds)
      throws SQLException {
    String sql =
        """
        with vals as (
          select o.product_id, kv.key, kv.value
          from offers o, jsonb_each(o.spec) kv
          where o.product_id = any (?) and o.resolution_status in ('auto', 'reviewed')
        ), ranked as (
          select product_id, key, value,
                 row_number() over (partition by product_id, key
                                    order by count(*) desc, value::text) as rn
          from vals
          group by product_id, key, value
        )
        select product_id, jsonb_object_agg(key, value)::text
        from ranked where rn = 1
        group by product_id
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setArray(1, c.createArrayOf("bigint", productIds.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        Map<Long, Map<String, Object>> out = new java.util.HashMap<>();
        while (rs.next()) {
          out.put(rs.getLong(1), Jsonb.toMap(rs.getString(2)));
        }
        return out;
      }
    }
  }

  /** Replaces a product's canonical spec. */
  public void updateSpec(Connection c, long productId, Map<String, Object> spec)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("update products set spec = ?::jsonb where id = ?")) {
      ps.setString(1, Jsonb.fromMap(spec));
      ps.setLong(2, productId);
      ps.executeUpdate();
    }
  }

  /** Records a labeled pair, replacing any earlier label for the same pair. */
  public void upsertLabel(Connection c, long offerId, long productId, boolean match, String note)
      throws SQLException {
    String sql =
        """
        insert into resolution_labels (offer_id, product_id, match, note)
        values (?, ?, ?, ?)
        on conflict (offer_id, product_id)
          do update set match = excluded.match, note = excluded.note, labeled_at = now()
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, offerId);
      ps.setLong(2, productId);
      ps.setBoolean(3, match);
      if (note == null) {
        ps.setNull(4, Types.VARCHAR);
      } else {
        ps.setString(4, note);
      }
      ps.executeUpdate();
    }
  }

  /** Every stored label. */
  public List<Label> labels(Connection c) throws SQLException {
    try (PreparedStatement ps =
            c.prepareStatement(
                "select offer_id, product_id, match from resolution_labels order by id");
        ResultSet rs = ps.executeQuery()) {
      List<Label> out = new ArrayList<>();
      while (rs.next()) {
        out.add(new Label(rs.getLong(1), rs.getLong(2), rs.getBoolean(3)));
      }
      return out;
    }
  }
}
