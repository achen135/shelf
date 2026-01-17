package com.achen.shelf.api;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.SpecValidator;
import com.achen.shelf.db.QueryDao;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses and validates a {@code GET /products} query string into a {@link QueryDao.Search}.
 *
 * <p>Reserved parameters are the paging and pricing knobs; every other parameter must be a field of
 * the category's spec schema, and its value must fit that field — validated by the same {@code
 * SpecValidator} the crawler and the config loader use, so {@code layout_size=76} is refused with
 * the schema's own message rather than silently matching nothing.
 */
final class SearchRequest {

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;

  private static final Set<String> RESERVED =
      Set.of("category", "min_price", "max_price", "in_stock", "sort", "limit", "offset", "signal");

  private SearchRequest() {}

  /** The search a request asks for, or a 400 naming what was wrong with it. */
  static QueryDao.Search parse(
      Context ctx, Map<String, CategoryConfig> categories, String forcedSignal) {
    CategoryConfig category = category(ctx, categories);
    int min = cents(ctx, "min_price", 0);
    int max = cents(ctx, "max_price", Integer.MAX_VALUE);
    if (min > max) {
      throw new BadRequestResponse("min_price must not exceed max_price");
    }
    boolean inStock = bool(ctx, "in_stock", true);
    QueryDao.Sort sort = QueryDao.Sort.DEAL;
    String sortParam = ctx.queryParam("sort");
    if (sortParam != null) {
      sort = QueryDao.Sort.parse(sortParam);
      if (sort == null) {
        throw new BadRequestResponse("sort must be one of deal, price, name");
      }
    }
    int limit = integer(ctx, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
    int offset = integer(ctx, "offset", 0, 0, Integer.MAX_VALUE);
    String signal = forcedSignal != null ? forcedSignal : ctx.queryParam("signal");
    if (signal != null && !Set.of("buy", "wait", "neutral").contains(signal)) {
      throw new BadRequestResponse("signal must be one of buy, wait, neutral");
    }

    Map<String, Object> raw = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> e : ctx.queryParamMap().entrySet()) {
      if (RESERVED.contains(e.getKey())) {
        continue;
      }
      if (!category.specSchema().containsKey(e.getKey())) {
        throw new BadRequestResponse(
            "unknown parameter '"
                + e.getKey()
                + "'; spec fields for "
                + category.name()
                + " are "
                + category.specSchema().keySet());
      }
      raw.put(e.getKey(), e.getValue().get(0));
    }
    SpecValidator.Result validated = new SpecValidator(category.specSchema()).validate(raw);
    if (!validated.isClean()) {
      throw new BadRequestResponse(String.join("; ", validated.warnings()));
    }
    return new QueryDao.Search(
        category.name(), min, max, inStock, validated.accepted(), signal, sort, limit, offset);
  }

  static CategoryConfig category(Context ctx, Map<String, CategoryConfig> categories) {
    String name = ctx.queryParam("category");
    if (name == null || name.isBlank()) {
      throw new BadRequestResponse("category is required; one of " + categories.keySet());
    }
    CategoryConfig category = categories.get(name.toLowerCase(Locale.ROOT));
    if (category == null) {
      throw new BadRequestResponse(
          "unknown category '" + name + "'; one of " + categories.keySet());
    }
    return category;
  }

  /** A dollar amount ("149.99") as cents. */
  private static int cents(Context ctx, String name, int fallback) {
    String v = ctx.queryParam(name);
    if (v == null || v.isBlank()) {
      return fallback;
    }
    try {
      BigDecimal dollars = new BigDecimal(v.trim());
      if (dollars.signum() < 0) {
        throw new BadRequestResponse(name + " must not be negative");
      }
      return dollars.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    } catch (NumberFormatException | ArithmeticException e) {
      throw new BadRequestResponse(name + " must be a dollar amount, e.g. 149.99");
    }
  }

  private static boolean bool(Context ctx, String name, boolean fallback) {
    String v = ctx.queryParam(name);
    if (v == null) {
      return fallback;
    }
    return switch (v.trim().toLowerCase(Locale.ROOT)) {
      case "true", "1", "yes" -> true;
      case "false", "0", "no" -> false;
      default -> throw new BadRequestResponse(name + " must be true or false");
    };
  }

  static int integer(Context ctx, String name, int fallback, int min, int max) {
    String v = ctx.queryParam(name);
    if (v == null || v.isBlank()) {
      return fallback;
    }
    try {
      int n = Integer.parseInt(v.trim());
      if (n < min || n > max) {
        throw new BadRequestResponse(name + " must be between " + min + " and " + max);
      }
      return n;
    } catch (NumberFormatException e) {
      throw new BadRequestResponse(name + " must be an integer");
    }
  }
}
