package com.achen.shelf.ingest;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.Community;
import com.achen.shelf.config.CommunitySource;
import com.achen.shelf.crawl.DomainRateLimiter;
import com.achen.shelf.crawl.Fetcher;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.RawMentionDao;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One ingest run over a category's enabled communities, start to finish, on one thread — the {@code
 * CrawlRunner} of the community track.
 *
 * <p>One thread on purpose. A run is a few hundred API calls paced at about one a second against
 * two platforms; there is no politeness ceiling to spread over workers and no deadline a pool would
 * help meet, so the queue, the leases and the leader election the crawl needs would be machinery
 * without a job here. If a category ever configures enough communities for that to change, the seam
 * is {@link Ingester}: it already reads one community at a time.
 *
 * <p>One shot, not a cycle. Both sources are read as a window back from now, and a re-run inside
 * that window upserts the same rows; so the cadence is whatever runs {@code shelf ingest} — a
 * person, a cron entry — and must be under 30 days for the YouTube rows to be refreshed before they
 * must be deleted (see {@link YoutubeIngester#RETENTION}). M10 decides whether consensus refresh
 * wants its own schedule; this class does not pre-empt that.
 */
public final class IngestRunner {

  private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);

  private final Database db;
  private final Clock clock;
  private final Function<String, String> env;
  private final Map<CommunitySource, Ingester> ingesters;
  private final RawMentionDao mentions;

  /** Wires the live ingesters, reading credentials from the process environment. */
  public IngestRunner(Database db, Fetcher fetcher) {
    this(db, Clock.systemUTC(), System::getenv, liveIngesters(fetcher, new DomainRateLimiter()));
  }

  /** As above, with everything a test wants to control. */
  public IngestRunner(
      Database db,
      Clock clock,
      Function<String, String> env,
      Map<CommunitySource, Ingester> ingesters) {
    this.db = db;
    this.clock = clock;
    this.env = env;
    this.ingesters = Map.copyOf(ingesters);
    this.mentions = new RawMentionDao(db);
  }

  /** One in-process limiter for both platforms; see the ingesters for why per-domain is enough. */
  public static Map<CommunitySource, Ingester> liveIngesters(
      Fetcher fetcher, DomainRateLimiter limiter) {
    return Map.of(
        CommunitySource.REDDIT,
        new RedditIngester(fetcher, limiter, RedditIngester.Endpoints.live()),
        CommunitySource.YOUTUBE,
        new YoutubeIngester(fetcher, limiter, YoutubeIngester.Endpoints.live()));
  }

  /** Reads every enabled community, then prunes what the retention rule says must go. */
  public IngestSummary run(CategoryConfig category) throws SQLException, InterruptedException {
    Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    List<IngestSummary.CommunitySummary> summaries = new ArrayList<>();

    for (Community community : category.enabledCommunities()) {
      Optional<Map<String, String>> credentials =
          Credentials.resolve(community.ingest().auth(), env);
      if (credentials.isEmpty()) {
        List<String> missing = Credentials.missing(community.ingest().auth(), env);
        log.warn(
            "{}: skipped — {} not set (credentials never live in the config file)",
            community.name(),
            String.join(", ", missing));
        summaries.add(
            IngestSummary.CommunitySummary.skipped(
                community.name(), community.source(), "unset: " + String.join(", ", missing)));
        continue;
      }
      Ingester ingester = ingesters.get(community.source());
      if (ingester == null) {
        throw new IllegalStateException("no ingester for source " + community.source());
      }
      MentionSink sink =
          batch ->
              batch.isEmpty()
                  ? new RawMentionDao.Written(0, 0)
                  : db.transaction(c -> mentions.upsert(c, category.name(), batch, startedAt));
      IngestSummary.CommunitySummary s =
          ingester.ingest(community, credentials.get(), startedAt, sink);
      log.info(
          "{} ({}): {} — {} items, {} comments, {} requests, {} new, {} refreshed",
          s.community(),
          s.source(),
          s.status(),
          s.items(),
          s.comments(),
          s.requests(),
          s.inserted(),
          s.refreshed());
      summaries.add(s);
    }

    int pruned =
        mentions.pruneStale(
            List.of(Mention.Source.YOUTUBE_VIDEO, Mention.Source.YOUTUBE_COMMENT),
            YoutubeIngester.RETENTION,
            startedAt);
    if (pruned > 0) {
      log.info(
          "pruned {} youtube rows not re-sighted in {} days",
          pruned,
          YoutubeIngester.RETENTION.toDays());
    }
    return new IngestSummary(
        category.name(),
        summaries,
        pruned,
        mentions.count(category.name()),
        Duration.between(startedAt, clock.instant()));
  }
}
