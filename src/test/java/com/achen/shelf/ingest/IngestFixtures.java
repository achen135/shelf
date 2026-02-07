package com.achen.shelf.ingest;

import com.achen.shelf.config.AuthSpec;
import com.achen.shelf.config.Community;
import com.achen.shelf.config.CommunitySource;
import com.achen.shelf.config.IngestSpec;
import com.achen.shelf.crawl.DomainRateLimiter;
import com.achen.shelf.crawl.Fetcher;
import com.achen.shelf.testing.FixtureServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The two platforms, as served by a {@link FixtureServer}: the requests the ingesters make, in the
 * exact query-string form they make them, mapped to the fixture files (the YouTube ones recorded
 * from a real channel and scrubbed, the Reddit ones hand-built to the documented shape — each
 * directory's README says which and why).
 */
final class IngestFixtures {

  private IngestFixtures() {}

  /**
   * The run instant every ingest test uses. The YouTube fixtures were recorded on 2026-09-18 and
   * the Reddit ones hand-built around 2026-01-31, so the windows below are sized from this instant
   * to put the same items inside and outside.
   */
  static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");

  static final Map<String, String> REDDIT_CREDENTIALS =
      Map.of("T_REDDIT_ID", "fixture-client-id", "T_REDDIT_SECRET", "fixture-client-secret");

  static final Map<String, String> YOUTUBE_CREDENTIALS = Map.of("T_YOUTUBE_KEY", "fixture-api-key");

  static Community reddit(int maxItems, int commentsPerItem) {
    return new Community(
        "r_fixture",
        CommunitySource.REDDIT,
        "MechanicalKeyboards",
        true,
        new IngestSpec(
            260, // 2026-01-01 cutoff: the January posts inside, the 2025-12-15 one outside
            maxItems,
            commentsPerItem,
            2.0,
            new AuthSpec(
                AuthSpec.Mode.OAUTH2_CLIENT_CREDENTIALS,
                List.of("T_REDDIT_ID", "T_REDDIT_SECRET"))),
        null);
  }

  static Community youtube(int maxItems, int commentsPerItem) {
    return new Community(
        "yt_fixture",
        CommunitySource.YOUTUBE,
        "@Keybored",
        true,
        new IngestSpec(
            120, // 2026-05-21 cutoff: page 1's three uploads inside, page 2's first outside
            maxItems,
            commentsPerItem,
            2.0,
            new AuthSpec(AuthSpec.Mode.API_KEY, List.of("T_YOUTUBE_KEY"))),
        null);
  }

  static FixtureServer serveReddit(FixtureServer server) {
    server.serveFixture("/api/v1/access_token", "reddit/token.json", "application/json");
    server.serveFixture(
        "/r/MechanicalKeyboards/new?limit=100&raw_json=1",
        "reddit/new-page1.json",
        "application/json");
    // A page asks for min(100, what is left of max_items); the tests run with 200 and with 1.
    server.serveFixture(
        "/r/MechanicalKeyboards/new?limit=1&raw_json=1",
        "reddit/new-page1.json",
        "application/json");
    server.serveFixture(
        "/r/MechanicalKeyboards/new?limit=100&raw_json=1&after=t3_def456",
        "reddit/new-page2.json",
        "application/json");
    server.serveFixture(
        "/comments/abc123?limit=50&depth=1&sort=top&raw_json=1",
        "reddit/comments-abc123.json",
        "application/json");
    server.serveFixture(
        "/comments/ghi789?limit=50&depth=1&sort=top&raw_json=1",
        "reddit/comments-ghi789.json",
        "application/json");
    return server;
  }

  static FixtureServer serveYoutube(FixtureServer server) {
    server.serveFixture(
        "/channels?part=contentDetails&forHandle=@Keybored", // the server sees the decoded query
        "youtube/channels-forhandle.json",
        "application/json");
    server.serveFixture(
        "/playlistItems?part=snippet,contentDetails&playlistId=UUzqmTtRqjBgQ_cybekKVGHA&maxResults=50",
        "youtube/playlist-items-page1.json",
        "application/json");
    server.serveFixture(
        "/playlistItems?part=snippet,contentDetails&playlistId=UUzqmTtRqjBgQ_cybekKVGHA&maxResults=47&pageToken=EAAaHlBUOkNESWlFRVpCUlVFd1JFUkVRamxGUWpBNU16Yw",
        "youtube/playlist-items-page2.json",
        "application/json");
    server.serveFixture(
        "/commentThreads?part=snippet&videoId=4APvQf436YM&maxResults=100&order=relevance&textFormat=plainText",
        "youtube/comment-threads-4APvQf436YM.json",
        "application/json");
    server.serveSequence(
        "/commentThreads?part=snippet&videoId=aRouyD0wdWI&maxResults=100&order=relevance&textFormat=plainText",
        List.of(
            new FixtureServer.Response(
                403,
                FixtureServer.Fixtures.read("youtube/comments-disabled.json"),
                "application/json",
                Duration.ZERO,
                false)));
    server.serveFixture(
        "/commentThreads?part=snippet&videoId=75-H8kz5QbY&maxResults=100&order=relevance&textFormat=plainText",
        "youtube/comment-threads-75-H8kz5QbY.json",
        "application/json");
    return server;
  }

  /** A fetcher that does not wait between retries, so a scripted 403 fails fast. */
  static Fetcher fetcher() {
    return new Fetcher("ShelfTest/0 (+test)", 2, Duration.ofMillis(1), Duration.ofSeconds(5));
  }

  /** A limiter on a fake clock that never actually sleeps. */
  static DomainRateLimiter limiter() {
    return new DomainRateLimiter(System::nanoTime, d -> {});
  }

  static RedditIngester reddit(FixtureServer server) {
    return new RedditIngester(
        fetcher(),
        limiter(),
        new RedditIngester.Endpoints(server.url("/api/v1/access_token"), server.baseUrl()));
  }

  static YoutubeIngester youtube(FixtureServer server) {
    return new YoutubeIngester(
        fetcher(), limiter(), new YoutubeIngester.Endpoints(server.baseUrl()));
  }
}
