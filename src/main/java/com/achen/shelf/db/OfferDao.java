package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

  /** An offer the retirement pass just marked, with the product it is linked to, if any. */
  public record Retired(long offerId, Long productId) {}

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
   *
   * <p>Seeing an offer also revives it: {@code retired_at} is cleared, so a listing the rollup pass
   * had retired (M4) counts as current again from the cycle that finds it back.
   */
  public long upsert(Connection c, Listing listing) throws SQLException {
    String sql =
        """
        insert into offers (retailer, url, title, retailer_sku, currency, brand, brand_norm, spec)
        values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        on conflict (url) do update set
          last_seen    = now(),
          retired_at   = null,
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

  /**
   * Retires every live offer at the given retailers that has gone unseen for {@code missedCycles}
   * consecutive completed crawl cycles of the category — its {@code last_seen} is older than the
   * start of the {@code missedCycles}-th most recent finished crawl run. With fewer finished runs
   * than that, nothing retires: the rule needs that many cycles of evidence. Returns what it
   * marked.
   *
   * <p>The row and its observations stay; only {@code retired_at} is set, and the next crawl that
   * sees the listing clears it again ({@link #upsert}). Backfill runs are not cycles.
   */
  public static List<Retired> retireUnseen(
      Connection c, String category, Collection<String> retailers, int missedCycles, Instant asOf)
      throws SQLException {
    if (missedCycles < 1) {
      throw new IllegalArgumentException("missedCycles must be at least 1");
    }
    String sql =
        """
        with cutoff as (
          select started_at
          from crawl_runs
          where category = ? and kind = 'crawl' and finished_at is not null
          order by started_at desc
          offset ? limit 1
        )
        update offers o
        set retired_at = ?
        from cutoff
        where o.retailer = any (?) and o.retired_at is null and o.last_seen < cutoff.started_at
        returning o.id, o.product_id
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, category);
      ps.setInt(2, missedCycles - 1);
      ps.setTimestamp(3, Timestamp.from(asOf));
      ps.setArray(4, c.createArrayOf("text", retailers.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        List<Retired> out = new ArrayList<>();
        while (rs.next()) {
          long offerId = rs.getLong(1);
          long productId = rs.getLong(2);
          out.add(new Retired(offerId, rs.wasNull() ? null : productId));
        }
        return out;
      }
    }
  }
}
