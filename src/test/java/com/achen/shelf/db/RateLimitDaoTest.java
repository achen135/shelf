package com.achen.shelf.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.testing.PostgresTestBase;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The shared politeness bucket. The interesting case is many callers at once: the whole reason the
 * state moved into Postgres is so that N workers reserving slots for one domain get N
 * <em>distinct</em> slots, spaced by the interval, however they interleave.
 */
class RateLimitDaoTest extends PostgresTestBase {

  private final RateLimitDao dao = new RateLimitDao(DB);

  @Test
  void theFirstReservationForADomainIsImmediate() throws SQLException {
    RateLimitDao.Reservation r = dao.reserve("shop.test", Duration.ofSeconds(5));

    assertThat(r.delay()).isLessThan(Duration.ofMillis(100));
    assertThat(dao.nextAllowedAt("shop.test")).isPresent();
  }

  @Test
  void reservationsAreSpacedByTheInterval() throws SQLException {
    RateLimitDao.Reservation first = dao.reserve("shop.test", Duration.ofSeconds(2));
    RateLimitDao.Reservation second = dao.reserve("shop.test", Duration.ofSeconds(2));
    RateLimitDao.Reservation third = dao.reserve("shop.test", Duration.ofSeconds(2));

    assertThat(Duration.between(first.slotAt(), second.slotAt())).isEqualTo(Duration.ofSeconds(2));
    assertThat(Duration.between(second.slotAt(), third.slotAt())).isEqualTo(Duration.ofSeconds(2));
    assertThat(second.delay()).isBetween(Duration.ofMillis(1900), Duration.ofMillis(2100));
    assertThat(third.delay()).isBetween(Duration.ofMillis(3900), Duration.ofMillis(4100));
  }

  @Test
  void differentDomainsDoNotShareABucket() throws SQLException {
    dao.reserve("one.test", Duration.ofSeconds(5));
    RateLimitDao.Reservation other = dao.reserve("two.test", Duration.ofSeconds(5));

    assertThat(other.delay()).isLessThan(Duration.ofMillis(100));
  }

  @Test
  void aStaleBucketDoesNotOweAWait() throws Exception {
    // A domain last reserved long ago (a previous cycle) must not be treated as owing a slot
    // since then: the next reservation starts from now.
    dao.reserve("shop.test", Duration.ofMillis(200));
    Thread.sleep(400);

    RateLimitDao.Reservation r = dao.reserve("shop.test", Duration.ofMillis(200));

    assertThat(r.delay()).isLessThan(Duration.ofMillis(100));
  }

  @Test
  void concurrentReservationsForOneDomainNeverOverlap() throws Exception {
    // Eight callers on their own connections, each reserving several slots for the same domain
    // at once. Sorted, the slots must be at least the interval apart: no two callers were ever
    // handed the same slot, and the union of their requests respects the domain's max_rps.
    Duration interval = Duration.ofMillis(250);
    List<Instant> slots = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<List<Instant>>> futures = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        futures.add(
            pool.submit(
                () -> {
                  List<Instant> mine = new ArrayList<>();
                  for (int n = 0; n < 4; n++) {
                    mine.add(dao.reserve("shop.test", interval).slotAt());
                  }
                  return mine;
                }));
      }
      for (Future<List<Instant>> f : futures) {
        slots.addAll(f.get());
      }
    }

    assertThat(slots).hasSize(32).doesNotHaveDuplicates();
    List<Instant> sorted = slots.stream().sorted().toList();
    for (int i = 1; i < sorted.size(); i++) {
      assertThat(Duration.between(sorted.get(i - 1), sorted.get(i)))
          .as("gap between slot %d and %d", i - 1, i)
          .isGreaterThanOrEqualTo(interval);
    }
  }
}
