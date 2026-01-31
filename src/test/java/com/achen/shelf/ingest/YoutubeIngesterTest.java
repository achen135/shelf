package com.achen.shelf.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

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
 * The YouTube ingester against the documented resource shapes (fixtures/youtube/README.md says why
 * the fixtures are not recordings).
 */
class YoutubeIngesterTest {

  private FixtureServer server;
  private final List<Mention> written = new ArrayList<>();
  private final MentionSink sink =
      batch -> {
        written.addAll(batch);
        return new RawMentionDao.Written(batch.size(), 0);
      };

  @BeforeEach
  void start() {
    server = IngestFixtures.serveYoutube(FixtureServer.start());
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void readsUploadsToTheWindowsEdgeAndTheirTopLevelComments() throws Exception {
    IngestSummary.CommunitySummary s =
        IngestFixtures.youtube(server)
            .ingest(
                IngestFixtures.youtube(50, 100),
                IngestFixtures.YOUTUBE_CREDENTIALS,
                IngestFixtures.NOW,
                sink);

    assertThat(s.status()).isEqualTo(IngestSummary.Status.OK);
    assertThat(s.items()).isEqualTo(3); // the fourth upload is from before the window
    assertThat(s.comments()).isEqualTo(3);
    assertThat(s.commentsUnavailable()).isEqualTo(1); // vid-002 answers 403
    assertThat(s.requests()).isEqualTo(6); // channel, two pages, three comment fetches
    assertThat(s.inserted()).isEqualTo(6);

    assertThat(written)
        .extracting(Mention::source, Mention::sourceId, Mention::parentSourceId)
        .containsExactly(
            tuple(Mention.Source.YOUTUBE_VIDEO, "vid-001", null),
            tuple(Mention.Source.YOUTUBE_VIDEO, "vid-002", null),
            tuple(Mention.Source.YOUTUBE_COMMENT, "cmt-0001", "vid-001"),
            tuple(Mention.Source.YOUTUBE_COMMENT, "cmt-0002", "vid-001"),
            tuple(Mention.Source.YOUTUBE_VIDEO, "vid-003", null),
            tuple(Mention.Source.YOUTUBE_COMMENT, "cmt-0030", "vid-003"));

    Mention video = written.get(0);
    assertThat(video.community()).isEqualTo("yt_fixture");
    assertThat(video.title()).startsWith("Keychron Q1 Pro review");
    assertThat(video.text()).contains("GMMK Pro comparison");
    assertThat(video.authorRef()).isEqualTo("UCfixturechannel000000001");
    assertThat(video.postedAt()).isEqualTo(Instant.parse("2026-01-30T15:00:00Z"));
    assertThat(video.permalink()).isEqualTo("https://www.youtube.com/watch?v=vid-001");

    Mention comment = written.get(2);
    assertThat(comment.text()).isEqualTo("Bought the Q1 Pro after this. No regrets.");
    assertThat(comment.authorRef()).isEqualTo("UCfixtureviewer0000000000a");
    assertThat(comment.permalink())
        .isEqualTo("https://www.youtube.com/watch?v=vid-001&lc=cmt-0001");
    assertThat(written).extracting(Mention::sourceId).doesNotContain("vid-old");
  }

  @Test
  void sendsTheKeyAsAHeaderNeverInTheUrl() throws Exception {
    IngestFixtures.youtube(server)
        .ingest(
            IngestFixtures.youtube(50, 100),
            IngestFixtures.YOUTUBE_CREDENTIALS,
            IngestFixtures.NOW,
            sink);

    List<Map<String, String>> channel =
        server.headers("/channels?part=contentDetails&forHandle=@FixtureKeys");
    assertThat(channel).hasSize(1);
    assertThat(channel.get(0)).containsEntry("x-goog-api-key", "fixture-api-key");
    assertThat(server.userAgents()).allMatch(ua -> ua.startsWith("ShelfTest/0"));
    // The fixture paths are registered without a key= parameter; a hit on each proves the URL
    // carried none (the server would have answered 404 to a URL it did not know).
    assertThat(server.hits("/channels?part=contentDetails&forHandle=@FixtureKeys")).isEqualTo(1);
  }

  @Test
  void anUnknownChannelFailsTheCommunityWithoutThrowing() throws Exception {
    server.serve(
        "/channels?part=contentDetails&forHandle=@FixtureKeys",
        "{\"kind\":\"youtube#channelListResponse\",\"items\":[]}",
        "application/json");

    IngestSummary.CommunitySummary s =
        IngestFixtures.youtube(server)
            .ingest(
                IngestFixtures.youtube(50, 100),
                IngestFixtures.YOUTUBE_CREDENTIALS,
                IngestFixtures.NOW,
                sink);

    assertThat(s.status()).isEqualTo(IngestSummary.Status.FAILED);
    assertThat(s.failure()).contains("@FixtureKeys").doesNotContain("fixture-api-key");
    assertThat(written).isEmpty();
  }
}
