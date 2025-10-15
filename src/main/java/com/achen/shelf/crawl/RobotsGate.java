package com.achen.shelf.crawl;

import com.achen.shelf.db.RobotsCacheDao;
import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches, caches and applies {@code robots.txt}.
 *
 * <p>Every request the crawler makes passes through {@link #allows(String)} first. Rules are parsed
 * by crawler-commons against our own product token, cached in Postgres (so M2's workers fetch one
 * copy between them) and in memory for the life of a run.
 *
 * <p>Failure handling follows the convention crawler-commons encodes: a 4xx means there are no
 * rules and everything is allowed, while a 5xx or an unreachable server means the site's wishes are
 * unknown — and unknown is treated as "do not crawl", because guessing in our own favour is the one
 * thing a polite crawler must not do.
 */
public final class RobotsGate {

  private static final Logger log = LoggerFactory.getLogger(RobotsGate.class);
  private static final Duration CACHE_TTL = Duration.ofHours(24);

  /**
   * The product token we match robots.txt groups against — the identity in our User-Agent.
   *
   * <p>Lower-case because crawler-commons requires the robot names handed to {@code parseContent}
   * to be lower-cased; passing "ShelfBot" makes every {@code User-agent: ShelfBot} group silently
   * fail to match, and the crawler would then obey only the wildcard group — quietly ignoring a
   * site that named us specifically.
   */
  public static final String PRODUCT_TOKEN = "shelfbot";

  private final Fetcher fetcher;
  private final RobotsCacheDao cache;
  private final SimpleRobotRulesParser parser = new SimpleRobotRulesParser();
  private final Map<String, BaseRobotRules> perOrigin = new ConcurrentHashMap<>();

  public RobotsGate(Fetcher fetcher, RobotsCacheDao cache) {
    this.fetcher = fetcher;
    this.cache = cache;
  }

  /** True if this URL may be fetched. */
  public boolean allows(String url) {
    return rulesFor(url).isAllowed(url);
  }

  /** The Crawl-delay a site asked for, if any. */
  public Optional<Duration> crawlDelay(String url) {
    long delay = rulesFor(url).getCrawlDelay();
    return delay == BaseRobotRules.UNSET_CRAWL_DELAY
        ? Optional.empty()
        : Optional.of(Duration.ofMillis(delay));
  }

  private BaseRobotRules rulesFor(String url) {
    URI uri = URI.create(url);
    String origin = uri.getScheme() + "://" + uri.getAuthority();
    return perOrigin.computeIfAbsent(origin, this::load);
  }

  private BaseRobotRules load(String origin) {
    String robotsUrl = origin + "/robots.txt";
    String host = URI.create(origin).getHost();

    Optional<RobotsCacheDao.Entry> cached = readCache(host);
    if (cached.isPresent()) {
      log.debug("robots.txt for {} served from cache", host);
      return parse(robotsUrl, cached.get().body());
    }

    FetchResult result = fetcher.fetch(robotsUrl);
    if (result.succeeded()) {
      writeCache(host, result.body().orElseThrow());
      return parse(robotsUrl, result.body().orElseThrow());
    }

    int status = result.status().orElse(503);
    log.warn(
        "could not fetch {} ({}); applying crawler-commons failedFetch rules",
        robotsUrl,
        result.failure().orElse("unknown"));
    return parser.failedFetch(status);
  }

  private SimpleRobotRules parse(String robotsUrl, String body) {
    return parser.parseContent(
        robotsUrl, body.getBytes(StandardCharsets.UTF_8), "text/plain", Set.of(PRODUCT_TOKEN));
  }

  private Optional<RobotsCacheDao.Entry> readCache(String host) {
    try {
      return cache.get(host, CACHE_TTL);
    } catch (SQLException e) {
      // A cache miss is always safe: we just fetch robots.txt again.
      log.warn("robots_cache lookup failed for {}: {}", host, e.getMessage());
      return Optional.empty();
    }
  }

  private void writeCache(String host, String body) {
    try {
      // NUL bytes are legal in a served file but not in a Postgres text column, and
      // keychron.com's robots.txt contains one. The rules have already been parsed from the
      // original bytes; this only sanitises what gets cached.
      cache.put(host, body.replace("\u0000", ""));
    } catch (SQLException e) {
      log.warn("could not cache robots.txt for {}: {}", host, e.getMessage());
    }
  }
}
