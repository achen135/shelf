package com.achen.shelf.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.testing.PostgresTestBase;
import com.achen.shelf.testing.PriceHistory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Partition pruning, asserted rather than claimed: with thirteen months of partitions in place, a
 * trailing-window query plans over only the months the window can touch.
 *
 * <p>Two mechanisms, both used by the rollup statement. Constant bounds are pruned by the planner —
 * the other partitions never appear in the plan. Bounds the planner cannot evaluate ({@code now()},
 * a bound parameter) are pruned when the executor starts, and the plan says so: {@code Subplans
 * Removed: n}.
 */
class PartitionPruningTest extends PostgresTestBase {

  private static final Pattern PARTITION = Pattern.compile("price_observations_(\\d{4}_\\d{2})");
  private static final Pattern REMOVED = Pattern.compile("Subplans Removed: (\\d+)");

  private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private long offer;

  @BeforeEach
  void thirteenMonthsOfHistory() throws SQLException {
    PriceHistory history = new PriceHistory(DB);
    offer = history.offer();
    for (int monthsAgo = 12; monthsAgo >= 0; monthsAgo--) {
      history.observe(offer, now.minus(30L * monthsAgo, ChronoUnit.DAYS), 10000 + monthsAgo);
    }
  }

  /** The months whose partition overlaps {@code (from, to]}. */
  private static Set<String> monthsTouchedBy(Instant from, Instant to) {
    Set<String> months = new TreeSet<>();
    YearMonth m = YearMonth.from(from.atOffset(ZoneOffset.UTC));
    YearMonth last = YearMonth.from(to.atOffset(ZoneOffset.UTC));
    while (!m.isAfter(last)) {
      months.add(String.format("%d_%02d", m.getYear(), m.getMonthValue()));
      m = m.plusMonths(1);
    }
    return months;
  }

  private static Set<String> partitionsIn(String plan) {
    Set<String> found = new TreeSet<>();
    Matcher matcher = PARTITION.matcher(plan);
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    return found;
  }

  private static int subplansRemoved(String plan) {
    Matcher matcher = REMOVED.matcher(plan);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
  }

  private static String explain(Connection c, String sql, Instant from, Instant to)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("explain (analyze, format text) " + sql)) {
      if (from != null) {
        ps.setTimestamp(1, Timestamp.from(from));
        ps.setTimestamp(2, Timestamp.from(to));
      }
      return read(ps);
    }
  }

  private static String read(PreparedStatement ps) throws SQLException {
    StringBuilder plan = new StringBuilder();
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        plan.append(rs.getString(1)).append('\n');
      }
    }
    return plan.toString();
  }

  private static int partitionCount(Connection c) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "select count(*) from pg_inherits where inhparent ="
                    + " 'price_observations'::regclass")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  void constantBoundsPruneAtPlanTime() throws SQLException {
    Instant from = now.minus(90, ChronoUnit.DAYS);
    Set<String> expected = monthsTouchedBy(from, now);
    String sql =
        String.format(
            "select count(*) from price_observations where offer_id = %d"
                + " and observed_at > '%s' and observed_at <= '%s'",
            offer, from, now);

    try (Connection c = DB.connection()) {
      assertThat(partitionCount(c)).isGreaterThanOrEqualTo(13);
      String plan = explain(c, sql, null, null);

      assertThat(partitionsIn(plan)).isEqualTo(expected);
      assertThat(expected).hasSizeBetween(3, 4);
    }
  }

  @Test
  void boundParametersPruneAtPlanTimeToo() throws SQLException {
    Instant from = now.minus(30, ChronoUnit.DAYS);
    String sql =
        "select count(*) from price_observations where offer_id = "
            + offer
            + " and observed_at > ? and observed_at <= ?";

    try (Connection c = DB.connection()) {
      String plan = explain(c, sql, from, now);

      assertThat(partitionsIn(plan)).isEqualTo(monthsTouchedBy(from, now));
    }
  }

  @Test
  void boundsThePlannerCannotEvaluateArePrunedWhenTheExecutorStarts() throws SQLException {
    String sql =
        "select count(*) from price_observations where offer_id = "
            + offer
            + " and observed_at > now() - interval '90 days' and observed_at <= now()";

    try (Connection c = DB.connection()) {
      int partitions = partitionCount(c);
      String plan = explain(c, sql, null, null);

      int touched = monthsTouchedBy(now.minus(90, ChronoUnit.DAYS), now).size();
      assertThat(subplansRemoved(plan)).isEqualTo(partitions - touched);
      // The executor only reports what it removed; the survivors are listed by name.
      assertThat(partitionsIn(plan)).hasSize(touched);
    }
  }

  @Test
  void aQueryWithNoTimeBoundTouchesEveryPartition() throws SQLException {
    String sql = "select count(*) from price_observations where offer_id = " + offer;

    try (Connection c = DB.connection()) {
      String plan = explain(c, sql, null, null);

      assertThat(partitionsIn(plan)).hasSize(partitionCount(c));
      assertThat(subplansRemoved(plan)).isZero();
    }
  }

  @Test
  void theSurvivorsAreScannedThroughThePrimaryKey() throws SQLException {
    Instant from = now.minus(90, ChronoUnit.DAYS);
    String sql =
        String.format(
            "select observed_at, price_cents from price_observations where offer_id = %d"
                + " and observed_at > '%s' and observed_at <= '%s' order by observed_at",
            offer, from, now);

    try (Connection c = DB.connection()) {
      String plan = explain(c, sql, null, null);
      List<String> scans =
          plan.lines().filter(l -> l.contains("Scan") && l.contains("price_observations")).toList();

      assertThat(scans).isNotEmpty().allMatch(l -> l.contains("_pkey"));
    }
  }
}
