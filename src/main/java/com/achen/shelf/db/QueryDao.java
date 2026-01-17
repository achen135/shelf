package com.achen.shelf.db;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The read side (M6): the queries behind {@code GET /products}, {@code /products/{id}} and {@code
 * /deals}. Everything a page shows comes from {@code price_rollups} and {@code deal_signals} — the
 * tables the post-cycle chain maintains — except a product's history, which is read from {@code
 * price_observations} for its live offers over a bounded window.
 *
 * <p><b>Spec filters read the listings, not the catalog.</b> {@code products.spec} is a per-field
 * mode over a product's offers (M3), so a GMMK 3 sold at 65/75/100% has one {@code layout_size}
 * there. A filter matches a product when some <em>live listing</em> of it — with the product's
 * summary filling in the fields the listing did not state — contains every requested value: {@code
 * (p.spec || o.spec) @> filters}. The same listing must also satisfy the price range and, by
 * default, be in stock, and it is the listing the row reports: "Keychron Q1 Pro, 75%, hot-swap —
 * $159 at keychron", not the product's cheapest colourway at $99.
 */
public final class QueryDao {

  /** How to order a search. */
  public enum Sort {
    /** Buy calls first, then by where today's price sits in the trailing year, then price. */
    DEAL("(signal = 'buy') desc, percentile_365d asc nulls last, price_cents asc, id"),
    PRICE("price_cents asc, id"),
    NAME("canonical_name asc, id");

    private final String orderBy;

    Sort(String orderBy) {
      this.orderBy = orderBy;
    }

    /** Parses a query-string value; null for anything unknown. */
    public static Sort parse(String value) {
      for (Sort s : values()) {
        if (s.name().equalsIgnoreCase(value)) {
          return s;
        }
      }
      return null;
    }

    public String lowerName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** A search: what to match and how to page it. Prices in cents. */
  public record Search(
      String category,
      int minPriceCents,
      int maxPriceCents,
      boolean inStockOnly,
      Map<String, Object> specFilters,
      String signal,
      Sort sort,
      int limit,
      int offset) {
    public Search {
      specFilters = Map.copyOf(specFilters);
    }

    /** The same search over another category. */
    public Search withCategory(String other) {
      return new Search(
          other,
          minPriceCents,
          maxPriceCents,
          inStockOnly,
          specFilters,
          signal,
          sort,
          limit,
          offset);
    }
  }

  /** The catalog row. */
  public record Product(
      long id, String category, String brand, String model, String name, Map<String, Object> spec) {
    public Product {
      spec = Map.copyOf(spec);
    }
  }

  /** A product's price picture, from its {@code price_rollups} row. */
  public record Price(
      Integer currentPriceCents,
      Boolean currentInStock,
      Long currentOfferId,
      Integer listPriceCents,
      Double percentile365d,
      Integer min30d,
      Integer min365d,
      Integer median365d,
      Integer max365d,
      int observations365d,
      int synthetic365d,
      int saleDays365d,
      Instant asOf) {}

  /** A product's call, from {@code deal_signals}; null when none has been computed. */
  public record Signal(String signal, List<String> reasonCodes, Long bestOfferId, Instant asOf) {
    public Signal {
      reasonCodes = List.copyOf(reasonCodes);
    }
  }

  /** One listing with its own current price. */
  public record Offer(
      long id,
      String retailer,
      String title,
      String url,
      Map<String, Object> spec,
      Integer priceCents,
      Boolean inStock,
      Instant observedAt,
      Integer listPriceCents,
      Double percentile365d,
      Integer min365d,
      int observations365d,
      int synthetic365d) {
    public Offer {
      spec = Map.copyOf(spec);
    }
  }

  /** One search result: the product, its price and call, and the listing that matched. */
  public record Hit(Product product, Price price, Signal signal, Offer offer) {}

  /** A page of hits with the total the search would return unpaged. */
  public record Page(List<Hit> hits, long total) {
    public Page {
      hits = List.copyOf(hits);
    }
  }

  /** One product with everything the detail page shows. */
  public record Detail(Product product, Price price, Signal signal, List<Offer> offers) {
    public Detail {
      offers = List.copyOf(offers);
    }
  }

  /** One day of a product's history: the cheapest in-stock live listing that day. */
  public record Day(LocalDate day, Integer priceCents, boolean synthetic, int observations) {}

  private static final String PRICE_COLUMNS =
      """
      r.current_price_cents, r.current_in_stock, r.current_offer_id, r.list_price_cents,
      r.percentile_365d, r.min_30d, r.min_365d, r.median_365d, r.max_365d,
      r.observations_365d, r.synthetic_365d, r.sale_days_365d, r.as_of
      """;

  private static final String SIGNAL_COLUMNS =
      "s.signal, s.reason_codes, s.best_offer_id, s.as_of as signal_as_of";

