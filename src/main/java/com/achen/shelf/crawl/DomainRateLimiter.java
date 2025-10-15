package com.achen.shelf.crawl;

import crawlercommons.domains.EffectiveTldFinder;
import java.net.URI;
import java.time.Duration;
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
 * <p>State is in memory, which is correct for M1's single process and wrong for M2's worker pool —
 * N workers would each keep their own budget and together exceed it. M2 moves the next-allowed
 * timestamp into Postgres so all workers share one bucket; the interface here is deliberately the
 * one that change can keep.
 */
public final class DomainRateLimiter {

  /** Something that can pause the calling thread; swapped out in tests. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final Map<String, Long> nextAllowedNanos = new ConcurrentHashMap<>();
  private final Map<String, Long> intervalNanos = new ConcurrentHashMap<>();
  private final LongSupplier nanoTime;
  private final Sleeper sleeper;

  public DomainRateLimiter() {
    this(
        System::nanoTime,
        duration -> Thread.sleep(duration.toMillis(), duration.toNanosPart() % 1_000_000));
  }

  public DomainRateLimiter(LongSupplier nanoTime, Sleeper sleeper) {
    this.nanoTime = nanoTime;
    this.sleeper = sleeper;
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

    Duration wait;
    synchronized (this) {
      long now = nanoTime.getAsLong();
      long nextAllowed = nextAllowedNanos.getOrDefault(domain, now);
      long startAt = Math.max(now, nextAllowed);
      nextAllowedNanos.put(domain, startAt + interval);
      wait = Duration.ofNanos(startAt - now);
    }
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
}
