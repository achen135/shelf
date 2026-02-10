package com.achen.shelf.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.db.DealSignalDao;
import com.achen.shelf.testing.PostgresTestBase;
import com.achen.shelf.testing.Storefront;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The routes over HTTP: shapes, validation, errors, the page. */
class ApiServerTest extends PostgresTestBase {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private Storefront shop;
  private ApiServer server;
  private String base;

  @BeforeEach
  void setUp() throws SQLException {
    shop = new Storefront(DB);
    server =
        new ApiServer(DB, List.of(shop.category), Clock.fixed(Storefront.AS_OF, ZoneOffset.UTC))
            .start("127.0.0.1", 0);
    base = "http://127.0.0.1:" + server.port();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode json(String path, int expectedStatus) throws IOException, InterruptedException {
    HttpResponse<String> r = get(path);
    assertThat(r.statusCode()).as(path + " → " + r.body()).isEqualTo(expectedStatus);
    assertThat(r.headers().firstValue("content-type").orElse("")).startsWith("application/json");
    return JSON.readTree(r.body());
  }

  @Test
  void productsSearchesFiltersAndEchoesTheQuery() throws Exception {
    JsonNode all = json("/products?category=keyboards", 200);
    assertThat(all.get("total").asLong()).isEqualTo(2);
    assertThat(all.get("query").get("sort").asText()).isEqualTo("deal");
    assertThat(all.get("query").get("in_stock").asBoolean()).isTrue();
    assertThat(all.get("query").get("max_price_cents").isNull()).isTrue();
    JsonNode q1 = null;
    for (JsonNode p : all.get("products")) {
      if (p.get("id").asLong() == shop.q1pro) {
        q1 = p;
      }
    }
    assertThat(q1).isNotNull();
    assertThat(q1.get("name").asText()).isEqualTo("Keychron Q1 Pro");
    assertThat(q1.get("offer").get("price_cents").asInt()).isEqualTo(9900);
    assertThat(q1.get("price").get("current_cents").asInt()).isEqualTo(9900);
    assertThat(q1.get("price").has("percentile_365d")).isTrue();
    assertThat(q1.get("price").has("synthetic_share")).isTrue();
    assertThat(q1.get("price").get("as_of").asText()).startsWith("2030-06-01T12:00:00Z");
    assertThat(q1.get("signal").get("signal").asText()).isIn("buy", "wait", "neutral");
    assertThat(q1.get("signal").get("reasons").get(0).has("description")).isTrue();
    assertThat(q1.get("signal").get("stale").asBoolean()).isFalse();

    JsonNode filtered =
        json("/products?category=keyboards&layout_size=75&min_price=100&max_price=175.50", 200);
    assertThat(filtered.get("total").asLong()).isEqualTo(1);
    assertThat(filtered.get("query").get("spec").get("layout_size").asText()).isEqualTo("75");
    assertThat(filtered.get("query").get("min_price_cents").asInt()).isEqualTo(10000);
    assertThat(filtered.get("query").get("max_price_cents").asInt()).isEqualTo(17550);
    assertThat(filtered.get("products").get(0).get("offer").get("id").asLong())
        .isEqualTo(shop.q1pro75);

    JsonNode hot = json("/products?category=keyboards&hot_swap=true&layout_size=65", 200);
    assertThat(hot.get("query").get("spec").get("hot_swap").asBoolean()).isTrue();
    assertThat(hot.get("total").asLong()).isEqualTo(1);

    JsonNode paged = json("/products?category=keyboards&sort=name&limit=1&offset=1", 200);
    assertThat(paged.get("total").asLong()).isEqualTo(2);
    assertThat(paged.get("products")).hasSize(1);
  }

  @Test
  void refusesWhatItCannotAnswer() throws Exception {
    assertThat(json("/products", 400).get("title").asText()).contains("category is required");
    assertThat(json("/products?category=monitors", 400).get("title").asText())
        .contains("unknown category");
    assertThat(json("/products?category=keyboards&layout_size=76", 400).get("title").asText())
        .contains("not one of");
    assertThat(json("/products?category=keyboards&colour=red", 400).get("title").asText())
        .contains("unknown parameter 'colour'");
    assertThat(json("/products?category=keyboards&hot_swap=maybe", 400).get("title").asText())
        .contains("expected boolean");
    assertThat(json("/products?category=keyboards&sort=magic", 400).get("title").asText())
        .contains("sort must be");
    assertThat(json("/products?category=keyboards&min_price=-1", 400).get("title").asText())
        .contains("negative");
    assertThat(json("/products?category=keyboards&min_price=abc", 400).get("title").asText())
        .contains("dollar amount");
    assertThat(
            json("/products?category=keyboards&min_price=200&max_price=100", 400)
                .get("title")
                .asText())
        .contains("min_price must not exceed");
    assertThat(json("/products?category=keyboards&limit=0", 400).get("title").asText())
        .contains("between 1 and 200");
    assertThat(json("/products?category=keyboards&signal=maybe", 400).get("title").asText())
        .contains("signal must be");
    assertThat(json("/products/abc", 400).get("title").asText()).contains("integer");
    assertThat(json("/products/424242", 404).get("title").asText()).contains("no product 424242");
    assertThat(json("/products/1?days=0", 400).get("title").asText()).contains("between 1 and 730");
    assertThat(json("/nothing/here", 404).get("status").asInt()).isEqualTo(404);
  }

  @Test
  void aProductDetailCarriesListingsHistoryAndTheSplit() throws Exception {
    JsonNode d = json("/products/" + shop.q1pro + "?days=365", 200);
    assertThat(d.get("name").asText()).isEqualTo("Keychron Q1 Pro");
    assertThat(d.get("spec").get("hot_swap").asBoolean()).isTrue();
    assertThat(d.get("offers")).hasSize(3);
    assertThat(d.get("offers").get(0).get("id").asLong()).isEqualTo(shop.q1pro65);
    assertThat(d.get("offers").get(2).get("in_stock").asBoolean()).isFalse();
    assertThat(d.get("history_days").asInt()).isEqualTo(365);
    assertThat(d.get("history")).hasSize(31);
    JsonNode first = d.get("history").get(0);
    assertThat(first.get("day").asText()).isEqualTo("2029-08-05");
    assertThat(first.get("price_cents").asInt()).isEqualTo(8000);
    assertThat(first.get("synthetic").asBoolean()).isFalse();
    assertThat(d.get("price").get("synthetic_365d").asInt()).isZero();

    JsonNode ducky = json("/products/" + shop.ducky, 200);
    assertThat(ducky.get("price").get("synthetic_share").asDouble()).isEqualTo(1.0);
    assertThat(ducky.get("history").get(0).get("synthetic").asBoolean()).isTrue();
    boolean flagged = false;
    for (JsonNode r : ducky.get("signal").get("reasons")) {
      flagged |= r.get("code").asText().equals("MOSTLY_SYNTHETIC");
    }
    assertThat(flagged).isTrue();
  }

  @Test
  void dealsAreTheBuysAndAStaleCallSaysSo() throws Exception {
    assertThat(json("/deals?category=keyboards", 200).get("total").asLong()).isZero();
    try (Connection c = DB.connection()) {
      DealSignalDao.upsert(
          c,
          List.of(
              new DealSignalDao.Signal(
                  shop.q1pro,
                  "buy",
                  List.of("ON_SALE", "YEAR_LOW"),
                  shop.q1pro65,
                  Storefront.AS_OF.minusSeconds(3600),
                  null)));
    }
    JsonNode deals = json("/deals?category=keyboards&sort=name&in_stock=false", 200);
    assertThat(deals.get("total").asLong()).isEqualTo(1);
    // /deals pins signal=buy, sort=deal and in-stock whatever the query said
    assertThat(deals.get("query").get("signal").asText()).isEqualTo("buy");
    assertThat(deals.get("query").get("sort").asText()).isEqualTo("deal");
    assertThat(deals.get("query").get("in_stock").asBoolean()).isTrue();
    JsonNode hit = deals.get("products").get(0);
    assertThat(hit.get("id").asLong()).isEqualTo(shop.q1pro);
    assertThat(hit.get("signal").get("stale").asBoolean()).isTrue();
    assertThat(hit.get("signal").get("reasons").get(1).get("description").asText())
        .isEqualTo("the lowest price of the trailing year");
  }

  @Test
  void theConsensusRidesBesideTheCallWithItsCountAndQuotes() throws Exception {
    // Before any pass: nothing, not zero.
    JsonNode before = json("/products?category=keyboards&in_stock=false", 200);
    assertThat(before.get("products").get(0).get("consensus").isNull()).isTrue();

    // Two linked mentions of the Q1 Pro: a positive comment and a neutral video, one community.
    execute(
        "insert into raw_mentions (category, source, source_id, community, title, text, posted_at,"
            + " permalink) values"
            + " ('keyboards', 'youtube_comment', 'c1', 'yt_test', null,"
            + " 'Bought the Q1 Pro after this. No regrets. Long story about the rest.',"
            + " '2030-05-20T00:00:00Z', 'https://www.youtube.com/watch?v=v1&lc=c1'),"
            + " ('keyboards', 'youtube_video', 'v1', 'yt_test', 'Keychron Q1 Pro after a year',"
            + " 'Chapters: sound, software.', '2030-05-01T00:00:00Z',"
            + " 'https://www.youtube.com/watch?v=v1')");
    execute(
        "insert into mentions (raw_mention_id, product_id, match_score, match_status, matched_text,"
            + " sentiment, extraction_method) select r.id, "
            + shop.q1pro
            + ", 1.0, 'auto', 'q1 pro',"
            + " case when r.source = 'youtube_comment' then 'positive' else 'neutral' end, 'rule'"
            + " from raw_mentions r");
    new com.achen.shelf.consensus.ConsensusRun(DB, Clock.fixed(Storefront.AS_OF, ZoneOffset.UTC))
        .run(shop.category);

    JsonNode list = json("/products?category=keyboards&in_stock=false&sort=consensus", 200);
    assertThat(list.get("query").get("sort").asText()).isEqualTo("consensus");
    JsonNode first = list.get("products").get(0);
    assertThat(first.get("id").asLong()).isEqualTo(shop.q1pro);
    JsonNode k = first.get("consensus");
    assertThat(k.get("mention_count").asInt()).isEqualTo(2);
    assertThat(k.get("leaning").asText()).isEqualTo("liked");
    assertThat(k.get("score").asDouble()).isEqualTo(0.5);
    assertThat(k.get("source_diversity").asInt()).isEqualTo(1);
    assertThat(k.get("window_days").asInt()).isEqualTo(90);
    assertThat(k.get("quotes")).isEmpty(); // the list carries the numbers, the detail the words
    JsonNode second = list.get("products").get(1).get("consensus");
    assertThat(second.get("mention_count").asInt()).isZero();
    assertThat(second.get("leaning").asText()).isEqualTo("unheard");
    assertThat(second.get("score").isNull()).isTrue();

    JsonNode d = json("/products/" + shop.q1pro, 200);
    JsonNode dk = d.get("consensus");
    assertThat(dk.get("mention_count").asInt()).isEqualTo(2);
    assertThat(dk.get("quotes")).hasSize(2);
    JsonNode q = dk.get("quotes").get(0);
    assertThat(q.get("sentiment").asText()).isEqualTo("positive");
    assertThat(q.get("excerpt").asText()).isEqualTo("Bought the Q1 Pro after this. No regrets.");
    assertThat(q.get("platform").asText()).isEqualTo("YouTube");
    assertThat(q.get("permalink").asText()).isEqualTo("https://www.youtube.com/watch?v=v1&lc=c1");
    assertThat(q.get("community").asText()).isEqualTo("yt_test");
    assertThat(dk.get("quotes").get(1).get("sentiment").asText()).isEqualTo("neutral");
    // The call is untouched by the consensus: separate objects, never one number.
    assertThat(d.get("signal").get("signal").asText()).isIn("buy", "wait", "neutral");

    assertThat(json("/products?category=keyboards&sort=talk", 400).get("title").asText())
        .isEqualTo("sort must be one of deal, price, name, consensus");
  }

  @Test
  void servesTheCategoriesTheHealthCheckAndThePage() throws Exception {
    JsonNode cats = json("/categories", 200);
    assertThat(cats).hasSize(1);
    assertThat(cats.get(0).get("name").asText()).isEqualTo("keyboards");
    assertThat(cats.get(0).get("spec_schema").get("layout_size").get("values")).isNotEmpty();
    assertThat(cats.get(0).get("spec_schema").get("hot_swap").get("type").asText())
        .isEqualTo("boolean");
    assertThat(cats.get(0).get("retailers")).isNotEmpty();

    assertThat(json("/health", 200).get("status").asText()).isEqualTo("ok");

    HttpResponse<String> page = get("/");
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.headers().firstValue("content-type").orElse("")).startsWith("text/html");
    assertThat(page.body()).contains("<title>Shelf</title>").contains("/products");
  }
}
