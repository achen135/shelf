package com.achen.shelf.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.db.RawMentionDao;
import com.achen.shelf.testing.FixtureServer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Reddit ingester against the documented API shape (fixtures/reddit/README.md says why the
 * fixtures are not recordings).
 */
class RedditIngesterTest {

  private FixtureServer server;
  private final List<Mention> written = new ArrayList<>();
  private final MentionSink sink =
      batch -> {
        written.addAll(batch);
        return new RawMentionDao.Written(batch.size(), 0);
      };

  @BeforeEach
  void start() {
    server = IngestFixtures.serveReddit(FixtureServer.start());
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void readsPostsToTheWindowsEdgeAndTheirTopLevelComments() throws Exception {
    IngestSummary.CommunitySummary s =
        IngestFixtures.reddit(server)
            .ingest(
                IngestFixtures.reddit(200, 50),
                IngestFixtures.REDDIT_CREDENTIALS,
                IngestFixtures.NOW,
                sink);

    assertThat(s.status()).isEqualTo(IngestSummary.Status.OK);
    // Three posts inside the window (one of them deleted), the fourth from before it ends the read.
    assertThat(s.items()).isEqualTo(3);
    assertThat(s.comments()).isEqualTo(2);
    assertThat(s.requests()).isEqualTo(5); // token, two pages, two comment fetches
    assertThat(s.inserted()).isEqualTo(4);

    assertThat(written)
        .extracting(Mention::source, Mention::sourceId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(Mention.Source.REDDIT_POST, "t3_abc123"),
            org.assertj.core.groups.Tuple.tuple(Mention.Source.REDDIT_COMMENT, "t1_c00001"),
            org.assertj.core.groups.Tuple.tuple(Mention.Source.REDDIT_POST, "t3_ghi789"),
            org.assertj.core.groups.Tuple.tuple(Mention.Source.REDDIT_COMMENT, "t1_c00010"));

    Mention post = written.get(0);
    assertThat(post.community()).isEqualTo("r_fixture");
    assertThat(post.title()).startsWith("Keychron Q1 Pro vs GMMK Pro");
    assertThat(post.text()).contains("gasket mount");
    assertThat(post.authorRef()).isEqualTo("fixture_user_one");
    assertThat(post.postedAt()).isEqualTo(Instant.parse("2026-01-30T15:00:00Z"));
    assertThat(post.permalink())
        .isEqualTo(
            "https://www.reddit.com/r/MechanicalKeyboards/comments/abc123/keychron_q1_pro_vs_gmmk_pro_after_a_year/");
    assertThat(post.parentSourceId()).isNull();

    Mention comment = written.get(1);
    assertThat(comment.parentSourceId()).isEqualTo("t3_abc123");
    assertThat(comment.title()).isNull();
    assertThat(comment.text()).startsWith("Q1 Pro.");

    // The deleted post, the removed comment and the "more" stub were not handed over.
    assertThat(written).extracting(Mention::sourceId).doesNotContain("t3_def456", "t1_c00002");
    assertThat(written).extracting(Mention::text).doesNotContain("[deleted]", "[removed]");
  }

  @Test
  void authenticatesOnceWithBasicAuthThenBearerAndKeepsTheSecretOutOfUrls() throws Exception {
    IngestFixtures.reddit(server)
        .ingest(
            IngestFixtures.reddit(200, 50),
            IngestFixtures.REDDIT_CREDENTIALS,
            IngestFixtures.NOW,
            sink);

    List<Map<String, String>> token = server.headers("/api/v1/access_token");
    assertThat(token).hasSize(1);
    // base64("fixture-client-id:fixture-client-secret")
    assertThat(token.get(0))
        .containsEntry(
            "authorization", "Basic Zml4dHVyZS1jbGllbnQtaWQ6Zml4dHVyZS1jbGllbnQtc2VjcmV0")
        .containsEntry("content-type", "application/x-www-form-urlencoded");

    List<Map<String, String>> listing =
        server.headers("/r/MechanicalKeyboards/new?limit=100&raw_json=1");
    assertThat(listing).hasSize(1);
    assertThat(listing.get(0))
        .containsEntry("authorization", "bearer fixture-bearer-token-not-real");
    assertThat(server.userAgents()).allMatch(ua -> ua.startsWith("ShelfTest/0"));
  }

  @Test
  void capsItemsAndCanSkipComments() throws Exception {
    IngestSummary.CommunitySummary s =
        IngestFixtures.reddit(server)
            .ingest(
                IngestFixtures.reddit(1, 0),
                IngestFixtures.REDDIT_CREDENTIALS,
                IngestFixtures.NOW,
                sink);

    assertThat(s.items()).isEqualTo(1);
    assertThat(s.comments()).isZero();
    assertThat(s.requests()).isEqualTo(2); // token + one page, no comment fetches
    assertThat(server.hits("/r/MechanicalKeyboards/new?limit=1&raw_json=1")).isEqualTo(1);
  }

  @Test
  void aFailedTokenRequestFailsTheCommunityWithoutThrowing() throws Exception {
    server.serveSequence(
        "/api/v1/access_token", List.of(FixtureServer.Response.status(401, "unauthorized")));

    IngestSummary.CommunitySummary s =
        IngestFixtures.reddit(server)
            .ingest(
                IngestFixtures.reddit(200, 50),
                IngestFixtures.REDDIT_CREDENTIALS,
                IngestFixtures.NOW,
                sink);

    assertThat(s.status()).isEqualTo(IngestSummary.Status.FAILED);
    assertThat(s.failure()).contains("token request failed").contains("HTTP 401");
    assertThat(s.failure()).doesNotContain("fixture-client-secret");
    assertThat(written).isEmpty();
  }
}
