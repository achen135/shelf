package com.achen.shelf.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Pacing is tested against a fake clock: the point is the arithmetic, and a test that really waited
 * five seconds per request would be both slow and flaky.
 */
class DomainRateLimiterTest {

  private final AtomicLong now = new AtomicLong(0);
  private final List<Duration> slept = new ArrayList<>();

  private DomainRateLimiter limiter() {
    return new DomainRateLimiter(
        now::get,
        duration -> {
          slept.add(duration);
          now.addAndGet(duration.toNanos()); // a real sleep advances the clock; so does this one
        });
  }

  @Test
  void firstRequestToADomainDoesNotWait() throws InterruptedException {
    DomainRateLimiter limiter = limiter();
    limiter.configure("https://shop.test", 0.5, null);

    limiter.acquire("https://shop.test/a");

    assertThat(slept).isEmpty();
  }

  @Test
  void spacesRequestsByTheConfiguredRate() throws InterruptedException {
    DomainRateLimiter limiter = limiter();
    limiter.configure("https://shop.test", 0.5, null); // 0.5 rps -> one request every 2s

    limiter.acquire("https://shop.test/a");
    limiter.acquire("https://shop.test/b");
    limiter.acquire("https://shop.test/c");

    assertThat(slept).hasSize(2);
    assertThat(slept).allSatisfy(d -> assertThat(d).isEqualTo(Duration.ofSeconds(2)));
  }

  @Test
  void subdomainsShareOneBudget() throws InterruptedException {
    // www.example.com and cdn.example.com are one operator's servers, so they draw on one
    // bucket. A registrable domain needs a real public suffix, hence example.com rather than
    // the .test names the other cases use — under .test each host is its own budget, which is
    // the right conservative fallback but not what this case is about.
    DomainRateLimiter limiter = limiter();
    limiter.configure("https://www.example.com", 1.0, null);

    limiter.acquire("https://www.example.com/a");
    limiter.acquire("https://cdn.example.com/b");

    assertThat(slept).singleElement().isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void hostsUnderAnUnknownSuffixGetTheirOwnBudget() {
    // The conservative fallback: if we cannot tell what the registrable domain is, do not
    // assume two hosts are the same operator and merge their budgets.
    assertThat(DomainRateLimiter.registrableDomain("https://www.shop.test/a"))
        .isNotEqualTo(DomainRateLimiter.registrableDomain("https://cdn.shop.test/a"));
  }

  @Test
  void differentDomainsDoNotBlockEachOther() throws InterruptedException {
    DomainRateLimiter limiter = limiter();
    limiter.configure("https://one.test", 0.5, null);
    limiter.configure("https://two.test", 0.5, null);

    limiter.acquire("https://one.test/a");
    limiter.acquire("https://two.test/a");

    assertThat(slept).isEmpty();
  }

  @Test
  void aCrawlDelayCanOnlySlowUsDown() throws InterruptedException {
    DomainRateLimiter limiter = limiter();
    limiter.configure("https://shop.test", 1.0, Duration.ofSeconds(10)); // robots.txt asks for 10s

    limiter.acquire("https://shop.test/a");
    limiter.acquire("https://shop.test/b");

    assertThat(slept).singleElement().isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void theStrictestConfiguredLimitWins() throws InterruptedException {
    DomainRateLimiter limiter = limiter();
    limiter.configure("https://shop.test", 2.0, null); // 0.5s apart
    limiter.configure("https://shop.test", 0.25, null); // 4s apart

    limiter.acquire("https://shop.test/a");
    limiter.acquire("https://shop.test/b");

    assertThat(slept).singleElement().isEqualTo(Duration.ofSeconds(4));
  }

  @Test
  void resolvesRegistrableDomains() {
    assertThat(DomainRateLimiter.registrableDomain("https://www.keychron.com/x"))
        .isEqualTo("keychron.com");
    assertThat(DomainRateLimiter.registrableDomain("https://kbdfans.com/x"))
        .isEqualTo("kbdfans.com");
    // No public suffix to strip: the host itself is the budget.
    assertThat(DomainRateLimiter.registrableDomain("http://127.0.0.1:8080/x"))
        .isEqualTo("127.0.0.1");
  }
}
