package com.achen.shelf.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.achen.shelf.testing.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The schema the crawl writes into, and the partition helper that keeps it writable. */
class MigrationAndPartitionTest extends PostgresTestBase {

  private final PartitionDao partitions = new PartitionDao(DB);

  @Test
  void migratesFromAnEmptyDatabase() throws SQLException {
    assertThat(tables())
        .contains(
            "products",
            "offers",
            "price_observations",
            "price_rollups",
            "deal_signals",
            "crawl_runs",
            "raw_fetches",
            "robots_cache");
  }

  @Test
  void bootstrapsThisMonthAndNext() throws SQLException {
    List<String> partitionNames = partitionNames();
    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);

    assertThat(partitionNames)
        .contains(
            "price_observations_" + String.format("%d_%02d", now.getYear(), now.getMonthValue()));
    ZonedDateTime next = now.plusMonths(1);
    assertThat(partitionNames)
        .contains(
            "price_observations_" + String.format("%d_%02d", next.getYear(), next.getMonthValue()));
  }

  @Test
  void cutsPartitionsOnUtcMonthBoundaries() throws SQLException {
    // The bug this guards: date_trunc and timestamptz literals resolve against the session's
    // TimeZone, so a client in America/New_York would otherwise create partitions running
    // 04:00Z to 04:00Z — and the next client, in UTC, could not create the following month
    // without overlapping it.
    partitions.ensurePartition(Instant.parse("2030-03-15T12:00:00Z"));

    assertThat(boundsOf("price_observations_2030_03"))
        .isEqualTo("FOR VALUES FROM ('2030-03-01 00:00:00+00') TO ('2030-04-01 00:00:00+00')");
  }

  @Test
  void createsTheSameBoundsFromAnySessionTimezone() throws SQLException {
    partitions.ensurePartition(Instant.parse("2031-06-10T00:00:00Z"));

    // Clients on the other side of the world must be able to create the adjacent months.
    // These use their own connections rather than the pool: `set time zone` is session state
    // that outlives a pooled connection's return, so borrowing one and changing its zone would
    // quietly re-tune every later query in the suite.
    createPartitionInSession("Asia/Tokyo", "2031-07-10 00:00:00+00");
    createPartitionInSession("America/New_York", "2031-08-10 00:00:00+00");

    assertThat(boundsOf("price_observations_2031_07"))
        .isEqualTo("FOR VALUES FROM ('2031-07-01 00:00:00+00') TO ('2031-08-01 00:00:00+00')");
    assertThat(boundsOf("price_observations_2031_08"))
        .isEqualTo("FOR VALUES FROM ('2031-08-01 00:00:00+00') TO ('2031-09-01 00:00:00+00')");
  }

  @Test
  void isIdempotent() throws SQLException {
    String first = partitions.ensurePartition(Instant.parse("2032-01-05T00:00:00Z"));
    String second = partitions.ensurePartition(Instant.parse("2032-01-25T23:59:59Z"));

    assertThat(first).isEqualTo("price_observations_2032_01").isEqualTo(second);
    assertThat(partitionNames()).filteredOn("price_observations_2032_01"::equals).hasSize(1);
  }

  @Test
  void refusesAnObservationWithNoPartition() throws SQLException {
    // No DEFAULT partition, on purpose: a row outside every known month should fail loudly
    // rather than land in a catch-all that later blocks ATTACH for that month.
    long runId = new CrawlRunDao(DB).open("keyboards", Instant.now(), 1);
    long offerId =
        new OfferDao(DB)
            .upsert(
                new OfferDao.Listing(
                    "shop", "https://shop.test/p/1", "B M", null, "USD", "B", "b", "{}"));
    ObservationDao observations = new ObservationDao(DB);

    assertThatThrownBy(
            () ->
                observations.record(
                    offerId,
                    Instant.parse("1999-01-01T00:00:00Z"),
                    1000,
                    0,
                    true,
                    runId,
                    ObservationDao.Source.OBSERVED))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("partition");
  }

  private static List<String> tables() throws SQLException {
    return queryStrings(
        "select table_name from information_schema.tables where table_schema = 'public'");
  }

  private static List<String> partitionNames() throws SQLException {
    return queryStrings(
        "select c.relname from pg_class c join pg_inherits i on i.inhrelid = c.oid"
            + " where i.inhparent = 'price_observations'::regclass");
  }

  private static void createPartitionInSession(String timeZone, String timestamp)
      throws SQLException {
    try (Connection c = directConnection();
        var ps =
            c.prepareStatement(
                "select set_config('TimeZone', ?, false), ensure_price_partition(?::timestamptz)")) {
      ps.setString(1, timeZone);
      ps.setString(2, timestamp);
      ps.execute();
    }
  }

  /**
   * The partition's bounds, rendered in UTC.
   *
   * <p>pg_get_expr renders a timestamptz in the reading session's TimeZone, so this reads in a
   * session pinned to UTC — otherwise the assertion would be about the reader's zone rather than
   * about where the boundary actually falls.
   */
  private static String boundsOf(String partition) throws SQLException {
    try (Connection c = directConnection();
        Statement s = c.createStatement()) {
      s.execute("set time zone 'UTC'");
      try (var ps =
          c.prepareStatement(
              "select pg_get_expr(c.relpartbound, c.oid) from pg_class c where c.relname = ?")) {
        ps.setString(1, partition);
        try (var rs = ps.executeQuery()) {
          if (!rs.next()) {
            throw new AssertionError("no such partition: " + partition);
          }
          return rs.getString(1);
        }
      }
    }
  }

  private static Connection directConnection() throws SQLException {
    return java.sql.DriverManager.getConnection(jdbcUrl(), dbUser(), dbPassword());
  }

  private static List<String> queryStrings(String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection c = DB.connection();
        Statement s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1));
      }
    }
    return values;
  }
}
