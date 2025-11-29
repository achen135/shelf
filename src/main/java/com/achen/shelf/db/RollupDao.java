package com.achen.shelf.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Computes and reads {@code price_rollups}: trailing-window statistics per offer and per product.
 *
 * <p>Each grain is one SQL statement over {@code price_observations} that ends in an upsert, so a
 * rollup row is a pure function of the observations and the instant it was computed at — recompute
 * it and you get the same row. The two grains differ only in what their <em>series</em> is:
 *
 * <ul>
 *   <li>an <b>offer</b>'s series is its own observations, and its current price is its latest one;
 *   <li>a <b>product</b>'s series is, at each instant, the cheapest in-stock observation among its
 *       live linked offers — what a shopper could have paid — and its current price is the cheapest
 *       latest observation among those offers, in-stock ones first, with {@code current_in_stock}
 *       saying whether that price is buyable.
 * </ul>
 *
 * <p>Everything after the series is shared: the list price is the year's most common price (ties to
 * the higher), a sale is a run of consecutive observations at or below 90% of it, and the windows,
 * percentile and volatility are plain aggregates over the year. Every row asked for gets written,
 * including one with no observations in the window — a product whose last live offer retired must
 * not keep a stale row.
 */
public final class RollupDao {

  /** min / median / max over one trailing window; all null when the window holds nothing. */
  public record Window(Integer min, Integer median, Integer max) {}

  /** One detected sale: consecutive observations at or below 90% of the list price. */
  public record SaleWindow(Instant start, Instant end, int minPriceCents, int offPct, int days) {}

