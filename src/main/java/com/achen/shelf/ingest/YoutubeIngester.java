package com.achen.shelf.ingest;

import com.achen.shelf.config.Community;
import com.achen.shelf.crawl.DomainRateLimiter;
import com.achen.shelf.crawl.FetchResult;
import com.achen.shelf.crawl.Fetcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a YouTube channel through the YouTube Data API v3: its uploads back to the window's edge —
 * title and description — and the top-level comments under each.
 *
 * <p><b>No transcripts, and that is a finding, not an omission.</b> The official API's {@code
 * captions.download} "requires the user to have permission to edit the video", so a third party
 * cannot fetch a channel's captions through it, and YouTube's Developer Policies (§III.D.7) forbid
 * the undocumented endpoint every "transcript" library reads instead. What the policies do allow
 * with a plain API key is exactly what this reads: {@code channels.list} for the uploads playlist,
 * {@code playlistItems.list} for the videos (rather than {@code search.list}, which is metered
 * separately at 100 calls a day), {@code commentThreads.list} for the comments — one quota unit
 * each against a free 10,000 a day, no billing account (docs/Sessions.md, M8).
 *
 * <p><b>Built against the documented resource shapes, not recorded responses</b>: no key existed
 * when this was written; the fixtures' README says how to replace them with recordings.
 *
 * <p>The key travels in the {@code X-Goog-Api-Key} header rather than the {@code key=} query
 * parameter the docs show first, so that no URL that reaches a log line carries it.
 *
 * <p>Pacing: the quota is per project per day, not per second, so the limiter here is a courtesy
 * ceiling rather than a hard limit; as with Reddit, one process and one key make the per-domain
 * bucket the per-credential bucket. Retention is the hard rule: Developer Policies §III.E.4 let
 * stored API data live 30 days before it must be refreshed or deleted, which is why every
 * re-sighting refreshes {@code fetched_at} and {@link IngestRunner} prunes what has gone stale.
 *
 * <p>A video whose comments the API refuses (comments disabled; the API answers 403) counts as
 * {@code commentsUnavailable} and the run continues. A 403 on the channel or playlist call — the
 * quota's shape too — fails the community.
 */
public final class YoutubeIngester implements Ingester {

  private static final Logger log = LoggerFactory.getLogger(YoutubeIngester.class);

  /** Where the API lives; overridable so the tests can point it at a fixture server. */
  public record Endpoints(String apiBase) {
    public static Endpoints live() {
      return new Endpoints("https://www.googleapis.com/youtube/v3");
    }
  }

  /** How long a stored YouTube row may go without a re-sighting (Developer Policies §III.E.4). */
  public static final Duration RETENTION = Duration.ofDays(30);

  private static final int PLAYLIST_PAGE = 50;
  private static final int COMMENT_PAGE = 100;

  private final Fetcher fetcher;
  private final DomainRateLimiter limiter;
  private final Endpoints endpoints;
  private final ObjectMapper json = new ObjectMapper();

  public YoutubeIngester(Fetcher fetcher, DomainRateLimiter limiter, Endpoints endpoints) {
    this.fetcher = fetcher;
    this.limiter = limiter;
    this.endpoints = endpoints;
  }

  @Override
  public IngestSummary.CommunitySummary ingest(
      Community community, Map<String, String> credentials, Instant now, MentionSink sink)
      throws InterruptedException, SQLException {
    Counters n = new Counters();
    limiter.configure(endpoints.apiBase(), community.ingest().maxRps(), null);
    Instant cutoff = now.minus(Duration.ofDays(community.ingest().windowDays()));
    String key = credentials.get(community.ingest().auth().envVars().get(0));
    Map<String, String> auth = Map.of("X-Goog-Api-Key", key);

    try {
      String uploads = uploadsPlaylist(community.id(), auth, n);
      String pageToken = null;
      boolean done = false;
      while (!done && n.items < community.ingest().maxItems()) {
        String url =
            endpoints.apiBase()
                + "/playlistItems?part=snippet,contentDetails&playlistId="
                + uploads
                + "&maxResults="
                + Math.min(PLAYLIST_PAGE, community.ingest().maxItems() - n.items)
                + (pageToken == null ? "" : "&pageToken=" + pageToken);
        JsonNode page = get(url, auth, n);
        List<Mention> videos = new ArrayList<>();
        List<String> videoIds = new ArrayList<>();
        for (JsonNode item : page.path("items")) {
          JsonNode snippet = item.path("snippet");
          String videoId = snippet.path("resourceId").path("videoId").asText(null);
          if (videoId == null) {
            continue;
          }
          Instant publishedAt = rfc3339(item.path("contentDetails").path("videoPublishedAt"));
          if (publishedAt == null) {
            publishedAt = rfc3339(snippet.path("publishedAt"));
          }
          if (publishedAt != null && publishedAt.isBefore(cutoff)) {
            done = true; // the uploads playlist is newest-first
            break;
          }
          if (n.items >= community.ingest().maxItems()) {
            done = true;
            break;
          }
          n.items++;
          videos.add(
              new Mention(
                  Mention.Source.YOUTUBE_VIDEO,
                  videoId,
                  community.name(),
                  null,
                  snippet.path("title").asText(),
                  snippet.path("description").asText(""),
                  snippet
                      .path("videoOwnerChannelId")
                      .asText(snippet.path("channelId").asText(null)),
                  publishedAt,
                  "https://www.youtube.com/watch?v=" + videoId));
          videoIds.add(videoId);
        }
        n.write(sink.write(videos));

        if (community.ingest().commentsPerItem() > 0) {
          for (String videoId : videoIds) {
            n.write(sink.write(comments(community, videoId, auth, n)));
          }
        }

        pageToken =
            page.path("nextPageToken").isTextual() ? page.path("nextPageToken").asText() : null;
        if (pageToken == null || page.path("items").isEmpty()) {
          done = true;
        }
      }
      return n.summary(community, IngestSummary.Status.OK, null);
    } catch (IngestException e) {
      log.warn("{}: {}", community.name(), e.getMessage());
      return n.summary(community, IngestSummary.Status.FAILED, e.getMessage());
    }
  }

