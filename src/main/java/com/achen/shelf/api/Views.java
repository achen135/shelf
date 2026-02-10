package com.achen.shelf.api;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.SpecField;
import com.achen.shelf.consensus.ConsensusRule;
import com.achen.shelf.db.QueryDao;
import com.achen.shelf.mention.Excerpt;
import com.achen.shelf.resolve.Scorer;
import com.achen.shelf.signal.ReasonCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The JSON the API returns, as records. Field names go out in snake_case; every number a page shows
 * carries what it rests on — the synthetic share of the year, the instant the rollup was computed,
 * and whether the call is in step with the price it was made on.
 */
final class Views {

  private Views() {}

  record CategoryView(String name, Map<String, FieldView> specSchema, List<String> retailers) {
    static CategoryView of(CategoryConfig c) {
      Map<String, FieldView> fields = new LinkedHashMap<>();
      c.specSchema().forEach((k, v) -> fields.put(k, FieldView.of(v)));
      return new CategoryView(
          c.name(), fields, c.enabledRetailers().stream().map(r -> r.name()).toList());
    }
  }

  record FieldView(String type, List<String> values, String unit) {
    static FieldView of(SpecField f) {
      return new FieldView(f.type().name().toLowerCase(Locale.ROOT), f.enumValues(), f.unit());
    }
  }

  record Reason(String code, String description) {
    static Reason of(String code) {
      String description;
      try {
        description = ReasonCode.valueOf(code).description();
      } catch (IllegalArgumentException e) {
        description = null;
      }
      return new Reason(code, description);
    }
  }

  record PriceView(
      Integer currentCents,
      Boolean inStock,
      Integer listCents,
      @JsonProperty("percentile_365d") Double percentile365d,
      @JsonProperty("min_30d_cents") Integer min30dCents,
      @JsonProperty("min_365d_cents") Integer min365dCents,
      @JsonProperty("median_365d_cents") Integer median365dCents,
      @JsonProperty("max_365d_cents") Integer max365dCents,
      @JsonProperty("observations_365d") int observations365d,
      @JsonProperty("synthetic_365d") int synthetic365d,
      Double syntheticShare,
      @JsonProperty("sale_days_365d") int saleDays365d,
      Instant asOf) {
    static PriceView of(QueryDao.Price p) {
      if (p == null) {
        return null;
      }
      return new PriceView(
          p.currentPriceCents(),
          p.currentInStock(),
          p.listPriceCents(),
          p.percentile365d(),
          p.min30d(),
          p.min365d(),
          p.median365d(),
          p.max365d(),
          p.observations365d(),
          p.synthetic365d(),
          p.observations365d() == 0 ? null : (double) p.synthetic365d() / p.observations365d(),
          p.saleDays365d(),
          p.asOf());
    }
  }

  record SignalView(
      String signal, List<Reason> reasons, Long bestOfferId, Instant asOf, boolean stale) {
    /** {@code stale}: the call was made on an older rollup than the price shown beside it. */
    static SignalView of(QueryDao.Signal s, QueryDao.Price p) {
      if (s == null) {
        return null;
      }
      boolean stale = p != null && !s.asOf().equals(p.asOf());
      return new SignalView(
          s.signal(),
          s.reasonCodes().stream().map(Reason::of).toList(),
          s.bestOfferId(),
          s.asOf(),
          stale);
    }
  }

  /**
   * What the communities said (M10). The count is never optional: a score without it would be the
   * one number Spec v2 §3 says never to show. {@code leaning} is the score in words at the rule's
   * fixed cut-offs; {@code unheard} is a row with nothing in the window.
   */
  record ConsensusView(
      Double score,
      String leaning,
      int mentionCount,
      int positiveCount,
      int negativeCount,
      int neutralCount,
      Double positiveShare,
      int sourceDiversity,
      int windowDays,
      Instant asOf,
      List<QuoteView> quotes) {
    static ConsensusView of(QueryDao.Consensus k, List<QueryDao.Quote> quotes) {
      if (k == null) {
        return null;
      }
      String leaning =
          k.score() == null
              ? ConsensusRule.Leaning.UNHEARD.dbValue()
              : ConsensusRule.leaning(k.score()).dbValue();
      return new ConsensusView(
          k.score(),
          leaning,
          k.mentionCount(),
          k.positiveCount(),
          k.negativeCount(),
          k.neutralCount(),
          k.positiveShare(),
          k.sourceDiversity(),
          k.windowDays(),
          k.asOf(),
          quotes.stream().map(QuoteView::of).toList());
    }
  }