  /** A rollup row, either grain. */
  public record Rollup(
      Long offerId,
      Long productId,
      Instant asOf,
      Integer currentPriceCents,
      Instant currentObservedAt,
      Boolean currentInStock,
      Long currentOfferId,
      Integer listPriceCents,
      Window d7,
      Window d30,
      Window d90,
      Window d365,
      Double percentile365d,
      Double volatility365d,
      int observations365d,
      int synthetic365d,
      List<SaleWindow> saleWindows,
      int saleDays365d,
      Instant lastSaleEndedAt,
      Instant computedAt) {
    public Rollup {
      saleWindows = List.copyOf(saleWindows);
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The offer grain's series and current price. Parameters: as_of, then the offer ids. */
  private static final String OFFER_HEAD =
      """
      with params as (
        select ?::timestamptz as as_of
      ), keys as (
        select unnest(?::bigint[]) as key
      ), series as (
        select po.offer_id as key, po.observed_at, po.price_cents, po.in_stock,
               (po.source = 'synthetic') as synthetic
        from price_observations po
        cross join params p
        where po.offer_id = any (select key from keys)
          and po.observed_at > p.as_of - interval '365 days' and po.observed_at <= p.as_of
      ), current as (
        select distinct on (key) key, price_cents, observed_at, in_stock, null::bigint as offer_id
        from series
        order by key, observed_at desc
      ),
      """;

  /** The product grain's series and current price. Parameters: as_of, then the product ids. */
  private static final String PRODUCT_HEAD =
      """
      with params as (
        select ?::timestamptz as as_of
      ), keys as (
        select unnest(?::bigint[]) as key
      ), live as (
        select o.id as offer_id, o.product_id as key
        from offers o
        where o.product_id = any (select key from keys)
          and o.resolution_status in ('auto', 'reviewed') and o.retired_at is null
      ), obs as (
        select l.key, l.offer_id, po.observed_at, po.price_cents, po.in_stock,
               (po.source = 'synthetic') as synthetic
        from price_observations po
        join live l on l.offer_id = po.offer_id
        cross join params p
        where po.observed_at > p.as_of - interval '365 days' and po.observed_at <= p.as_of
      ), series as (
        select key, observed_at, min(price_cents) as price_cents, true as in_stock,
               bool_or(synthetic) as synthetic
        from obs
        where in_stock
        group by key, observed_at
      ), latest_per_offer as (
        select distinct on (offer_id) key, offer_id, price_cents, observed_at, in_stock
        from obs
        order by offer_id, observed_at desc
      ), current as (
        select distinct on (key) key, price_cents, observed_at, in_stock, offer_id
        from latest_per_offer
        order by key, in_stock desc, price_cents, observed_at desc, offer_id
      ),
      """;

  /** Everything after the series: list price, sales, windows, and the upsert. */
  private static final String TAIL =
      """
      listp as (
        select key, mode() within group (order by price_cents desc) as list_price_cents
        from series
        group by key
      ), flagged as (
        select s.key, s.observed_at, s.price_cents, s.synthetic,
               (s.price_cents * 10 <= l.list_price_cents * 9) as is_sale
        from series s
        join listp l using (key)
      ), stepped as (
        select *, coalesce(lag(is_sale) over (partition by key order by observed_at), false) as was_sale
        from flagged
      ), islands as (
        select *, sum(case when is_sale and not was_sale then 1 else 0 end)
                    over (partition by key order by observed_at) as sale_no
        from stepped
      ), sales as (
        select key, sale_no, min(observed_at) as start_at, max(observed_at) as end_at,
               min(price_cents) as min_price_cents,
               count(distinct (observed_at at time zone 'UTC')::date) as days
        from islands
        where is_sale
        group by key, sale_no
      ), sale_agg as (
        select s.key,
               jsonb_agg(jsonb_build_object(
                 'start', s.start_at, 'end', s.end_at, 'min_price_cents', s.min_price_cents,
                 'off_pct', round(100.0 * (1 - s.min_price_cents::numeric / l.list_price_cents)),
                 'days', s.days) order by s.start_at desc) as sale_windows,
               sum(s.days) as sale_days,
               max(s.end_at) as last_sale_ended_at
        from sales s
        join listp l using (key)
        group by s.key
      ), agg as (
        select s.key,
               min(s.price_cents) filter (where s.observed_at > p.as_of - interval '7 days') as min_7d,
               percentile_cont(0.5) within group (order by s.price_cents)
                 filter (where s.observed_at > p.as_of - interval '7 days') as median_7d,
               max(s.price_cents) filter (where s.observed_at > p.as_of - interval '7 days') as max_7d,
               min(s.price_cents) filter (where s.observed_at > p.as_of - interval '30 days') as min_30d,
               percentile_cont(0.5) within group (order by s.price_cents)
                 filter (where s.observed_at > p.as_of - interval '30 days') as median_30d,
               max(s.price_cents) filter (where s.observed_at > p.as_of - interval '30 days') as max_30d,
               min(s.price_cents) filter (where s.observed_at > p.as_of - interval '90 days') as min_90d,
               percentile_cont(0.5) within group (order by s.price_cents)
                 filter (where s.observed_at > p.as_of - interval '90 days') as median_90d,
               max(s.price_cents) filter (where s.observed_at > p.as_of - interval '90 days') as max_90d,
               min(s.price_cents) as min_365d,
               percentile_cont(0.5) within group (order by s.price_cents) as median_365d,
               max(s.price_cents) as max_365d,
               case when max(c.price_cents) is null then null
                    else count(*) filter (where s.price_cents < c.price_cents)::double precision / count(*)
               end as percentile_365d,
               stddev_samp(s.price_cents) / nullif(avg(s.price_cents), 0) as volatility_365d,
               count(*) as observations_365d,
               count(*) filter (where s.synthetic) as synthetic_365d
        from series s
        cross join params p
        left join current c on c.key = s.key
        group by s.key
      )
      insert into price_rollups (
        offer_id, product_id, as_of,
        current_price_cents, current_observed_at, current_in_stock, current_offer_id, list_price_cents,
        min_7d, median_7d, max_7d, min_30d, median_30d, max_30d,
        min_90d, median_90d, max_90d, min_365d, median_365d, max_365d,
        percentile_365d, volatility_365d, observations_365d, synthetic_365d,
        sale_windows, sale_days_365d, last_sale_ended_at, computed_at)
      select @columns, p.as_of,
             c.price_cents, c.observed_at, c.in_stock, c.offer_id, l.list_price_cents,
             a.min_7d, round(a.median_7d)::int, a.max_7d,
             a.min_30d, round(a.median_30d)::int, a.max_30d,
             a.min_90d, round(a.median_90d)::int, a.max_90d,
             a.min_365d, round(a.median_365d)::int, a.max_365d,
             a.percentile_365d, a.volatility_365d,
             coalesce(a.observations_365d, 0), coalesce(a.synthetic_365d, 0),
             coalesce(sa.sale_windows, '[]'::jsonb), coalesce(sa.sale_days, 0), sa.last_sale_ended_at,
             now()
      from keys k
      cross join params p
      left join current c on c.key = k.key
      left join listp l on l.key = k.key
      left join agg a on a.key = k.key
      left join sale_agg sa on sa.key = k.key
      on conflict (@key) where @key is not null do update set
        as_of = excluded.as_of,
        current_price_cents = excluded.current_price_cents,
        current_observed_at = excluded.current_observed_at,
        current_in_stock = excluded.current_in_stock,
        current_offer_id = excluded.current_offer_id,
        list_price_cents = excluded.list_price_cents,
        min_7d = excluded.min_7d, median_7d = excluded.median_7d, max_7d = excluded.max_7d,
        min_30d = excluded.min_30d, median_30d = excluded.median_30d, max_30d = excluded.max_30d,
        min_90d = excluded.min_90d, median_90d = excluded.median_90d, max_90d = excluded.max_90d,
        min_365d = excluded.min_365d, median_365d = excluded.median_365d, max_365d = excluded.max_365d,
        percentile_365d = excluded.percentile_365d,
        volatility_365d = excluded.volatility_365d,
        observations_365d = excluded.observations_365d,
        synthetic_365d = excluded.synthetic_365d,
        sale_windows = excluded.sale_windows,
        sale_days_365d = excluded.sale_days_365d,
        last_sale_ended_at = excluded.last_sale_ended_at,
        computed_at = excluded.computed_at
      """;

  private static final String OFFER_SQL =
      OFFER_HEAD + TAIL.replace("@columns", "k.key, null::bigint").replace("@key", "offer_id");
  private static final String PRODUCT_SQL =
      PRODUCT_HEAD + TAIL.replace("@columns", "null::bigint, k.key").replace("@key", "product_id");

  private static final String SELECT =
      """
      select offer_id, product_id, as_of,
             current_price_cents, current_observed_at, current_in_stock, current_offer_id,
             list_price_cents,
             min_7d, median_7d, max_7d, min_30d, median_30d, max_30d,
             min_90d, median_90d, max_90d, min_365d, median_365d, max_365d,
             percentile_365d, volatility_365d, observations_365d, synthetic_365d,
             sale_windows::text, sale_days_365d, last_sale_ended_at, computed_at
      from price_rollups
      """;

  private RollupDao() {}

  /** Recomputes the rollup row of each given offer as of {@code asOf}. Returns rows written. */
  public static int recomputeOffers(Connection c, Collection<Long> offerIds, Instant asOf)
      throws SQLException {
    return recompute(c, OFFER_SQL, offerIds, asOf);
  }

  /** Recomputes the rollup row of each given product as of {@code asOf}. Returns rows written. */
  public static int recomputeProducts(Connection c, Collection<Long> productIds, Instant asOf)
      throws SQLException {
    return recompute(c, PRODUCT_SQL, productIds, asOf);
  }

  private static int recompute(Connection c, String sql, Collection<Long> ids, Instant asOf)
      throws SQLException {
    if (ids.isEmpty()) {
      return 0;
    }
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(asOf));
      ps.setArray(2, c.createArrayOf("bigint", ids.toArray()));
      return ps.executeUpdate();
    }
  }

