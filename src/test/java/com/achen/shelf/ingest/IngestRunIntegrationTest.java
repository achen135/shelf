package com.achen.shelf.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.config.Community;
import com.achen.shelf.config.CommunitySource;
import com.achen.shelf.testing.FixtureServer;
import com.achen.shelf.testing.PostgresTestBase;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code shelf ingest} end to end: both ingesters against the fixture server, into a real Postgres,
 * twice — the idempotency proof M8 was asked for is the row count standing still on the second run,
 * with the {@code (source, source_id)} constraint doing the work.
 */
class IngestRunIntegrationTest extends PostgresTestBase {

  private FixtureServer server;
  private CategoryConfig category;

  @BeforeEach
  void start() {
    server = FixtureServer.start();
    IngestFixtures.serveReddit(server);
    IngestFixtures.serveYoutube(server);
    // The shipped keyboards file, with its communities swapped for the fixture ones: everything
    // else about the category is real, and the loader has validated it.
    CategoryConfig shipped = new CategoryConfigLoader().load(Path.of("categories"), "keyboards");
    category =
        new CategoryConfig(
            shipped.name(),
            shipped.specSchema(),
            shipped.retailers(),
            shipped.seedProducts(),
            shipped.resolution(),
            List.of(IngestFixtures.reddit(200, 50), IngestFixtures.youtube(50, 100)));
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private IngestRunner runner(Clock clock, Map<String, String> env) {
    return new IngestRunner(
        DB,
        clock,
        env::get,
        Map.of(
            CommunitySource.REDDIT, IngestFixtures.reddit(server),
            CommunitySource.YOUTUBE, IngestFixtures.youtube(server)));
  }

  private static Map<String, String> bothCredentials() {
    Map<String, String> env = new java.util.HashMap<>(IngestFixtures.REDDIT_CREDENTIALS);
    env.putAll(IngestFixtures.YOUTUBE_CREDENTIALS);
    return env;
  }

  @Test
  void aRepeatRunWritesNoNewRows() throws Exception {
    Clock clock = Clock.fixed(IngestFixtures.NOW, ZoneOffset.UTC);

    IngestSummary first = runner(clock, bothCredentials()).run(category);
    assertThat(first.communities())
        .extracting(IngestSummary.CommunitySummary::status)
        .containsOnly(IngestSummary.Status.OK);
    assertThat(first.totalInserted()).isEqualTo(10); // 4 reddit + 6 youtube
    assertThat(first.totalRefreshed()).isZero();
    assertThat(first.rowsInCategory()).isEqualTo(10);
    assertThat(count("select count(*) from raw_mentions")).isEqualTo(10);

    IngestSummary second =
        runner(
                Clock.fixed(IngestFixtures.NOW.plus(Duration.ofHours(6)), ZoneOffset.UTC),
                bothCredentials())
            .run(category);
    assertThat(second.totalInserted()).isZero();
    assertThat(second.totalRefreshed()).isEqualTo(10);
    assertThat(count("select count(*) from raw_mentions")).isEqualTo(10);
    assertThat(count("select count(distinct (source, source_id)) from raw_mentions")).isEqualTo(10);
    // The re-sighting moved every row's fetched_at to the second run's instant.
    assertThat(count("select count(*) from raw_mentions where fetched_at = '2026-09-18T06:00:00Z'"))
        .isEqualTo(10);
  }

  @Test
  void whatWasStoredIsWhatWasSaid() throws Exception {
    runner(Clock.fixed(IngestFixtures.NOW, ZoneOffset.UTC), bothCredentials()).run(category);

    assertThat(count("select count(*) from raw_mentions where category = 'keyboards'"))
        .isEqualTo(10);
    assertThat(count("select count(*) from raw_mentions where source = 'reddit_post'"))
        .isEqualTo(2);
    assertThat(count("select count(*) from raw_mentions where source = 'reddit_comment'"))
        .isEqualTo(2);
    assertThat(count("select count(*) from raw_mentions where source = 'youtube_video'"))
        .isEqualTo(3);
    assertThat(count("select count(*) from raw_mentions where source = 'youtube_comment'"))
        .isEqualTo(3);
    assertThat(
            count(
                "select count(*) from raw_mentions where source = 'reddit_comment'"
                    + " and parent_source_id = 't3_abc123' and community = 'r_fixture'"))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from raw_mentions where source = 'youtube_video'"
                    + " and source_id = '4APvQf436YM' and title = 'Most OP Keyboard? Lofree Hyzen'"
                    + " and posted_at = '2026-08-15T15:12:19Z'"
                    + " and permalink = 'https://www.youtube.com/watch?v=4APvQf436YM'"))
        .isEqualTo(1);
    assertThat(count("select count(*) from raw_mentions where text in ('[deleted]', '[removed]')"))
        .isZero();
  }

  @Test
  void aCommunityWhoseCredentialsAreUnsetIsSkippedNotFailed() throws Exception {
    IngestSummary s =
        runner(Clock.fixed(IngestFixtures.NOW, ZoneOffset.UTC), IngestFixtures.YOUTUBE_CREDENTIALS)
            .run(category);

    assertThat(s.communities())
        .extracting(
            IngestSummary.CommunitySummary::community, IngestSummary.CommunitySummary::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("r_fixture", IngestSummary.Status.NO_CREDENTIALS),
            org.assertj.core.groups.Tuple.tuple("yt_fixture", IngestSummary.Status.OK));
    assertThat(s.communities().get(0).failure()).contains("T_REDDIT_ID");
    assertThat(s.failures()).isZero();
    assertThat(server.hits("/api/v1/access_token")).isZero();
    assertThat(count("select count(*) from raw_mentions")).isEqualTo(6);
  }

  @Test
  void youtubeRowsNotReSightedInThirtyDaysArePruned() throws Exception {
    runner(Clock.fixed(IngestFixtures.NOW, ZoneOffset.UTC), bothCredentials()).run(category);
    // Age one video and its comments past the retention window, and one Reddit row further still.
    execute(
        "update raw_mentions set fetched_at = '2026-07-01T00:00:00Z'"
            + " where source_id in ('75-H8kz5QbY', 'UgyS94MKXxoAubBkrz54AaABAg', 't3_ghi789')");

    // A run 29¾ days on: the rows fetched on the first run are inside the 30 days and stay; the
    // aged June upload and its comment have fallen out of the 120-day window by a few hours, so
    // nothing re-sights them, and they are older than 30 days — they go.
    Clock later =
        Clock.fixed(IngestFixtures.NOW.plus(Duration.ofDays(29).plusHours(18)), ZoneOffset.UTC);
    IngestSummary s = runner(later, bothCredentials()).run(category);

    assertThat(s.pruned()).isEqualTo(2);
    assertThat(
            count(
                "select count(*) from raw_mentions where source_id in"
                    + " ('75-H8kz5QbY', 'UgyS94MKXxoAubBkrz54AaABAg')"))
        .isZero();
    // Reddit rows are not under the 30-day rule and are left alone.
    assertThat(count("select count(*) from raw_mentions where source_id = 't3_ghi789'"))
        .isEqualTo(1);
  }

  @Test
  void theShippedCategoriesHaveNoEnabledCommunityWithoutAnEnvVarContract() {
    for (String name : List.of("keyboards", "monitors")) {
      CategoryConfig cfg = new CategoryConfigLoader().load(Path.of("categories"), name);
      assertThat(cfg.enabledCommunities()).isNotEmpty();
      for (Community c : cfg.enabledCommunities()) {
        assertThat(c.ingest().auth().envVars()).isNotEmpty();
      }
    }
  }
}