  /**
   * A quoted mention: an excerpt around the words that named the product, and where it was said.
   */
  record QuoteView(
      String sentiment,
      String excerpt,
      String community,
      String source,
      String platform,
      Instant postedAt,
      String permalink) {
    static final int EXCERPT_CHARS = 280;

    static QuoteView of(QueryDao.Quote q) {
      String full =
          q.title() == null || q.title().isBlank() ? q.text() : q.title() + "\n" + q.text();
      return new QuoteView(
          q.sentiment(),
          Excerpt.of(full, Scorer.tokens(q.matchedText()), EXCERPT_CHARS),
          q.community(),
          q.source(),
          q.source().startsWith("youtube")
              ? "YouTube"
              : q.source().startsWith("reddit") ? "Reddit" : q.source(),
          q.postedAt(),
          q.permalink());
    }
  }

  record OfferView(
      long id,
      String retailer,
      String title,
      String url,
      Map<String, Object> spec,
      Integer priceCents,
      Boolean inStock,
      Instant observedAt,
      Integer listCents,
      @JsonProperty("percentile_365d") Double percentile365d,
      @JsonProperty("min_365d_cents") Integer min365dCents,
      @JsonProperty("observations_365d") int observations365d,
      @JsonProperty("synthetic_365d") int synthetic365d) {
    static OfferView of(QueryDao.Offer o) {
      if (o == null) {
        return null;
      }
      return new OfferView(
          o.id(),
          o.retailer(),
          o.title(),
          o.url(),
          o.spec(),
          o.priceCents(),
          o.inStock(),
          o.observedAt(),
          o.listPriceCents(),
          o.percentile365d(),
          o.min365d(),
          o.observations365d(),
          o.synthetic365d());
    }
  }

  record ProductView(
      long id,
      String category,
      String brand,
      String model,
      String name,
      Map<String, Object> spec,
      PriceView price,
      SignalView signal,
      OfferView offer,
      ConsensusView consensus) {
    static ProductView of(QueryDao.Hit h) {
      QueryDao.Product p = h.product();
      return new ProductView(
          p.id(),
          p.category(),
          p.brand(),
          p.model(),
          p.name(),
          p.spec(),
          PriceView.of(h.price()),
          SignalView.of(h.signal(), h.price()),
          OfferView.of(h.offer()),
          ConsensusView.of(h.consensus(), List.of()));
    }
  }

  record SearchEcho(
      String category,
      int minPriceCents,
      Integer maxPriceCents,
      boolean inStock,
      Map<String, Object> spec,
      String signal,
      String sort,
      int limit,
      int offset) {
    static SearchEcho of(QueryDao.Search q) {
      return new SearchEcho(
          q.category(),
          q.minPriceCents(),
          q.maxPriceCents() == Integer.MAX_VALUE ? null : q.maxPriceCents(),
          q.inStockOnly(),
          q.specFilters(),
          q.signal(),
          q.sort().lowerName(),
          q.limit(),
          q.offset());
    }
  }

  record ProductsResponse(SearchEcho query, long total, List<ProductView> products) {}

  record DayView(LocalDate day, Integer priceCents, boolean synthetic, int observations) {
    static DayView of(QueryDao.Day d) {
      return new DayView(d.day(), d.priceCents(), d.synthetic(), d.observations());
    }
  }

  record ProductDetailResponse(
      long id,
      String category,
      String brand,
      String model,
      String name,
      Map<String, Object> spec,
      PriceView price,
      SignalView signal,
      ConsensusView consensus,
      List<OfferView> offers,
      int historyDays,
      List<DayView> history) {
    static ProductDetailResponse of(QueryDao.Detail d, int days, List<QueryDao.Day> history) {
      QueryDao.Product p = d.product();
      return new ProductDetailResponse(
          p.id(),
          p.category(),
          p.brand(),
          p.model(),
          p.name(),
          p.spec(),
          PriceView.of(d.price()),
          SignalView.of(d.signal(), d.price()),
          ConsensusView.of(d.consensus(), d.quotes()),
          d.offers().stream().map(OfferView::of).toList(),
          days,
          history.stream().map(DayView::of).toList());
    }
  }

  record Health(String status, String database) {}

  record Problem(int status, String title) {}
}
