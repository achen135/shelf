package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Reads and writes the canonical {@code products} catalog. */
public final class ProductDao {

  private final Database db;

  public ProductDao(Database db) {
    this.db = db;
  }

  /**
   * Inserts a catalog row if (category, brand_norm, model_norm) is new, and returns its id either
   * way.
   *
   * <p>{@code on conflict … do update} rather than {@code do nothing}: the latter returns no row
   * when the product already exists, which would cost a second round trip per seed. Touching {@code
   * last_seen} is also the honest thing to record — we did just see it.
   */
  public long upsert(
      String category,
      String brand,
      String model,
      String brandNorm,
      String modelNorm,
      String canonicalName,
      String specJson)
      throws SQLException {
    String sql =
        """
        insert into products (category, brand, model, brand_norm, model_norm, canonical_name, spec)
        values (?, ?, ?, ?, ?, ?, ?::jsonb)
        on conflict (category, brand_norm, model_norm)
          do update set last_seen = now()
        returning id
        """;
    try (Connection c = db.connection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, category);
      ps.setString(2, brand);
      ps.setString(3, model);
      ps.setString(4, brandNorm);
      ps.setString(5, modelNorm);
      ps.setString(6, canonicalName);
      ps.setString(7, specJson);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** Looks up a product by its normalized identity. */
  public Optional<Long> findId(String category, String brandNorm, String modelNorm)
      throws SQLException {
    String sql = "select id from products where category = ? and brand_norm = ? and model_norm = ?";
    try (Connection c = db.connection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, category);
      ps.setString(2, brandNorm);
      ps.setString(3, modelNorm);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
      }
    }
  }
}
