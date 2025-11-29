package com.achen.shelf.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.backfill.SyntheticSeries.Point;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The generator's contract: deterministic, complete, and shaped like a retailer's page. */
class SyntheticSeriesTest {

  private static final LocalDate FROM = LocalDate.of(2029, 9, 11);
  private static final LocalDate TO = LocalDate.of(2030, 9, 11);
  private static final long SEED = 42;

  private static List<Point> series(long offerId) {
    return SyntheticSeries.generate(offerId, 12999, FROM, TO, SEED);
  }

  private static int mode(List<Point> points) {
    Map<Integer, Integer> counts = new HashMap<>();
    for (Point p : points) {
      counts.merge(p.priceCents(), 1, Integer::sum);
    }
    return counts.entrySet().stream()
        .max(Map.Entry.<Integer, Integer>comparingByValue().thenComparingInt(Map.Entry::getKey))
        .orElseThrow()
        .getKey();
  }

  @Test
  void isDeterministicInTheSeedAndTheListing() {
    assertThat(series(7)).isEqualTo(series(7));
    assertThat(series(7)).isNotEqualTo(series(8));
    assertThat(series(7)).isNotEqualTo(SyntheticSeries.generate(7, 12999, FROM, TO, SEED + 1));
  }

  @Test
  void coversEveryDayOfTheWindowOnceInOrder() {
    List<Point> points = series(1);

    assertThat(points).hasSize(365);
    assertThat(points.get(0).day()).isEqualTo(FROM);
    assertThat(points.get(364).day()).isEqualTo(TO.minusDays(1));
    for (int i = 1; i < points.size(); i++) {
      assertThat(points.get(i).day()).isEqualTo(points.get(i - 1).day().plusDays(1));
    }
    assertThat(SyntheticSeries.generate(1, 12999, TO, FROM, SEED)).isEmpty();
  }

  @Test
  void endsAtTheAnchorPriceToTheCentAndSellsAtWholeDollars() {
    for (long offer = 1; offer <= 200; offer++) {
      List<Point> points = series(offer);
      // The last 30% of the window is past any list-price change, so its mode is the anchor.
      List<Point> tail = points.subList(255, 365);
      assertThat(mode(tail)).as("offer %d", offer).isEqualTo(12999);
      // Every price is the list price (ending in .99 like the anchor) or a whole-dollar sale.
      assertThat(points)
          .allSatisfy(p -> assertThat(p.priceCents() % 100).isIn(0, 99))
          .allSatisfy(p -> assertThat(p.priceCents()).isPositive());
      // Anything below list in that tail is a sale, and a sale is at least 10% off.
      assertThat(tail)
          .filteredOn(p -> p.priceCents() != 12999)
          .allSatisfy(p -> assertThat(p.priceCents() * 10).isLessThanOrEqualTo(12999 * 9));
    }
  }

  @Test
  void someListingsHadADifferentListPriceEarlier() {
    long changed =
        IntStream.rangeClosed(1, 200)
            .filter(offer -> mode(series(offer).subList(0, 100)) != 12999)
            .count();

    assertThat(changed).isBetween(20L, 80L); // a quarter of listings, give or take
  }

  @Test
  void mostListingsJoinBlackFridayWeek() {
    LocalDate blackFriday = LocalDate.of(2029, 11, 28);
    long onSale =
        IntStream.rangeClosed(1, 200)
            .filter(
                offer ->
                    series(offer).stream()
                        .filter(p -> p.day().equals(blackFriday))
                        .anyMatch(p -> p.priceCents() * 10 <= 12999 * 9))
            .count();

    assertThat(onSale).isBetween(110L, 170L); // 70% participation, give or take
  }

  @Test
  void stockoutsAreRareButHappen() {
    long total = 0;
    long out = 0;
    for (long offer = 1; offer <= 100; offer++) {
      for (Point p : series(offer)) {
        total++;
        if (!p.inStock()) {
          out++;
        }
      }
    }

    assertThat(out).isPositive();
    assertThat((double) out / total).isLessThan(0.12);
  }
}
