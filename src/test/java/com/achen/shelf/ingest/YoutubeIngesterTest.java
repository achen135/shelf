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
    assertThat(s.items()).isEqualTo(3); // page 1 whole; page 2's first upload is from 2024
    assertThat(s.comments()).isEqualTo(3);
    assertThat(s.commentsUnavailable()).isEqualTo(1); // aRouyD0wdWI answers 403
    assertThat(s.requests()).isEqualTo(6); // channel, two pages, three comment fetches
    assertThat(s.inserted()).isEqualTo(6);

    assertThat(written)
        .extracting(Mention::source, Mention::sourceId, Mention::parentSourceId)
        .containsExactly(
            tuple(Mention.Source.YOUTUBE_VIDEO, "4APvQf436YM", null),
            tuple(Mention.Source.YOUTUBE_VIDEO, "aRouyD0wdWI", null),
            tuple(Mention.Source.YOUTUBE_VIDEO, "75-H8kz5QbY", null),
            tuple(Mention.Source.YOUTUBE_COMMENT, "Ugw1XQh8o6Jzezho0y94AaABAg", "4APvQf436YM"),
            tuple(Mention.Source.YOUTUBE_COMMENT, "Ugzys_PfzxdHXfqQkv14AaABAg", "4APvQf436YM"),
            tuple(Mention.Source.YOUTUBE_COMMENT, "UgyS94MKXxoAubBkrz54AaABAg", "75-H8kz5QbY"));

    Mention video = written.get(0);
    assertThat(video.community()).isEqualTo("yt_fixture");
    assertThat(video.title()).isEqualTo("Most OP Keyboard? Lofree Hyzen");
    assertThat(video.text()).startsWith("This is probably one of the most OP keyboards");
    assertThat(video.authorRef()).isEqualTo("UCzqmTtRqjBgQ_cybekKVGHA");
    assertThat(video.postedAt()).isEqualTo(Instant.parse("2026-08-15T15:12:19Z"));
    assertThat(video.permalink()).isEqualTo("https://www.youtube.com/watch?v=4APvQf436YM");

    Mention comment = written.get(3);
    assertThat(comment.text()).startsWith("I type for a living");
    assertThat(comment.authorRef()).isEqualTo("UCscrubbedviewer000000000001");
    assertThat(comment.postedAt()).isEqualTo(Instant.parse("2026-09-14T14:48:14Z"));
    assertThat(comment.permalink())
        .isEqualTo("https://www.youtube.com/watch?v=4APvQf436YM&lc=Ugw1XQh8o6Jzezho0y94AaABAg");
    assertThat(written).extracting(Mention::sourceId).doesNotContain("fRh9IFhUCMQ", "XSHaTrc5Lcs");
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
        server.headers("/channels?part=contentDetails&forHandle=@Keybored");
    assertThat(channel).hasSize(1);
    assertThat(channel.get(0)).containsEntry("x-goog-api-key", "fixture-api-key");
    assertThat(server.userAgents()).allMatch(ua -> ua.startsWith("ShelfTest/0"));
    // The fixture paths are registered without a key= parameter; a hit on each proves the URL
    // carried none (the server would have answered 404 to a URL it did not know).
    assertThat(server.hits("/channels?part=contentDetails&forHandle=@Keybored")).isEqualTo(1);
  }

  @Test
  void anUnknownChannelFailsTheCommunityWithoutThrowing() throws Exception {
    server.serve(
        "/channels?part=contentDetails&forHandle=@Keybored",
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
    assertThat(s.failure()).contains("@Keybored").doesNotContain("fixture-api-key");
    assertThat(written).isEmpty();
  }
}
