package com.achen.shelf.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.backfill.SyntheticSeries.Point;
import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.db.CrawlRunDao;
import com.achen.shelf.db.RollupDao;
import com.achen.shelf.testing.FixtureServer;
import com.achen.shelf.testing.PostgresTestBase;
import com.achen.shelf.testing.PriceHistory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The backfill against a real Postgres: what it writes, where it stops, and that it converges. */
class SyntheticBackfillTest extends PostgresTestBase {

  private static final Instant FIRST_CRAWL = Instant.parse("2030-03-15T10:00:00Z");
  private static final long SEED = 7;

  private PriceHistory history;
  private CategoryConfig category;
  private SyntheticBackfill backfill;
  private long a;
  private long b;

  @BeforeEach
  void setUp() throws SQLException {
    history = new PriceHistory(DB);
    String yaml =
        FixtureServer.Fixtures.read("categories/fixture-server.yaml.template")
            .replace("__PORT__", "1")
            .replace("name: json_store", "name: " + PriceHistory.RETAILER);
    category =
        new CategoryConfigLoader()
            .load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "keyboards");
    backfill = new SyntheticBackfill(DB, SEED);

    a = history.offer();
    b = history.offer();
    long run = history.finishedRun(FIRST_CRAWL);
    history.observe(
        a, FIRST_CRAWL, 10000, true, com.achen.shelf.db.ObservationDao.Source.OBSERVED, run);
    history.observe(
        b, FIRST_CRAWL, 25000, false, com.achen.shelf.db.ObservationDao.Source.OBSERVED, run);
    long later = history.finishedRun(FIRST_CRAWL.plusSeconds(3600 * 6));
    history.observe(
        a,
        FIRST_CRAWL.plusSeconds(3600 * 6),
        9000,
        true,
        com.achen.shelf.db.ObservationDao.Source.OBSERVED,
        later);
  }

  private record Row(Instant at, int priceCents, boolean inStock, String source, long runId) {}

  private List<Row> rows(long offerId) throws SQLException {
    try (Connection c = DB.connection();
        PreparedStatement ps =
            c.prepareStatement(
                "select observed_at, price_cents, in_stock, source, crawl_run_id"
                    + " from price_observations where offer_id = ? order by observed_at")) {
      ps.setLong(1, offerId);
      try (ResultSet rs = ps.executeQuery()) {
        List<Row> out = new ArrayList<>();
        while (rs.next()) {
          out.add(
              new Row(
                  rs.getTimestamp(1).toInstant(),
                  rs.getInt(2),
                  rs.getBoolean(3),
                  rs.getString(4),
                  rs.getLong(5)));
        }
        return out;
      }
    }
  }

  @Test
  void writesTheGeneratorsSeriesLabeledSyntheticEndingTheDayBeforeTheFirstRealObservation()
      throws SQLException {
    SyntheticBackfill.Summary s = backfill.run(category, 40);

    assertThat(s.offers()).isEqualTo(2);
    assertThat(s.rows()).isEqualTo(80);
    assertThat(s.toExclusive()).isEqualTo(LocalDate.of(2030, 3, 15));
    assertThat(s.from()).isEqualTo(LocalDate.of(2030, 2, 3));

    List<Row> synthetic = rows(a).stream().filter(r -> r.source().equals("synthetic")).toList();
    List<Point> expected = SyntheticSeries.generate(a, 10000, s.from(), s.toExclusive(), SEED);
    assertThat(synthetic).hasSize(40);
    for (int i = 0; i < 40; i++) {
      Row row = synthetic.get(i);
      Point p = expected.get(i);
      assertThat(row.at()).isEqualTo(p.day().atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC));
      assertThat(row.priceCents()).isEqualTo(p.priceCents());
      assertThat(row.inStock()).isEqualTo(p.inStock());
      assertThat(row.runId()).isEqualTo(s.runId());
    }
    assertThat(synthetic.get(39).at()).isBefore(FIRST_CRAWL);
    // b's anchor is its first observed price, even though that observation was out of stock
    assertThat(rows(b).get(0).priceCents())
        .isEqualTo(
            SyntheticSeries.generate(b, 25000, s.from(), s.toExclusive(), SEED)
                .get(0)
                .priceCents());
    // the real rows are untouched
    assertThat(rows(a).stream().filter(r -> r.source().equals("observed"))).hasSize(2);
    assertThat(count("select count(*) from price_observations where source = 'synthetic'"))
        .isEqualTo(80);
  }

  @Test
  void recordsItselfAsABackfillRunThatNoCycleQuestionCanMistakeForACrawl() throws SQLException {
    SyntheticBackfill.Summary s = backfill.run(category, 40);

    assertThat(
            count(
                "select count(*) from crawl_runs where kind = 'backfill' and id = "
                    + s.runId()
                    + " and started_at = '2030-02-03 12:00:00+00'"
                    + " and finished_at = '2030-03-14 12:00:00+00' and worker_count = 0"))
        .isEqualTo(1);
    try (Connection c = DB.connection()) {
      assertThat(CrawlRunDao.lastFinishedAt(c, PriceHistory.CATEGORY))
          .hasValue(FIRST_CRAWL.plusSeconds(3600 * 6 + 60));
    }
  }

  @Test
  void createsThePartitionsItNeeds() throws SQLException {
    backfill.run(category, 400);

    assertThat(
            count(
                "select count(*) from pg_inherits where inhparent = 'price_observations'::regclass"
                    + " and inhrelid::regclass::text in ('price_observations_2029_02',"
                    + " 'price_observations_2029_06', 'price_observations_2030_03')"))
        .isEqualTo(3);
    assertThat(count("select count(*) from price_observations where source = 'synthetic'"))
        .isEqualTo(800);
  }

  @Test
  void convergesOnRepeatAndFillsInNewOffersOnly() throws SQLException {
    backfill.run(category, 40);
    SyntheticBackfill.Summary again = backfill.run(category, 40);

    assertThat(again.rows()).isZero();
    assertThat(again.runId()).isNull();
    assertThat(count("select count(*) from crawl_runs where kind = 'backfill'")).isEqualTo(1);

    long newcomer = history.offer();
    history.observe(newcomer, FIRST_CRAWL.plusSeconds(86400), 5000);
    SyntheticBackfill.Summary third = backfill.run(category, 40);

    assertThat(third.offers()).isEqualTo(1);
    assertThat(third.rows()).isEqualTo(40);
    assertThat(third.from()).isEqualTo(LocalDate.of(2030, 2, 3));
    assertThat(count("select count(*) from price_observations where source = 'synthetic'"))
        .isEqualTo(120);
  }

  @Test
  void ignoresOtherRetailersAndOffersNeverObserved() throws SQLException {
    long elsewhere = history.offer("other-shop");
    history.observe(elsewhere, FIRST_CRAWL, 1000);
    long unobserved = history.offer();

    backfill.run(category, 10);

    assertThat(rows(elsewhere)).hasSize(1);
    assertThat(rows(unobserved)).isEmpty();
  }

  @Test
  void writesNothingBeforeTheFirstCrawl() throws SQLException {
    execute("truncate price_observations, crawl_runs restart identity cascade");

    SyntheticBackfill.Summary s = backfill.run(category, 40);

    assertThat(s.rows()).isZero();
    assertThat(s.runId()).isNull();
  }

  @Test
  void theRollupCountsWhatIsSynthetic() throws SQLException {
    SyntheticBackfill.Summary s = backfill.run(category, 40);
    Instant asOf = FIRST_CRAWL.plusSeconds(86400);

    try (Connection c = DB.connection()) {
      RollupDao.recomputeOffers(c, List.of(a), asOf);
      RollupDao.Rollup r = RollupDao.offerRollup(c, a).orElseThrow();
      assertThat(r.observations365d()).isEqualTo(42);
      assertThat(r.synthetic365d()).isEqualTo(40);
      assertThat(r.currentPriceCents()).isEqualTo(9000);
      assertThat(s.runId()).isNotNull();
    }
  }
}
