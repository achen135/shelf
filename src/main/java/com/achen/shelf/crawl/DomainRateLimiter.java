package com.achen.shelf.crawl;

import com.achen.shelf.db.RateLimitDao;
import crawlercommons.domains.EffectiveTldFinder;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-domain request pacing.
 *
 * <p>Keyed on the <em>registrable</em> domain (via the public-suffix list), not the host, so {@code
 * www.keychron.com} and {@code cdn.keychron.com} draw on one budget — they are one operator's
 * servers, and "be polite to keychron.com" is the promise being kept.
 *
 * <p>It is a token bucket of capacity one: requests are spaced by 1/max_rps, with no burst
 * allowance. A burst is exactly what a small retailer would notice, and the crawl has no deadline
 * that a burst would help meet.
 *
 * <p>Where the bucket lives is the {@link SlotStore}. The interval per domain is configuration and
 * stays in this process; the <em>next-allowed instant</em> is the shared state. {@link #shared}
 * keeps it in Postgres, so that N workers — separate processes on separate machines — reserve slots
 * from one bucket and together never exceed a retailer's {@code max_rps}. The in-memory store is
 * correct for a single process and is what the unit tests drive with a fake clock.
 */
public final class DomainRateLimiter {

  /** Something that can pause the calling thread; swapped out in tests. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  /**
   * Where the next-allowed instant per domain is kept.
   *
   * <p>{@link #reserve} atomically takes the next slot for a domain — pushing the domain's
   * next-allowed instant forward by {@code interval} — and returns how long the caller must wait
   * before its slot arrives. Zero means "go now".
   */
  @FunctionalInterface
  public interface SlotStore {
    Duration reserve(String domain, Duration interval);
  }

  private final Map<String, Long> intervalNanos = new ConcurrentHashMap<>();
  private final SlotStore store;
  private final Sleeper sleeper;

  /** In-memory pacing on the system clock: right for one process, wrong for a pool. */
  public DomainRateLimiter() {
    this(System::nanoTime, DomainRateLimiter::sleepFor);
  }

  /** In-memory pacing on an explicit clock and sleeper, for tests. */
  public DomainRateLimiter(LongSupplier nanoTime, Sleeper sleeper) {
    this(inMemory(nanoTime), sleeper);
  }

  public DomainRateLimiter(SlotStore store, Sleeper sleeper) {
    this.store = store;
    this.sleeper = sleeper;
  }

  /**
   * A limiter whose bucket is the {@code domain_rate_limits} table.
   *
   * <p>A database failure while reserving a slot surfaces as an unchecked exception rather than a
   * silent "go ahead": if we cannot prove the slot is ours, the fetch does not happen and the task
   * fails and is retried — a missed page is recoverable, an impolite burst is not.
   */
  public static DomainRateLimiter shared(RateLimitDao dao) {
    SlotStore store =
        (domain, interval) -> {
          try {
            return dao.reserve(domain, interval).delay();
          } catch (SQLException e) {
            throw new IllegalStateException(
                "could not reserve a rate-limit slot for " + domain + ": " + e.getMessage(), e);
          }
        };
    return new DomainRateLimiter(store, DomainRateLimiter::sleepFor);
  }

  /**
   * Sets the pace for a domain. The strictest limit registered wins, so a retailer's configured
   * max_rps can only ever be slowed by another source (such as a robots.txt Crawl-delay), never
   * loosened.
   */
  public void configure(String url, double maxRps, Duration crawlDelay) {
    long fromRps = (long) Math.ceil(Duration.ofSeconds(1).toNanos() / maxRps);
    long interval = Math.max(fromRps, crawlDelay == null ? 0L : crawlDelay.toNanos());
    intervalNanos.merge(registrableDomain(url), interval, Math::max);
  }

  /** Blocks until this domain may be requested again. */
  public void acquire(String url) throws InterruptedException {
    String domain = registrableDomain(url);
    long interval = intervalNanos.getOrDefault(domain, Duration.ofSeconds(5).toNanos());
    Duration wait = store.reserve(domain, Duration.ofNanos(interval));
    if (!wait.isZero() && !wait.isNegative()) {
      sleeper.sleep(wait);
    }
  }

  /** The registrable domain for a URL, e.g. {@code keychron.com} for {@code www.keychron.com}. */
  public static String registrableDomain(String url) {
    String host = URI.create(url).getHost();
    if (host == null) {
      return url;
    }
    String assigned = EffectiveTldFinder.getAssignedDomain(host, true);
    // Localhost and bare hostnames have no public suffix; the host itself is the right budget.
    return assigned == null ? host : assigned;
  }

  /** A store that keeps next-allowed instants in this process, on the given clock. */
  static SlotStore inMemory(LongSupplier nanoTime) {
    Map<String, Long> nextAllowedNanos = new HashMap<>();
    return (domain, interval) -> {
      synchronized (nextAllowedNanos) {
        long now = nanoTime.getAsLong();
        long nextAllowed = nextAllowedNanos.getOrDefault(domain, now);
        long startAt = Math.max(now, nextAllowed);
        nextAllowedNanos.put(domain, startAt + interval.toNanos());
        return Duration.ofNanos(startAt - now);
      }
    };
  }

  private static void sleepFor(Duration duration) throws InterruptedException {
    Thread.sleep(duration.toMillis(), duration.toNanosPart() % 1_000_000);
  }
}
