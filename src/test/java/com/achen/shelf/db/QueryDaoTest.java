package com.achen.shelf.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.db.QueryDao.Day;
import com.achen.shelf.db.QueryDao.Detail;
import com.achen.shelf.db.QueryDao.Hit;
import com.achen.shelf.db.QueryDao.Page;
import com.achen.shelf.db.QueryDao.Search;
import com.achen.shelf.db.QueryDao.Sort;
import com.achen.shelf.testing.PostgresTestBase;
import com.achen.shelf.testing.Storefront;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The search reads listings, prices the matched variant, and pages; the detail and history. */
class QueryDaoTest extends PostgresTestBase {

  private Storefront shop;

  @BeforeEach
  void setUp() throws SQLException {
    shop = new Storefront(DB);
  }

  private static Search search(Map<String, Object> spec, int min, int max, boolean inStock) {
    return new Search("keyboards", min, max, inStock, spec, null, Sort.DEAL, 50, 0);
  }

  private Page run(Search s) throws SQLException {
    try (Connection c = DB.connection()) {
      return QueryDao.search(c, s);
    }
  }

  @Test
  void aBareSearchReturnsEveryProductWithItsCheapestInStockListing() throws SQLException {
    Page page = run(search(Map.of(), 0, Integer.MAX_VALUE, true));

    assertThat(page.total()).isEqualTo(2);
    Hit q1 =
        page.hits().stream().filter(h -> h.product().id() == shop.q1pro).findFirst().orElseThrow();
    assertThat(q1.offer().id()).isEqualTo(shop.q1pro65);
    assertThat(q1.offer().priceCents()).isEqualTo(9900);
    assertThat(q1.price().currentPriceCents()).isEqualTo(9900);
    assertThat(q1.price().currentInStock()).isTrue();
    assertThat(q1.signal()).isNotNull();
    assertThat(q1.signal().signal()).isIn("buy", "wait", "neutral");
    assertThat(q1.product().spec()).containsEntry("hot_swap", true);
    Hit ducky =
        page.hits().stream().filter(h -> h.product().id() == shop.ducky).findFirst().orElseThrow();
    assertThat(ducky.offer().priceCents()).isEqualTo(5000);
    assertThat(ducky.price().synthetic365d()).isEqualTo(ducky.price().observations365d());
  }

  @Test
  void aSpecFilterMatchesAListingAndPricesThatListing() throws SQLException {
    // the 75% is $150 in stock; the $80 75% is out of stock and does not count by default
    Page page = run(search(Map.of("layout_size", "75"), 0, Integer.MAX_VALUE, true));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.hits().get(0).offer().id()).isEqualTo(shop.q1pro75);
    assertThat(page.hits().get(0).offer().priceCents()).isEqualTo(15000);

    // the price range applies to the matched listing, not the product's cheapest colourway
    assertThat(run(search(Map.of("layout_size", "75"), 0, 12000, true)).total()).isZero();
    assertThat(run(search(Map.of("layout_size", "65"), 0, 12000, true)).total()).isEqualTo(1);

