package com.achen.shelf.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.db.OfferDao;
import com.achen.shelf.testing.PostgresTestBase;
import com.achen.shelf.testing.PriceHistory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The retirement rule ({@link OfferDao#retireUnseen}): an offer stops counting as current once it
 * has gone unseen for N consecutive completed crawl cycles, and comes back the moment a crawl sees
 * it again.
 */
class OfferRetirementTest extends PostgresTestBase {

  private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
  private static final List<String> RETAILERS = List.of(PriceHistory.RETAILER);

  private PriceHistory history;

  @BeforeEach
  void setUp() {
    history = new PriceHistory(DB);
  }

  private static Instant cycle(int n) {
    return T0.plus(6L * n, ChronoUnit.HOURS);
  }

  private List<OfferDao.Retired> retire(int missedCycles) throws SQLException {
    try (Connection c = DB.connection()) {
      return OfferDao.retireUnseen(c, PriceHistory.CATEGORY, RETAILERS, missedCycles, cycle(10));
    }
  }

  private Instant retiredAt(long offerId) throws SQLException {
    try (Connection c = DB.connection();
        PreparedStatement ps = c.prepareStatement("select retired_at from offers where id = ?")) {
      ps.setLong(1, offerId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        var t = rs.getTimestamp(1);
        return t == null ? null : t.toInstant();
      }
    }
  }

  @Test
  void retiresAnOfferUnseenForThreeCompletedCycles() throws SQLException {
    long gone = history.offer();
    long seenInTheThirdMostRecent = history.offer();
    long seenLastCycle = history.offer();
    for (int i = 1; i <= 4; i++) {
      history.finishedRun(cycle(i));
    }
    // Cycles 2, 3, 4 are the three most recent; the cutoff is cycle 2's start.
    history.lastSeen(gone, cycle(1).plusSeconds(30));
    history.lastSeen(seenInTheThirdMostRecent, cycle(2).plusSeconds(30));
    history.lastSeen(seenLastCycle, cycle(4).plusSeconds(30));

    List<OfferDao.Retired> retired = retire(3);

    assertThat(retired).extracting(OfferDao.Retired::offerId).containsExactly(gone);
    assertThat(retiredAt(gone)).isEqualTo(cycle(10));
    assertThat(retiredAt(seenInTheThirdMostRecent)).isNull();
    assertThat(retiredAt(seenLastCycle)).isNull();
  }

  @Test
  void needsThatManyFinishedCyclesOfEvidence() throws SQLException {
    long old = history.offer();
    history.lastSeen(old, T0.minus(30, ChronoUnit.DAYS));
    history.finishedRun(cycle(1));
    history.finishedRun(cycle(2));

    assertThat(retire(3)).isEmpty();
    assertThat(retiredAt(old)).isNull();

    history.finishedRun(cycle(3));
    assertThat(retire(3)).extracting(OfferDao.Retired::offerId).containsExactly(old);
  }

  @Test
  void anOpenRunAndABackfillRunAreNotCycles() throws SQLException {
    long old = history.offer();
    history.lastSeen(old, T0.minus(30, ChronoUnit.DAYS));
    history.finishedRun(cycle(1));
    history.finishedRun(cycle(2));
    history.openRun(cycle(3));
    history.run(T0.minus(400, ChronoUnit.DAYS), T0.minus(1, ChronoUnit.DAYS), "backfill");

    assertThat(retire(3)).isEmpty();
  }

  @Test
  void aRetiredOfferIsReportedOnceAndCarriesItsProduct() throws SQLException {
    long product = history.product("Keychron", "Q2");
    long linked = history.offer();
    history.link(linked, product, "auto");
    long unlinked = history.offer();
    for (int i = 1; i <= 3; i++) {
      history.finishedRun(cycle(i));
    }
    history.lastSeen(linked, T0);
    history.lastSeen(unlinked, T0);

    List<OfferDao.Retired> first = retire(3);
    assertThat(first)
        .containsExactlyInAnyOrder(
            new OfferDao.Retired(linked, product), new OfferDao.Retired(unlinked, null));
    assertThat(retire(3)).isEmpty();
  }

  @Test
  void aCrawlThatSeesTheOfferAgainRevivesIt() throws SQLException {
    long offer = history.offer();
    for (int i = 1; i <= 3; i++) {
      history.finishedRun(cycle(i));
    }
    history.lastSeen(offer, T0);
    retire(3);
    assertThat(retiredAt(offer)).isNotNull();

    // The same URL, upserted by a later crawl.
    long again =
        new OfferDao(DB)
            .upsert(
                new OfferDao.Listing(
                    PriceHistory.RETAILER,
                    "https://shop.test/p/1",
                    "Listing 1",
                    null,
                    "USD",
                    "Brand",
                    "brand",
                    "{}"));

    assertThat(again).isEqualTo(offer);
    assertThat(retiredAt(offer)).isNull();
  }

  @Test
  void otherRetailersAreLeftAlone() throws SQLException {
    long elsewhere = history.offer("other-shop");
    for (int i = 1; i <= 3; i++) {
      history.finishedRun(cycle(i));
    }
    history.lastSeen(elsewhere, T0);

    assertThat(retire(3)).isEmpty();
    assertThat(retiredAt(elsewhere)).isNull();
  }
}