  /**
   * The channel's uploads playlist id. The config may name the channel by id ({@code UC…}) or by
   * handle ({@code @name}); {@code channels.list} takes either.
   */
  private String uploadsPlaylist(String channel, Map<String, String> auth, Counters n)
      throws InterruptedException, IngestException {
    String selector =
        channel.startsWith("@")
            ? "forHandle=" + URLEncoder.encode(channel, StandardCharsets.UTF_8)
            : "id=" + URLEncoder.encode(channel, StandardCharsets.UTF_8);
    JsonNode body = get(endpoints.apiBase() + "/channels?part=contentDetails&" + selector, auth, n);
    JsonNode uploads =
        body.path("items").path(0).path("contentDetails").path("relatedPlaylists").path("uploads");
    if (!uploads.isTextual() || uploads.asText().isBlank()) {
      throw new IngestException("channel '" + channel + "' not found, or has no uploads playlist");
    }
    return uploads.asText();
  }

  /** The top-level comments of one video, up to the configured count, most relevant first. */
  private List<Mention> comments(
      Community community, String videoId, Map<String, String> auth, Counters n)
      throws InterruptedException, IngestException {
    List<Mention> out = new ArrayList<>();
    String pageToken = null;
    int want = community.ingest().commentsPerItem();
    while (out.size() < want) {
      String url =
          endpoints.apiBase()
              + "/commentThreads?part=snippet&videoId="
              + videoId
              + "&maxResults="
              + Math.min(COMMENT_PAGE, want - out.size())
              + "&order=relevance&textFormat=plainText"
              + (pageToken == null ? "" : "&pageToken=" + pageToken);
      limiter.acquire(url);
      n.requests++;
      FetchResult result = fetcher.fetch(url, auth);
      if (result.status().orElse(0) == 403 && !result.succeeded()) {
        // Comments disabled on the video is a 403 with reason commentsDisabled; so is a spent
        // quota (quotaExceeded). Fetcher keeps no body on failure, so the two are not told apart
        // here: the video is counted as unavailable and the run goes on, and a spent quota shows
        // up as every remaining video being unavailable — visible in the summary, not fatal.
        log.info("{}: comments unavailable for video {} (HTTP 403)", community.name(), videoId);
        n.commentsUnavailable++;
        return out;
      }
      if (!result.succeeded()) {
        throw new IngestException(
            "GET "
                + url
                + " failed: "
                + result.failure().orElse("HTTP " + result.status().orElse(0)));
      }
      JsonNode page = parse(result.body().orElseThrow(), url);
      for (JsonNode thread : page.path("items")) {
        JsonNode top = thread.path("snippet").path("topLevelComment");
        JsonNode c = top.path("snippet");
        String id = top.path("id").asText(null);
        if (id == null) {
          continue;
        }
        n.comments++;
        out.add(
            new Mention(
                Mention.Source.YOUTUBE_COMMENT,
                id,
                community.name(),
                videoId,
                null,
                c.path("textOriginal").asText(c.path("textDisplay").asText("")),
                c.path("authorChannelId")
                    .path("value")
                    .asText(c.path("authorDisplayName").asText(null)),
                rfc3339(c.path("publishedAt")),
                "https://www.youtube.com/watch?v=" + videoId + "&lc=" + id));
      }
      pageToken =
          page.path("nextPageToken").isTextual() ? page.path("nextPageToken").asText() : null;
      if (pageToken == null || page.path("items").isEmpty()) {
        break;
      }
    }
    return out;
  }

  private JsonNode get(String url, Map<String, String> headers, Counters n)
      throws InterruptedException, IngestException {
    limiter.acquire(url);
    n.requests++;
    FetchResult result = fetcher.fetch(url, headers);
    if (!result.succeeded()) {
      throw new IngestException(
          "GET "
              + url
              + " failed: "
              + result.failure().orElse("HTTP " + result.status().orElse(0)));
    }
    return parse(result.body().orElseThrow(), url);
  }

  private JsonNode parse(String body, String url) throws IngestException {
    try {
      JsonNode node = json.readTree(body);
      return node == null ? MissingNode.getInstance() : node;
    } catch (IOException e) {
      throw new IngestException(url + " did not return JSON: " + e.getMessage());
    }
  }

  private static Instant rfc3339(JsonNode field) {
    if (!field.isTextual()) {
      return null;
    }
    try {
      return Instant.parse(field.asText());
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
