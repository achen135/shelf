package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/** Reads and writes {@code offers} — one row per SKU at a retailer, keyed by its URL. */
public final class OfferDao {

  private final Database db;

  public OfferDao(Database db) {
    this.db = db;
  }

  /**
   * Upserts an offer on its URL and returns its id.
   *
   * <p>The URL is the natural key: the same variant page is the same offer, run after run, which is
   * what keeps a second crawl from creating a duplicate. An existing offer keeps its {@code
   * product_id} — M1 only ever fills in a null one, so a link that M3 makes (or a human corrects in
   * the review queue) is never silently reverted by a later crawl.
   */
  public long upsert(
      String retailer,
      String url,
      String title,
      String retailerSku,
      String currency,
      Long productId)
      throws SQLException {
    try (Connection c = db.connection()) {
      return upsert(c, retailer, url, title, retailerSku, currency, productId);
    }
  }

  /**
   * As above, on a caller-owned connection — so a worker can write a page's offers, its
   * observations and its task's completion in one transaction (M2).
   */
  public long upsert(
      Connection c,
      String retailer,
      String url,
      String title,
      String retailerSku,
      String currency,
      Long productId)
      throws SQLException {
    String sql =
        """
        insert into offers (retailer, url, title, retailer_sku, currency, product_id,
                            resolution_status)
        values (?, ?, ?, ?, ?, ?, ?)
        on conflict (url) do update set
          last_seen    = now(),
          title        = excluded.title,
          retailer_sku = coalesce(excluded.retailer_sku, offers.retailer_sku),
          product_id   = coalesce(offers.product_id, excluded.product_id),
          resolution_status = case
            when offers.product_id is null and excluded.product_id is not null then 'auto'
            else offers.resolution_status
          end
        returning id
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, retailer);
      ps.setString(2, url);
      ps.setString(3, title);
      if (retailerSku == null) {
        ps.setNull(4, Types.VARCHAR);
      } else {
        ps.setString(4, retailerSku);
      }
      ps.setString(5, currency);
      if (productId == null) {
        ps.setNull(6, Types.BIGINT);
      } else {
        ps.setLong(6, productId);
      }
      ps.setString(7, productId == null ? "pending" : "auto");
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
