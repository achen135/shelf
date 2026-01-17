package com.achen.shelf.api;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.QueryDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.HttpStatus;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The query API (M6), on Javalin: read-only, no auth, JSON in snake_case.
 *
 * <ul>
 *   <li>{@code GET /categories} — every category served, with its spec schema (the filter UI).
 *   <li>{@code GET /products?category=…} — price range in dollars, {@code in_stock}, any spec field
 *       as a filter (matched against the listings, see {@link QueryDao}), {@code sort=deal|price|
 *       name}, {@code limit}/{@code offset}, optional {@code signal}.
 *   <li>{@code GET /products/{id}?days=365} — the product, its price picture and call, its live
 *       listings each with their own current price, and its daily history over the window.
 *   <li>{@code GET /deals?category=…} — {@code /products} with {@code signal=buy}, {@code
 *       sort=deal}.
 *   <li>{@code GET /health} — a {@code select 1}.
 *   <li>{@code /} — the demo page, from the classpath.
 * </ul>
 *
 * <p>Handlers run on virtual threads; every request borrows one pooled connection for as long as
 * its queries take and no longer. Errors are JSON {@code {status, title}}.
 */
public final class ApiServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

  static final int DEFAULT_HISTORY_DAYS = 365;
  static final int MAX_HISTORY_DAYS = 730;

  private final Database db;
  private final Map<String, CategoryConfig> categories;
  private final Clock clock;
  private final Javalin app;

  public ApiServer(Database db, List<CategoryConfig> categories, Clock clock) {
    this.db = db;
    Map<String, CategoryConfig> byName = new LinkedHashMap<>();
    categories.forEach(c -> byName.put(c.name(), c));
    this.categories = Map.copyOf(byName);
    this.clock = clock;
    ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    this.app =
        Javalin.create(
            config -> {
              config.showJavalinBanner = false;
              config.useVirtualThreads = true;
              config.jsonMapper(new JavalinJackson(mapper, false));
              config.staticFiles.add(
                  s -> {
                    s.hostedPath = "/";
                    s.directory = "/public";
                    s.location = Location.CLASSPATH;
                  });
              config.http.defaultContentType = "application/json";
            });
    app.get("/health", this::health);
    app.get("/categories", this::categories);
    app.get("/products", ctx -> search(ctx, null));
    app.get("/products/{id}", this::product);
    app.get("/deals", ctx -> search(ctx, "buy"));
    app.exception(
        HttpResponseException.class,
        (e, ctx) ->
            ctx.status(e.getStatus()).json(new Views.Problem(e.getStatus(), e.getMessage())));
    app.exception(
        SQLException.class,
        (e, ctx) -> {
          log.error("query failed: {} {}", ctx.method(), ctx.fullUrl(), e);
          ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
              .json(new Views.Problem(500, "the query failed; see the server log"));
        });
    app.error(
        404,
        "application/json",
        ctx -> ctx.json(new Views.Problem(404, "no such route: " + ctx.path())));
  }

  /** Binds and serves. Port 0 picks a free one; {@link #port()} says which. */
  public ApiServer start(String host, int port) {
    app.start(host, port);
    log.info("api listening on {}:{} serving {}", host, port(), categories.keySet());
    return this;
  }

  public int port() {
    return app.port();
  }

  @Override
  public void close() {
    app.stop();
  }

  private void health(Context ctx) throws SQLException {
    try (Connection c = db.connection();
        Statement s = c.createStatement()) {
      s.execute("select 1");
    }
    ctx.json(new Views.Health("ok", "ok"));
  }

  private void categories(Context ctx) {
    ctx.json(categories.values().stream().map(Views.CategoryView::of).toList());
  }

  private void search(Context ctx, String forcedSignal) throws SQLException {
    QueryDao.Search q = SearchRequest.parse(ctx, categories, forcedSignal);
    if (forcedSignal != null) {
      q =
          new QueryDao.Search(
              q.category(),
              q.minPriceCents(),
              q.maxPriceCents(),
              true,
              q.specFilters(),
              forcedSignal,
              QueryDao.Sort.DEAL,
              q.limit(),
              q.offset());
    }
    QueryDao.Page page;
    try (Connection c = db.connection()) {
      page = QueryDao.search(c, q);
    }
    ctx.json(
        new Views.ProductsResponse(
            Views.SearchEcho.of(q),
            page.total(),
            page.hits().stream().map(Views.ProductView::of).toList()));
  }

  private void product(Context ctx) throws SQLException {
    long id;
    try {
      id = Long.parseLong(ctx.pathParam("id"));
    } catch (NumberFormatException e) {
      throw new BadRequestResponse("product id must be an integer");
    }
    int days = SearchRequest.integer(ctx, "days", DEFAULT_HISTORY_DAYS, 1, MAX_HISTORY_DAYS);
    Views.ProductDetailResponse response;
    try (Connection c = db.connection()) {
      QueryDao.Detail detail =
          QueryDao.product(c, id).orElseThrow(() -> new NotFoundResponse("no product " + id));
      List<QueryDao.Day> history = QueryDao.history(c, id, days, clock.instant());
      response = Views.ProductDetailResponse.of(detail, days, history);
    }
    ctx.json(response);
  }
}