  private static final String OFFER_COLUMNS =
      """
      o.id, o.retailer, o.title, o.url, o.spec::text,
      ro.current_price_cents, ro.current_in_stock, ro.current_observed_at, ro.list_price_cents,
      ro.percentile_365d, ro.min_365d, ro.observations_365d, ro.synthetic_365d
      """;

  /**
   * The search. Parameters: in_stock_only, min, max, filters (jsonb), category, signal ×2, limit,
   * offset. {@code count(*) over ()} carries the unpaged total on every row.
   */
  private static final String SEARCH =
      """
      with hits as (
        select p.id, p.category, p.brand, p.model, p.canonical_name, p.spec::text as product_spec,
      """
          + PRICE_COLUMNS
          + ", "
          + SIGNAL_COLUMNS
          + """
      ,
               m.offer_id, m.retailer, m.title, m.url, m.offer_spec, m.price_cents, m.in_stock,
               m.observed_at, m.offer_list_price_cents, m.offer_percentile_365d, m.offer_min_365d,
               m.offer_observations_365d, m.offer_synthetic_365d,
               count(*) over () as total
        from products p
        join price_rollups r on r.product_id = p.id
        left join deal_signals s on s.product_id = p.id
        cross join lateral (
          select o.id as offer_id, o.retailer, o.title, o.url, o.spec::text as offer_spec,
                 ro.current_price_cents as price_cents, ro.current_in_stock as in_stock,
                 ro.current_observed_at as observed_at, ro.list_price_cents as offer_list_price_cents,
                 ro.percentile_365d as offer_percentile_365d, ro.min_365d as offer_min_365d,
                 ro.observations_365d as offer_observations_365d,
                 ro.synthetic_365d as offer_synthetic_365d
          from offers o
          join price_rollups ro on ro.offer_id = o.id
          where o.product_id = p.id
            and o.resolution_status in ('auto', 'reviewed') and o.retired_at is null
            and ro.current_price_cents is not null
            and (ro.current_in_stock or not ?)
            and ro.current_price_cents between ? and ?
            and (p.spec || o.spec) @> ?::jsonb
          order by ro.current_in_stock desc, ro.current_price_cents, o.id
          limit 1
        ) m
        where p.category = ?
          and (?::text is null or s.signal = ?::text)
      )
      select * from hits
      order by @order
      limit ? offset ?
      """;

  private QueryDao() {}

  /** Runs a search: one row per matching product, paged, with the unpaged total. */
  public static Page search(Connection c, Search q) throws SQLException {
    String sql = SEARCH.replace("@order", q.sort().orderBy);
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      int i = 1;
      ps.setBoolean(i++, q.inStockOnly());
      ps.setInt(i++, q.minPriceCents());
      ps.setInt(i++, q.maxPriceCents());
      ps.setString(i++, Jsonb.fromMap(q.specFilters()));
      ps.setString(i++, q.category());
      if (q.signal() == null) {
        ps.setNull(i++, Types.VARCHAR);
        ps.setNull(i++, Types.VARCHAR);
      } else {
        ps.setString(i++, q.signal());
        ps.setString(i++, q.signal());
      }
      ps.setInt(i++, q.limit());
      ps.setInt(i++, q.offset());
      try (ResultSet rs = ps.executeQuery()) {
        List<Hit> hits = new ArrayList<>();
        long total = 0;
        while (rs.next()) {
          int k = 1;
          Product product =
              new Product(
                  rs.getLong(k++),
                  rs.getString(k++),
                  rs.getString(k++),
                  rs.getString(k++),
                  rs.getString(k++),
                  Jsonb.toMap(rs.getString(k++)));
          Price price = price(rs, k);
          k += 13;
          Signal signal = signal(rs, k);
          k += 4;
          Offer offer =
              new Offer(
                  rs.getLong(k++),
                  rs.getString(k++),
                  rs.getString(k++),
                  rs.getString(k++),
                  Jsonb.toMap(rs.getString(k++)),
                  intOrNull(rs, k++),
                  boolOrNull(rs, k++),
                  instantOrNull(rs, k++),
                  intOrNull(rs, k++),
                  doubleOrNull(rs, k++),
                  intOrNull(rs, k++),
                  rs.getInt(k++),
                  rs.getInt(k++));
          total = rs.getLong(k);
          hits.add(new Hit(product, price, signal, offer));
        }
        return new Page(hits, total);
      }
    }
  }

