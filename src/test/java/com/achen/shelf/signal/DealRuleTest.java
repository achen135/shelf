package com.achen.shelf.signal;

import static com.achen.shelf.signal.ReasonCode.AT_LIST;
import static com.achen.shelf.signal.ReasonCode.HIGH_PERCENTILE;
import static com.achen.shelf.signal.ReasonCode.LOW_PERCENTILE;
import static com.achen.shelf.signal.ReasonCode.MIDDLING_SALE;
import static com.achen.shelf.signal.ReasonCode.MILD_DISCOUNT;
import static com.achen.shelf.signal.ReasonCode.MOSTLY_SYNTHETIC;
import static com.achen.shelf.signal.ReasonCode.NEAR_YEAR_LOW;
import static com.achen.shelf.signal.ReasonCode.NO_PRICE;
import static com.achen.shelf.signal.ReasonCode.NO_SALE_HISTORY;
import static com.achen.shelf.signal.ReasonCode.ON_SALE;
import static com.achen.shelf.signal.ReasonCode.OUT_OF_STOCK;
import static com.achen.shelf.signal.ReasonCode.SALES_RARE;
import static com.achen.shelf.signal.ReasonCode.SALES_RECUR;
import static com.achen.shelf.signal.ReasonCode.THIN_HISTORY;
import static com.achen.shelf.signal.ReasonCode.YEAR_LOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.achen.shelf.db.RollupDao.Rollup;
import com.achen.shelf.db.RollupDao.SaleWindow;
import com.achen.shelf.db.RollupDao.Window;
import com.achen.shelf.signal.DealRule.Decision;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every branch of the rule, on hand-built rollup rows; the thresholds' guards. */
class DealRuleTest {

  private static final Instant AS_OF = Instant.parse("2030-06-01T00:00:00Z");
  private final DealRule rule = DealRule.defaults();

  /** A product row: list $100, year low $70, {@code saleDays} days on sale. */
  private static Rollup row(
      Integer price,
      Boolean inStock,
      double percentile,
      int saleDays,
      int observations,
      int synthetic) {
    List<SaleWindow> sales =
        saleDays == 0
            ? List.of()
            : List.of(
                new SaleWindow(
                    AS_OF.minusSeconds(86400L * 40),
                    AS_OF.minusSeconds(86400L * 30),
                    7000,
                    30,
                    saleDays));
    return new Rollup(
        null,
        7L,
        AS_OF,
        price,
        price == null ? null : AS_OF,
        inStock,
        price == null ? null : 42L,
        price == null ? null : 10000,
        new Window(price, price, price),
        new Window(price, price, price),
        new Window(price, price, price),
        price == null ? new Window(null, null, null) : new Window(7000, 10000, 10000),
        price == null ? null : percentile,
        price == null ? null : 0.1,
        observations,
        synthetic,
        sales,
        saleDays,
        sales.isEmpty() ? null : sales.get(0).end(),
        AS_OF);
  }

  private static Rollup row(int price, double percentile, int saleDays) {
    return row(price, true, percentile, saleDays, 365, 0);
  }

  @Test
  void noPriceOutOfStockOrThinHistoryIsNoCall() {
    assertThat(rule.decide(row(null, null, 0, 0, 0, 0)))
        .isEqualTo(new Decision(Signal.NEUTRAL, List.of(NO_PRICE)));
    assertThat(rule.decide(row(7000, false, 0.0, 10, 365, 0)))
        .isEqualTo(new Decision(Signal.NEUTRAL, List.of(OUT_OF_STOCK)));
    assertThat(rule.decide(row(7000, true, 0.0, 10, 29, 0)))
        .isEqualTo(new Decision(Signal.NEUTRAL, List.of(THIN_HISTORY)));
    assertThat(rule.decide(row(7000, true, 0.0, 10, 30, 0)).signal()).isEqualTo(Signal.BUY);
  }

  @Test
  void aSaleAtOrNearTheYearsLowIsABuy() {
    assertThat(rule.decide(row(7000, 0.0, 10)))
        .isEqualTo(new Decision(Signal.BUY, List.of(ON_SALE, YEAR_LOW, LOW_PERCENTILE)));
    // $73.50 is within 5% of the $70 low, even if a third of the year was cheaper
    assertThat(rule.decide(row(7350, 0.33, 60)))
        .isEqualTo(new Decision(Signal.BUY, List.of(ON_SALE, NEAR_YEAR_LOW)));
    // $73.51 is not
    assertThat(rule.decide(row(7351, 0.33, 60)))
        .isEqualTo(new Decision(Signal.NEUTRAL, List.of(ON_SALE, MIDDLING_SALE)));
  }

