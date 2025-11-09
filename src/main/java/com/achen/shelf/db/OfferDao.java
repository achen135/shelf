package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/** Reads and writes {@code offers} — one row per SKU at a retailer, keyed by its URL. */
public final class OfferDao {

  /** What the crawl knows about a listing: the facts resolution later scores. */
  public record Listing(
      String retailer,
      String url,
      String title,
      String retailerSku,
      String currency,
      String brand,
      String brandNorm,
      String specJson) {}

  private final Database db;

  public OfferDao(Database db) {
    this.db = db;
  }

  /** Upserts an offer on its URL and returns its id, on a pooled connection. */
  public long upsert(Listing listing) throws SQLException {
    try (Connection c = db.connection()) {
      return upsert(c, listing);
    }
  }

  /**
   * Upserts an offer on its URL and returns its id, on a caller-owned connection — so a worker can
   * write a page's offers, its observations and its task's completion in one transaction (M2).
   *
   * <p>The URL is the natural key: the same variant page is the same offer, run after run, which is
   * what keeps a second crawl from creating a duplicate. The crawl records what the listing says —
   * title, brand, spec — and nothing about which product it is: {@code product_id}, {@code
   * resolution_status} and the resolver's columns are never touched here, so a link the resolver
   * made or a human corrected survives every later crawl.
   */
  public long upsert(Connection c, Listing listing) throws SQLException {
    String sql =
        """
        insert into offers (retailer, url, title, retailer_sku, currency, brand, brand_norm, spec)
        values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        on conflict (url) do update set
          last_seen    = now(),
          title        = excluded.title,
          retailer_sku = coalesce(excluded.retailer_sku, offers.retailer_sku),
          brand        = excluded.brand,
          brand_norm   = excluded.brand_norm,
          spec         = excluded.spec
        returning id
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, listing.retailer());
      ps.setString(2, listing.url());
      ps.setString(3, listing.title());
      if (listing.retailerSku() == null) {
        ps.setNull(4, Types.VARCHAR);
      } else {
        ps.setString(4, listing.retailerSku());
      }
      ps.setString(5, listing.currency());
      ps.setString(6, listing.brand());
      ps.setString(7, listing.brandNorm());
      ps.setString(8, listing.specJson());
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