  /** One product with its price, call and live listings; empty if there is no such product. */
  public static Optional<Detail> product(Connection c, long productId) throws SQLException {
    String sql =
        """
        select p.id, p.category, p.brand, p.model, p.canonical_name, p.spec::text,
        """
            + PRICE_COLUMNS
            + ", "
            + SIGNAL_COLUMNS
            + """

        from products p
        left join price_rollups r on r.product_id = p.id
        left join deal_signals s on s.product_id = p.id
        where p.id = ?
        """;
    Product product;
    Price price;
    Signal signal;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, productId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        int k = 1;
        product =
            new Product(
                rs.getLong(k++),
                rs.getString(k++),
                rs.getString(k++),
                rs.getString(k++),
                rs.getString(k++),
                Jsonb.toMap(rs.getString(k++)));
        price = rs.getTimestamp(k + 12) == null ? null : price(rs, k);
        k += 13;
        signal = signal(rs, k);
      }
    }
    return Optional.of(new Detail(product, price, signal, offers(c, productId)));
  }

  /** A product's live linked listings, cheapest in-stock first. */
  static List<Offer> offers(Connection c, long productId) throws SQLException {
    String sql =
        "select "
            + OFFER_COLUMNS
            + """

        from offers o
        left join price_rollups ro on ro.offer_id = o.id
        where o.product_id = ?
          and o.resolution_status in ('auto', 'reviewed') and o.retired_at is null
        order by ro.current_in_stock desc nulls last, ro.current_price_cents asc nulls last, o.id
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, productId);
      try (ResultSet rs = ps.executeQuery()) {
        List<Offer> out = new ArrayList<>();
        while (rs.next()) {
          int k = 1;
          out.add(
              new Offer(
                  rs.getLong(k++),
                  rs.getString(k++),
                  rs.getString(k++),
                  rs.getString(k++),
                  Jsonb.toMap(rs.getString(k++)),
                  intOrNull(rs, k++),
                  boolOrNull(rs, k++),
                  instantOrNull(rs, k++),
                  intOrNull(rs, k++),
                  doubleOrNull(rs, k++),
                  intOrNull(rs, k++),
                  rs.getInt(k++),
                  rs.getInt(k++)));
        }
        return out;
      }
    }
  }

  /**
   * A product's daily history over the trailing {@code days}: the cheapest in-stock price among its
   * live listings each day (null on a day nothing was in stock), whether any of it is synthetic,
   * and how many observations the day holds. Bounded below, so the planner prunes to the partitions
   * the window can touch; per offer it is a primary-key range scan.
   */
  public static List<Day> history(Connection c, long productId, int days, Instant now)
      throws SQLException {
    String sql =
        """
        select (observed_at at time zone 'UTC')::date as day,
               min(price_cents) filter (where in_stock) as price_cents,
               bool_or(source = 'synthetic') as synthetic,
               count(*) as observations
        from price_observations
        where offer_id = any (select id from offers
                              where product_id = ?
                                and resolution_status in ('auto', 'reviewed') and retired_at is null)
          and observed_at > ? and observed_at <= ?
        group by 1
        order by 1
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, productId);
      ps.setTimestamp(2, Timestamp.from(now.minusSeconds(86400L * days)));
      ps.setTimestamp(3, Timestamp.from(now));
      try (ResultSet rs = ps.executeQuery()) {
        List<Day> out = new ArrayList<>();
        while (rs.next()) {
          out.add(
              new Day(
                  rs.getDate(1).toLocalDate(), intOrNull(rs, 2), rs.getBoolean(3), rs.getInt(4)));
        }
        return out;
      }
    }
  }

  private static Price price(ResultSet rs, int k) throws SQLException {
    return new Price(
        intOrNull(rs, k),
        boolOrNull(rs, k + 1),
        longOrNull(rs, k + 2),
        intOrNull(rs, k + 3),
        doubleOrNull(rs, k + 4),
        intOrNull(rs, k + 5),
        intOrNull(rs, k + 6),
        intOrNull(rs, k + 7),
        intOrNull(rs, k + 8),
        rs.getInt(k + 9),
        rs.getInt(k + 10),
        rs.getInt(k + 11),
        rs.getTimestamp(k + 12).toInstant());
  }

  private static Signal signal(ResultSet rs, int k) throws SQLException {
    String signal = rs.getString(k);
    if (signal == null) {
      return null;
    }
    Array codes = rs.getArray(k + 1);
    Long bestOffer = longOrNull(rs, k + 2);
    return new Signal(
        signal,
        codes == null ? List.of() : Arrays.asList((String[]) codes.getArray()),
        bestOffer,
        rs.getTimestamp(k + 3).toInstant());
  }

  private static Long longOrNull(ResultSet rs, int i) throws SQLException {
    long v = rs.getLong(i);
    return rs.wasNull() ? null : v;
  }

  private static Integer intOrNull(ResultSet rs, int i) throws SQLException {
    int v = rs.getInt(i);
    return rs.wasNull() ? null : v;
  }

  private static Double doubleOrNull(ResultSet rs, int i) throws SQLException {
    double v = rs.getDouble(i);
    return rs.wasNull() ? null : v;
  }

  private static Boolean boolOrNull(ResultSet rs, int i) throws SQLException {
    boolean v = rs.getBoolean(i);
    return rs.wasNull() ? null : v;
  }

  private static Instant instantOrNull(ResultSet rs, int i) throws SQLException {
    Timestamp t = rs.getTimestamp(i);
    return t == null ? null : t.toInstant();
  }
}