  /** The offers a run wrote an observation for — the run's touched set. */
  public static List<Long> offersObservedIn(Connection c, long runId) throws SQLException {
    String sql =
        "select distinct offer_id from price_observations where crawl_run_id = ? order by offer_id";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, runId);
      return longs(ps);
    }
  }

  /** Every offer at the given retailers. */
  public static List<Long> offersAt(Connection c, Collection<String> retailers)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("select id from offers where retailer = any (?) order by id")) {
      ps.setArray(1, c.createArrayOf("text", retailers.toArray()));
      return longs(ps);
    }
  }

  /** The distinct products the given offers are linked to. */
  public static List<Long> linkedProductsOf(Connection c, Collection<Long> offerIds)
      throws SQLException {
    if (offerIds.isEmpty()) {
      return List.of();
    }
    String sql =
        """
        select distinct product_id from offers
        where id = any (?) and product_id is not null and resolution_status in ('auto', 'reviewed')
        order by product_id
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setArray(1, c.createArrayOf("bigint", offerIds.toArray()));
      return longs(ps);
    }
  }

  /** Every product in a category. */
  public static List<Long> productsIn(Connection c, String category) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("select id from products where category = ? order by id")) {
      ps.setString(1, category);
      return longs(ps);
    }
  }

  /** An offer's rollup row, if computed. */
  public static Optional<Rollup> offerRollup(Connection c, long offerId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(SELECT + " where offer_id = ?")) {
      ps.setLong(1, offerId);
      return one(ps);
    }
  }

  /** A product's rollup row, if computed. */
  public static Optional<Rollup> productRollup(Connection c, long productId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(SELECT + " where product_id = ?")) {
      ps.setLong(1, productId);
      return one(ps);
    }
  }

  private static Optional<Rollup> one(PreparedStatement ps) throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      return rs.next() ? Optional.of(read(rs)) : Optional.empty();
    }
  }

  private static Rollup read(ResultSet rs) throws SQLException {
    int i = 1;
    Long offerId = longOrNull(rs, i++);
    Long productId = longOrNull(rs, i++);
    Instant asOf = rs.getTimestamp(i++).toInstant();
    Integer currentPrice = intOrNull(rs, i++);
    Instant currentObservedAt = instantOrNull(rs, i++);
    Boolean currentInStock = boolOrNull(rs, i++);
    Long currentOfferId = longOrNull(rs, i++);
    Integer listPrice = intOrNull(rs, i++);
    Window d7 = new Window(intOrNull(rs, i++), intOrNull(rs, i++), intOrNull(rs, i++));
    Window d30 = new Window(intOrNull(rs, i++), intOrNull(rs, i++), intOrNull(rs, i++));
    Window d90 = new Window(intOrNull(rs, i++), intOrNull(rs, i++), intOrNull(rs, i++));
    Window d365 = new Window(intOrNull(rs, i++), intOrNull(rs, i++), intOrNull(rs, i++));
    Double percentile = doubleOrNull(rs, i++);
    Double volatility = doubleOrNull(rs, i++);
    int observations = rs.getInt(i++);
    int synthetic = rs.getInt(i++);
    List<SaleWindow> sales = saleWindows(rs.getString(i++));
    int saleDays = rs.getInt(i++);
    Instant lastSaleEndedAt = instantOrNull(rs, i++);
    Instant computedAt = rs.getTimestamp(i++).toInstant();
    return new Rollup(
        offerId,
        productId,
        asOf,
        currentPrice,
        currentObservedAt,
        currentInStock,
        currentOfferId,
        listPrice,
        d7,
        d30,
        d90,
        d365,
        percentile,
        volatility,
        observations,
        synthetic,
        sales,
        saleDays,
        lastSaleEndedAt,
        computedAt);
  }

  private static List<SaleWindow> saleWindows(String json) {
    try {
      List<Map<String, Object>> raw = JSON.readValue(json, new TypeReference<>() {});
      List<SaleWindow> out = new ArrayList<>(raw.size());
      for (Map<String, Object> w : raw) {
        out.add(
            new SaleWindow(
                Instant.parse(w.get("start").toString().replace("+00:00", "Z")),
                Instant.parse(w.get("end").toString().replace("+00:00", "Z")),
                ((Number) w.get("min_price_cents")).intValue(),
                ((Number) w.get("off_pct")).intValue(),
                ((Number) w.get("days")).intValue()));
      }
      return List.copyOf(out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<Long> longs(PreparedStatement ps) throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      List<Long> out = new ArrayList<>();
      while (rs.next()) {
        out.add(rs.getLong(1));
      }
      return out;
    }
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
