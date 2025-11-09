package com.achen.shelf.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.crawl.Normalizer;
import com.achen.shelf.crawl.SeedCatalog;
import com.achen.shelf.db.Jsonb;
import com.achen.shelf.db.OfferDao;
import com.achen.shelf.db.ProductDao;
import com.achen.shelf.db.ResolutionDao;
import com.achen.shelf.testing.FixtureServer;
import com.achen.shelf.testing.PostgresTestBase;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One resolution pass against a real Postgres: what it links, what it queues, what it leaves alone,
 * and what it derives. The catalog is the fixture category's four seeds (Keychron Q6 HE, K2 Ultra
 * and Q65; Wooting 80HE), loaded through the real config loader.
 */
class ResolutionRunTest extends PostgresTestBase {

  private CategoryConfig category;
  private ResolutionRun run;
  private ResolutionDao dao;
  private OfferDao offers;

  @BeforeEach
  void setUp() throws SQLException {
    String yaml =
        FixtureServer.Fixtures.read("categories/fixture-server.yaml.template")
            .replace("__PORT__", "1");
    category =
        new CategoryConfigLoader()
            .load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "fixture");
    SeedCatalog.bootstrap(category, new ProductDao(DB));
    run = new ResolutionRun(DB, Resolver.Thresholds.defaults());
    dao = new ResolutionDao(DB);
    offers = new OfferDao(DB);
  }

  private long offer(String retailer, String brand, String title) throws SQLException {
    return offer(retailer, brand, title, Map.of());
  }

  private long offer(String retailer, String brand, String title, Map<String, Object> spec)
      throws SQLException {
    String url =
        "http://127.0.0.1:1/" + retailer + "/" + Normalizer.normalize(title).replace(' ', '-');
    return offers.upsert(
        new OfferDao.Listing(
            retailer,
            url,
            title,
            null,
            "USD",
            brand,
            Normalizer.normalize(brand),
            Jsonb.fromMap(spec)));
  }

  private long productId(String model) throws SQLException {
    return new ProductDao(DB)
        .findId("keyboards", "keychron", Normalizer.normalize(model))
        .or(
            () -> {
              try {
                return new ProductDao(DB)
                    .findId("keyboards", "wooting", Normalizer.normalize(model));
              } catch (SQLException e) {
                throw new IllegalStateException(e);
              }
            })
        .orElseThrow();
  }

  private record OfferState(
      String status, Long productId, Long candidateId, Double score, Map<String, Object> spec) {}

  private OfferState state(long offerId) throws SQLException {
    try (Connection c = DB.connection();
        PreparedStatement ps =
            c.prepareStatement(
                "select resolution_status, product_id, candidate_product_id, resolution_score,"
                    + " spec::text from offers where id = ?")) {
      ps.setLong(1, offerId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        long p = rs.getLong(2);
        Long product = rs.wasNull() ? null : p;
        long cand = rs.getLong(3);
        Long candidate = rs.wasNull() ? null : cand;
        double s = rs.getDouble(4);
        Double score = rs.wasNull() ? null : s;
        return new OfferState(
            rs.getString(1), product, candidate, score, Jsonb.toMap(rs.getString(5)));
      }
    }
  }

  private Map<String, Object> productSpec(long productId) throws SQLException {
    try (Connection c = DB.connection();
        PreparedStatement ps = c.prepareStatement("select spec::text from products where id = ?")) {
      ps.setLong(1, productId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return Jsonb.toMap(rs.getString(1));
      }
    }
  }

  @Test
  void linksQueuesAndSkipsAccordingToScore() throws SQLException {
    long whole = offer("json_store", "Keychron", "Keychron Q6 HE QMK Wireless Custom Keyboard");
    long bundle = offer("json_store", "Keychron", "Keychron Q6 HE Keyboard and Mouse Bundle");
    long sibling = offer("json_store", "Keychron", "Keychron Q65 Custom Keyboard");
    long unrelated = offer("json_store", "Keychron", "Keychron K3 Pro Low Profile");
    long thirdParty = offer("html_store", "Third Party", "Wooting 80HE Magnetic 75% Keyboard");
    long elsewhere =
        offer("other_store", "Keychron", "Keychron Q6 HE QMK Wireless Custom Keyboard");

    ResolutionRun.Summary summary = run.run(category);

    assertThat(summary.considered()).isEqualTo(5); // other_store is not in this category
    assertThat(summary.autoLinked()).isEqualTo(3);
    assertThat(summary.queuedForReview()).isEqualTo(1);
    assertThat(summary.unmatched()).isEqualTo(1);
    assertThat(summary.reviewQueueSize()).isEqualTo(1);

    OfferState linked = state(whole);
    assertThat(linked.status()).isEqualTo("auto");
    assertThat(linked.productId()).isEqualTo(productId("Q6 HE"));
    assertThat(linked.score()).isEqualTo(1.0);
    assertThat(linked.candidateId()).isNull();

    // The bundle phrase from the fixture config keeps a whole match out of auto; the Q6 HE is
    // proposed for a human.
    OfferState review = state(bundle);
    assertThat(review.status()).isEqualTo("pending");
    assertThat(review.productId()).isNull();
    assertThat(review.candidateId()).isEqualTo(productId("Q6 HE"));
    assertThat(review.score()).isEqualTo(0.5);

    // "q65" is its own product and never the Q6 HE's — the M1 boundary rule, still true.
    assertThat(state(sibling).productId()).isEqualTo(productId("Q65"));

    OfferState none = state(unrelated);
    assertThat(none.status()).isEqualTo("pending");
    assertThat(none.candidateId()).isNull();
    assertThat(none.score()).isLessThan(0.4);

    // No "third party" block: the title names Wooting, so that block is used.
    assertThat(state(thirdParty).productId()).isEqualTo(productId("80HE"));

    assertThat(state(elsewhere).status()).isEqualTo("pending");
    assertThat(state(elsewhere).score()).isNull();
  }

  @Test
  void aSecondPassChangesNothingAndHumanDecisionsOutrankTheMachine() throws SQLException {
    long auto = offer("json_store", "Keychron", "Keychron Q6 HE QMK Wireless Custom Keyboard");
    long reviewed = offer("json_store", "Keychron", "Keychron Q6 HE (a human says this is a Q65)");
    long rejected = offer("json_store", "Keychron", "Keychron Q6 HE (a human says: not a product)");
    long q65 = productId("Q65");
    DB.transaction(
        c -> {
          dao.propose(c, reviewed, productId("Q6 HE"), 0.5);
          assertThat(dao.accept(c, reviewed, q65)).isTrue();
          assertThat(dao.reject(c, rejected)).isTrue();
          return null;
        });

    ResolutionRun.Summary first = run.run(category);
    assertThat(first.considered()).isEqualTo(1);
    assertThat(state(auto).productId()).isEqualTo(productId("Q6 HE"));
    assertThat(state(reviewed))
        .extracting(OfferState::status, OfferState::productId)
        .containsExactly("reviewed", q65);
    assertThat(state(rejected).status()).isEqualTo("rejected");

    ResolutionRun.Summary second = run.run(category);
    assertThat(second.considered()).isZero();

    // A rescore reopens the machine's links and only those.
    ResolutionRun.Summary rescored = run.run(category, true);
    assertThat(rescored.considered()).isEqualTo(1);
    assertThat(rescored.autoLinked()).isEqualTo(1);
    assertThat(state(auto).productId()).isEqualTo(productId("Q6 HE"));
    assertThat(state(reviewed))
        .extracting(OfferState::status, OfferState::productId)
        .containsExactly("reviewed", q65);
    assertThat(state(rejected).status()).isEqualTo("rejected");
  }

  @Test
  void acceptingAProposalLinksItAndRejectingClosesIt() throws SQLException {
    long proposed = offer("json_store", "Keychron", "Keychron Q6 HE Keyboard and Mouse Bundle");
    long plain = offer("json_store", "Keychron", "Keychron something else entirely");
    run.run(category);
    long q6he = productId("Q6 HE");

    boolean accepted = DB.transaction(c -> dao.accept(c, proposed, null));
    assertThat(accepted).isTrue();
    assertThat(state(proposed))
        .extracting(OfferState::status, OfferState::productId)
        .containsExactly("reviewed", q6he);

    // Accepting an offer with no proposal needs an explicit product; rejecting needs nothing.
    boolean acceptedWithoutProposal = DB.transaction(c -> dao.accept(c, plain, null));
    boolean rejected = DB.transaction(c -> dao.reject(c, plain));
    boolean rejectedAgain = DB.transaction(c -> dao.reject(c, plain));
    boolean acceptedAgain = DB.transaction(c -> dao.accept(c, proposed, q6he));
    assertThat(acceptedWithoutProposal).isFalse();
    assertThat(rejected).isTrue();
    assertThat(rejectedAgain).as("no longer pending").isFalse();
    assertThat(acceptedAgain).as("no longer pending").isFalse();

    assertThat(dao.reviewQueue(ResolutionRun.retailerNames(category), 10)).isEmpty();
  }

  @Test
  void theReviewQueueListsProposalsBestFirst() throws SQLException {
    offer("json_store", "Keychron", "Keychron Q6 HE Keyboard and Mouse Bundle"); // 0.5
    offer("json_store", "Keychron", "Keychron K2 8K Ultra Keyboard"); // scattered tokens: 0.8
    run.run(category);

    List<ResolutionDao.ReviewItem> queue =
        dao.reviewQueue(ResolutionRun.retailerNames(category), 10);
    assertThat(queue).extracting(ResolutionDao.ReviewItem::score).containsExactly(0.8, 0.5);
    assertThat(queue)
        .extracting(ResolutionDao.ReviewItem::candidateName)
        .containsExactly("Keychron K2 Ultra", "Keychron Q6 HE");
  }

  @Test
  void derivesACanonicalSpecFromLinkedOffersWithTheSeedWinning() throws SQLException {
    // A config whose seed carries a spec value that the listings disagree with.
    String yaml =
        """
        name: keyboards
        spec_schema:
          switch_type: { type: string, enum: [linear, tactile, magnetic] }
          layout_size: { type: string, enum: ["75", "65", "full"] }
          hot_swap: { type: boolean }
        retailers:
          - name: json_store
            base_url: "http://127.0.0.1:1"
            brand_source: fixed
            brand: Keychron
            fetch: { mode: api, list_paths: ["/p.json?page={page}"], max_rps: 1.0 }
            parser: shopify_products_json
        seed_products:
          - { brand: Keychron, model: Q3 Pro, canonical_name: Keychron Q3 Pro, spec: { layout_size: "full" } }
        """;
    CategoryConfig seeded =
        new CategoryConfigLoader()
            .load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "seeded");
    SeedCatalog.bootstrap(seeded, new ProductDao(DB));
    long q3pro = productId("Q3 Pro");
    assertThat(productSpec(q3pro)).containsExactly(Map.entry("layout_size", "full"));

    offer(
        "json_store",
        "Keychron",
        "Keychron Q3 Pro A",
        Map.of("switch_type", "magnetic", "layout_size", "75", "hot_swap", true));
    offer(
        "json_store",
        "Keychron",
        "Keychron Q3 Pro B",
        Map.of("switch_type", "magnetic", "layout_size", "75"));
    offer(
        "json_store",
        "Keychron",
        "Keychron Q3 Pro C",
        Map.of("switch_type", "linear", "layout_size", "75"));
    offer(
        "json_store",
        "Keychron",
        "Keychron Q3 Pro Keyboard with PBTfans",
        Map.of("switch_type", "tactile"));
    ResolutionRun runner = new ResolutionRun(DB, Resolver.Thresholds.defaults());

    ResolutionRun.Summary summary = runner.run(seeded);

    // The fourth listing is a whole match too — "with pbtfans" is not a phrase in this config.
    assertThat(summary.autoLinked()).isEqualTo(4);
    assertThat(summary.productsWithSpecDerived()).isEqualTo(1);
    // Most common value per field across the linked offers — magnetic 2:1 over linear, tactile
    // 1 — and the seed's layout overrides the listings' "75".
    assertThat(productSpec(q3pro))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("switch_type", "magnetic", "layout_size", "full", "hot_swap", true));

    // Unchanged data → nothing rewritten.
    assertThat(runner.run(seeded, true).productsWithSpecDerived()).isZero();
  }

  @Test
  void linkedProductIsReadable() throws SQLException {
    long linked = offer("json_store", "Keychron", "Keychron Q6 HE QMK Wireless Custom Keyboard");
    long unlinked = offer("json_store", "Keychron", "Keychron K3 Pro Low Profile");
    run.run(category);
    try (Connection c = DB.connection()) {
      assertThat(dao.linkedProduct(c, linked)).isEqualTo(Optional.of(productId("Q6 HE")));
      assertThat(dao.linkedProduct(c, unlinked)).isEmpty();
      assertThat(dao.linkedProduct(c, 999_999L)).isEmpty();
    }
  }
}
