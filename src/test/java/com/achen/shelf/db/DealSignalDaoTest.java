package com.achen.shelf.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.achen.shelf.db.DealSignalDao.Signal;
import com.achen.shelf.testing.PostgresTestBase;
import com.achen.shelf.testing.PriceHistory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One row per product, rewritten in place; a buy must point at a listing. */
class DealSignalDaoTest extends PostgresTestBase {

  private static final Instant AS_OF = Instant.parse("2030-06-01T00:00:00Z");

  @Test
  void writesOneRowPerProductAndReplacesIt() throws SQLException {
    PriceHistory history = new PriceHistory(DB);
    long q2 = history.product("Keychron", "Q2");
    long q4 = history.product("Keychron", "Q4");
    long offer = history.offer();

    try (Connection c = DB.connection()) {
      int written =
          DealSignalDao.upsert(
              c,
              List.of(
                  new Signal(q2, "buy", List.of("ON_SALE", "YEAR_LOW"), offer, AS_OF, null),
                  new Signal(q4, "neutral", List.of("NO_PRICE"), null, AS_OF, null)));
      assertThat(written).isEqualTo(2);

      Signal buy = DealSignalDao.forProduct(c, q2).orElseThrow();
      assertThat(buy.signal()).isEqualTo("buy");
      assertThat(buy.reasonCodes()).containsExactly("ON_SALE", "YEAR_LOW");
      assertThat(buy.bestOfferId()).isEqualTo(offer);
      assertThat(buy.asOf()).isEqualTo(AS_OF);
      assertThat(buy.computedAt()).isNotNull();
      assertThat(DealSignalDao.forProduct(c, q4).orElseThrow().bestOfferId()).isNull();

      Instant later = AS_OF.plusSeconds(3600);
      DealSignalDao.upsert(
          c,
          List.of(new Signal(q2, "wait", List.of("AT_LIST", "SALES_RECUR"), offer, later, null)));

      assertThat(count("select count(*) from deal_signals")).isEqualTo(2);
      Signal wait = DealSignalDao.forProduct(c, q2).orElseThrow();
      assertThat(wait.signal()).isEqualTo("wait");
      assertThat(wait.reasonCodes()).containsExactly("AT_LIST", "SALES_RECUR");
      assertThat(wait.asOf()).isEqualTo(later);
      assertThat(DealSignalDao.inCategory(c, PriceHistory.CATEGORY))
          .extracting(Signal::productId)
          .containsExactly(q2, q4);
      assertThat(DealSignalDao.inCategory(c, "monitors")).isEmpty();
    }
  }

  @Test
  void aBuyMustNameTheListingBehindIt() throws SQLException {
    PriceHistory history = new PriceHistory(DB);
    long q2 = history.product("Keychron", "Q2");

    try (Connection c = DB.connection()) {
      assertThatThrownBy(
              () ->
                  DealSignalDao.upsert(
                      c, List.of(new Signal(q2, "buy", List.of("ON_SALE"), null, AS_OF, null))))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("deal_signals_check");
      assertThatThrownBy(
              () ->
                  DealSignalDao.upsert(
                      c, List.of(new Signal(q2, "maybe", List.of(), null, AS_OF, null))))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("deal_signals_signal_check");
    }
  }
}
