package com.achen.shelf.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads and writes the canonical {@code products} catalog. */
public final class ProductDao {

  /** A catalog row. */
  public record Row(
      long id,
      String category,
      String brand,
      String model,
      String brandNorm,
      String modelNorm,
      String canonicalName,
      Map<String, Object> spec,
      List<String> aliases) {
    public Row {
      aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
  }

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
   * last_seen} is also the honest thing to record — we did just see it. The aliases are rewritten
   * from the seed on every bootstrap (M9): they are config, and an edit to the category file is
   * meant to take effect on the next pass without anything else.
   */
  public long upsert(
      String category,
      String brand,
      String model,
      String brandNorm,
      String modelNorm,
      String canonicalName,
      String specJson,
      List<String> aliases)
      throws SQLException {
    String sql =
        """
        insert into products
          (category, brand, model, brand_norm, model_norm, canonical_name, spec, aliases)
        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        on conflict (category, brand_norm, model_norm)
          do update set last_seen = now(), aliases = excluded.aliases
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
      ps.setArray(8, c.createArrayOf("text", aliases.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** The v1 signature: a product with no aliases. */
  public long upsert(
      String category,
      String brand,
      String model,
      String brandNorm,
      String modelNorm,
      String canonicalName,
      String specJson)
      throws SQLException {
    return upsert(category, brand, model, brandNorm, modelNorm, canonicalName, specJson, List.of());
  }

  /** Every product in a category, in id order. */
  public List<Row> list(String category) throws SQLException {
    String sql =
        """
        select id, category, brand, model, brand_norm, model_norm, canonical_name, spec::text,
               aliases
        from products where category = ? order by id
        """;
    try (Connection c = db.connection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, category);
      try (ResultSet rs = ps.executeQuery()) {
        List<Row> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(
              new Row(
                  rs.getLong(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getString(6),
                  rs.getString(7),
                  Jsonb.toMap(rs.getString(8)),
                  List.of((String[]) rs.getArray(9).getArray())));
        }
        return rows;
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