    // out of stock allowed: a buyable listing still wins over a cheaper unbuyable one
    Page any = run(search(Map.of("layout_size", "75"), 0, Integer.MAX_VALUE, false));
    assertThat(any.hits().get(0).offer().id()).isEqualTo(shop.q1pro75);
    // ...but a range only the unbuyable one fits is honoured
    Page cheap = run(search(Map.of("layout_size", "75"), 0, 8500, false));
    assertThat(cheap.hits().get(0).offer().id()).isEqualTo(shop.q1pro75oos);
    assertThat(cheap.hits().get(0).offer().inStock()).isFalse();
  }

  @Test
  void theProductSummaryFillsInWhatAListingDidNotState() throws SQLException {
    // no listing says hot_swap; the product summary does, so a 65% hot-swap matches the 65%
    Page page =
        run(search(Map.of("layout_size", "65", "hot_swap", true), 0, Integer.MAX_VALUE, true));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.hits().get(0).offer().id()).isEqualTo(shop.q1pro65);
    // a value nothing states matches nothing
    assertThat(run(search(Map.of("layout_size", "tkl"), 0, Integer.MAX_VALUE, true)).total())
        .isZero();
    assertThat(run(search(Map.of("hot_swap", false), 0, Integer.MAX_VALUE, true)).total()).isZero();
  }

  @Test
  void sortsPagesAndFiltersBySignal() throws SQLException {
    Page byPrice =
        run(new Search("keyboards", 0, Integer.MAX_VALUE, true, Map.of(), null, Sort.PRICE, 50, 0));
    assertThat(byPrice.hits())
        .extracting(h -> h.product().id())
        .containsExactly(shop.ducky, shop.q1pro);
    Page byName =
        run(new Search("keyboards", 0, Integer.MAX_VALUE, true, Map.of(), null, Sort.NAME, 50, 0));
    assertThat(byName.hits())
        .extracting(h -> h.product().name())
        .containsExactly("Ducky One 2 SF", "Keychron Q1 Pro");

    Page second =
        run(new Search("keyboards", 0, Integer.MAX_VALUE, true, Map.of(), null, Sort.NAME, 1, 1));
    assertThat(second.total()).isEqualTo(2);
    assertThat(second.hits())
        .extracting(h -> h.product().name())
        .containsExactly("Keychron Q1 Pro");

    try (Connection c = DB.connection()) {
      DealSignalDao.upsert(
          c,
          List.of(
              new DealSignalDao.Signal(
                  shop.q1pro,
                  "buy",
                  List.of("ON_SALE", "YEAR_LOW"),
                  shop.q1pro65,
                  Storefront.AS_OF,
                  null)));
    }
    Page buys =
        run(new Search("keyboards", 0, Integer.MAX_VALUE, true, Map.of(), "buy", Sort.DEAL, 50, 0));
    assertThat(buys.hits()).extracting(h -> h.product().id()).containsExactly(shop.q1pro);
    Page deal =
        run(new Search("keyboards", 0, Integer.MAX_VALUE, true, Map.of(), null, Sort.DEAL, 50, 0));
    assertThat(deal.hits().get(0).product().id()).isEqualTo(shop.q1pro);
    assertThat(run(search(Map.of(), 0, Integer.MAX_VALUE, true).withCategory("monitors")).total())
        .isZero();
  }

  @Test
  void aProductDetailHasItsLiveListingsAndHistory() throws SQLException {
    Detail d;
    List<Day> history;
    List<Day> window;
    try (Connection c = DB.connection()) {
      d = QueryDao.product(c, shop.q1pro).orElseThrow();
      history = QueryDao.history(c, shop.q1pro, 365, Storefront.AS_OF);
      window = QueryDao.history(c, shop.q1pro, 15, Storefront.AS_OF);
      assertThat(QueryDao.product(c, 424242)).isEmpty();
    }
    assertThat(d.product().name()).isEqualTo("Keychron Q1 Pro");
    assertThat(d.price().currentPriceCents()).isEqualTo(9900);
    assertThat(d.signal()).isNotNull();
    // live listings only, cheapest in-stock first, the retired $10 one gone
    assertThat(d.offers())
        .extracting(QueryDao.Offer::id)
        .containsExactly(shop.q1pro65, shop.q1pro75, shop.q1pro75oos);
    assertThat(d.offers().get(2).inStock()).isFalse();
    assertThat(d.offers().get(0).priceCents()).isEqualTo(9900);
    assertThat(d.offers().get(0).listPriceCents()).isEqualTo(9900);

    // one row per observed day, the cheapest in-stock price that day; the $80 listing counts
    // while it is in stock (k > 20), the retired one never
    assertThat(history).hasSize(31);
    Day oldest = history.get(0);
    assertThat(oldest.day()).isEqualTo(LocalDate.of(2029, 8, 5));
    assertThat(oldest.priceCents()).isEqualTo(8000);
    assertThat(oldest.synthetic()).isFalse();
    assertThat(oldest.observations()).isEqualTo(3);
    Day newest = history.get(30);
    assertThat(newest.day()).isEqualTo(LocalDate.of(2030, 6, 1));
    assertThat(newest.priceCents()).isEqualTo(9900);
    assertThat(window).hasSize(2);
    assertThat(window.get(0).day()).isEqualTo(LocalDate.of(2030, 5, 22));
  }

  @Test
  void aProductWithNothingLiveStillHasADetail() throws SQLException {
    execute("update offers set retired_at = now() where product_id = " + shop.ducky);
    try (Connection c = DB.connection()) {
      Detail d = QueryDao.product(c, shop.ducky).orElseThrow();
      assertThat(d.offers()).isEmpty();
      assertThat(QueryDao.history(c, shop.ducky, 365, Storefront.AS_OF)).isEmpty();
      // and it no longer appears in a search: there is nothing to buy
      assertThat(QueryDao.search(c, search(Map.of(), 0, Integer.MAX_VALUE, false)).total())
          .isEqualTo(1);
    }
  }
}