  @Test
  void aSaleCheaperThanMostOfTheYearIsABuy() {
    assertThat(rule.decide(row(8500, 0.20, 40)))
        .isEqualTo(new Decision(Signal.BUY, List.of(ON_SALE, LOW_PERCENTILE)));
    assertThat(rule.decide(row(8500, 0.21, 40)))
        .isEqualTo(new Decision(Signal.NEUTRAL, List.of(ON_SALE, MIDDLING_SALE)));
  }

  @Test
  void aSaleMostOfTheYearBeatIsAWait() {
    assertThat(rule.decide(row(9000, 0.50, 200)))
        .isEqualTo(new Decision(Signal.WAIT, List.of(ON_SALE, HIGH_PERCENTILE, SALES_RECUR)));
    assertThat(rule.decide(row(9000, 0.49, 200)))
        .isEqualTo(new Decision(Signal.NEUTRAL, List.of(ON_SALE, MIDDLING_SALE)));
  }

  @Test
  void listPriceOnAProductThatGoesOnSaleIsAWait() {
    assertThat(rule.decide(row(10000, 0.15, 40)))
        .isEqualTo(new Decision(Signal.WAIT, List.of(AT_LIST, LOW_PERCENTILE, SALES_RECUR)));
    assertThat(rule.decide(row(10500, 0.90, 40)))
        .isEqualTo(new Decision(Signal.WAIT, List.of(AT_LIST, HIGH_PERCENTILE, SALES_RECUR)));
    // 9% off is not a sale by the rollup's definition
    assertThat(rule.decide(row(9100, 0.30, 40)))
        .isEqualTo(new Decision(Signal.WAIT, List.of(MILD_DISCOUNT, SALES_RECUR)));
    // 10% off is
    assertThat(rule.decide(row(9000, 0.30, 40)).reasons()).startsWith(ON_SALE);
  }

  @Test
  void listPriceOnAProductThatNeverDiscountsIsNoCall() {
    assertThat(rule.decide(row(10000, 0.0, 0)))
        .isEqualTo(new Decision(Signal.NEUTRAL, List.of(AT_LIST, LOW_PERCENTILE, NO_SALE_HISTORY)));
  }

  @Test
  void aCallRestingOnSyntheticHistorySaysSo() {
    assertThat(rule.decide(row(7000, true, 0.0, 10, 365, 183)).reasons())
        .containsExactly(ON_SALE, YEAR_LOW, LOW_PERCENTILE, MOSTLY_SYNTHETIC);
    assertThat(rule.decide(row(7000, true, 0.0, 10, 366, 183)).reasons())
        .doesNotContain(MOSTLY_SYNTHETIC);
  }

  @Test
  void thresholdsAreSweepableAndGuarded() {
    DealRule strict = new DealRule(DealRule.Thresholds.defaults().withBuyPercentile(0.05));
    assertThat(strict.decide(row(8500, 0.10, 40)).signal()).isEqualTo(Signal.NEUTRAL);
    DealRule eager = new DealRule(DealRule.Thresholds.defaults().withWaitPercentile(0.30));
    assertThat(eager.decide(row(9000, 0.35, 200)).signal()).isEqualTo(Signal.WAIT);

    assertThatThrownBy(() -> new DealRule.Thresholds(0.5, 0.5, 1.05, 30, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DealRule.Thresholds(0.2, 1.5, 1.05, 30, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DealRule.Thresholds(0.2, 0.5, 0.99, 30, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DealRule.Thresholds(0.2, 0.5, 1.05, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DealRule.Thresholds(0.2, 0.5, 1.05, 30, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aProductWhoseSalesAreTooRareToWaitForGetsNoCall() {
    // The default bar is one window: any sale makes waiting advice.
    assertThat(rule.decide(row(10000, 0.15, 40)).signal()).isEqualTo(Signal.WAIT);
    // Raised to three, a product with one window in its year is a no-call at list...
    DealRule demanding = new DealRule(DealRule.Thresholds.defaults().withMinSaleWindows(3));
    assertThat(demanding.decide(row(10000, 0.15, 40)))
        .isEqualTo(new Decision(Signal.NEUTRAL, List.of(AT_LIST, LOW_PERCENTILE, SALES_RARE)));
    // ...and on a sale most of the year beat; a good sale is still a buy
    assertThat(demanding.decide(row(9000, 0.50, 200)).reasons()).endsWith(SALES_RARE);
    assertThat(demanding.decide(row(7000, 0.0, 10)).signal()).isEqualTo(Signal.BUY);
  }

  @Test
  void signalsRoundTripThroughTheirStoredNames() {
    for (Signal s : Signal.values()) {
      assertThat(Signal.fromDbValue(s.dbValue())).isEqualTo(s);
    }
    assertThat(Signal.BUY.dbValue()).isEqualTo("buy");
  }
}
