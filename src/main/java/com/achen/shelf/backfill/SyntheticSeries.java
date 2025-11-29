package com.achen.shelf.backfill;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A plausible daily price series for one listing, ending at the price the crawl first observed.
 *
 * <p>The model is what a retailer's page does, not a random walk: the price sits at list most days;
 * a few times a year it drops 10–30% for one to two weeks; every listing may join the two sales
 * every retailer runs (Black Friday week, a mid-July sale); a quarter of listings had a different
 * list price earlier in the window; and a listing is out of stock now and then. Nothing jitters — a
 * retailer does not, and the rollup's list-price detection (the year's most common price) relies on
 * that. Sale prices are floored to whole dollars, so a sale is always at or below 90% of list,
 * which is where the rollup draws its line.
 *
 * <p>Deterministic: the same seed, listing and window produce the same series, which is what makes
 * the backfill re-derivable and its second run a no-op.
 */
public final class SyntheticSeries {

  /** One day's price. */
  public record Point(LocalDate day, int priceCents, boolean inStock) {}

  /** A sale every retailer runs: a window of the year and how many listings join it. */
  private record Season(MonthDay start, MonthDay end, double participation) {
    boolean covers(LocalDate day) {
      MonthDay md = MonthDay.from(day);
      return !md.isBefore(start) && !md.isAfter(end);
    }
  }

  private static final List<Season> SEASONS =
      List.of(
          new Season(MonthDay.of(11, 24), MonthDay.of(12, 1), 0.7),
          new Season(MonthDay.of(7, 8), MonthDay.of(7, 14), 0.7));

  private SyntheticSeries() {}

  /**
   * The series for one listing over {@code [from, toExclusive)}.
   *
   * @param anchorPriceCents the first price the crawl really observed; the list price at the end of
   *     the window
   */
  public static List<Point> generate(
      long offerId, int anchorPriceCents, LocalDate from, LocalDate toExclusive, long seed) {
    if (!toExclusive.isAfter(from)) {
      return List.of();
    }
    SplittableRandom rng =
        new SplittableRandom(seed ^ Long.rotateLeft(offerId * 0x9E3779B97F4A7C15L, 17));
    int days = (int) (toExclusive.toEpochDay() - from.toEpochDay());
    int anchor = Math.max(100, anchorPriceCents);

    // A quarter of listings carried a different list price earlier on; the change lands in the
    // first 70% of the window so the series always ends at the anchor for a while.
    int listChangeDay = -1;
    int earlierList = anchor;
    if (rng.nextDouble() < 0.25) {
      listChangeDay = rng.nextInt(Math.max(1, (int) (days * 0.7)));
      earlierList =
          withCentsOf(anchor, roundToDollar((int) (anchor * (0.85 + rng.nextDouble() * 0.30))));
    }
    double saleStartPerDay = (3 + rng.nextInt(7)) / 365.0;
    boolean[] joins = new boolean[SEASONS.size()];
    double[] seasonalOff = new double[SEASONS.size()];
    for (int i = 0; i < SEASONS.size(); i++) {
      joins[i] = rng.nextDouble() < SEASONS.get(i).participation();
      seasonalOff[i] = 0.15 + rng.nextDouble() * 0.10;
    }

    List<Point> points = new ArrayList<>(days);
    int saleDaysLeft = 0;
    double saleOff = 0;
    int stockoutDaysLeft = 0;
    for (int d = 0; d < days; d++) {
      LocalDate day = from.plusDays(d);
      int list = d < listChangeDay ? earlierList : anchor;

      if (saleDaysLeft > 0) {
        saleDaysLeft--;
      } else if (rng.nextDouble() < saleStartPerDay) {
        saleDaysLeft = 2 + rng.nextInt(12); // 3–14 days including today
        saleOff = 0.10 + rng.nextDouble() * 0.20;
      } else {
        saleOff = 0;
      }
      double off = saleOff;
      for (int i = 0; i < SEASONS.size(); i++) {
        if (joins[i] && SEASONS.get(i).covers(day)) {
          off = Math.max(off, seasonalOff[i]);
        }
      }

      if (stockoutDaysLeft > 0) {
        stockoutDaysLeft--;
      } else if (rng.nextDouble() < 0.004) {
        stockoutDaysLeft = 2 + rng.nextInt(19); // 3–21 days including today
      }

      int price = off == 0 ? list : floorToDollar((int) Math.floor(list * (1 - off)));
      points.add(new Point(day, Math.max(100, price), stockoutDaysLeft == 0));
    }
    return List.copyOf(points);
  }

  private static int roundToDollar(int cents) {
    return (int) (Math.round(cents / 100.0) * 100);
  }

  /** {@code dollars} with the cents part of {@code like} ($99.00 like $89.99 is $98.99). */
  private static int withCentsOf(int like, int dollars) {
    int cents = like % 100;
    return Math.max(100, cents == 0 ? dollars : dollars - 100 + cents);
  }

  private static int floorToDollar(int cents) {
    return (cents / 100) * 100;
  }
}
