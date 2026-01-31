package com.achen.shelf.ingest;

import com.achen.shelf.config.Community;
import com.achen.shelf.crawl.DomainRateLimiter;
import com.achen.shelf.crawl.FetchResult;
import com.achen.shelf.crawl.Fetcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a subreddit through the Reddit Data API: its newest posts back to the window's edge, and
 * the top comments under each.
 *
 * <p><b>Built against the documented API shape, not a recorded one.</b> Since 2025-11-11 Reddit's
 * Responsible Builder Policy gates every Data API client — free, non-commercial, read-only included
 * — behind a manual access request with no published turnaround, and no approved client existed
 * when this was written (docs/Sessions.md, M8). The endpoints, parameters and response fields below
 * are the ones Reddit's API documentation has carried for years and that every client library
 * reads; the fixtures under {@code src/test/resources/fixtures/reddit/} say in their README that
 * they are hand-built to that shape and are to be replaced by scrubbed recordings once a client is
 * approved. The unauthenticated {@code .json} endpoints were not used instead: they are outside the
 * policy and reddit.com's robots.txt disallows them.
 *
 * <p>Authentication is the "application only" OAuth2 flow: a client id and secret (from the
 * community's {@code auth.env_vars}) exchanged for a bearer token at {@code /api/v1/access_token}
 * with {@code grant_type=client_credentials}, once per run. Every API call then goes to {@code
 * oauth.reddit.com} with that token and this process's identifying User-Agent, which Reddit
 * requires to be unique and descriptive.
 *
 * <p>Pacing: the free tier allows 100 requests a minute per client. One process, one client per
 * subreddit config — so the per-registrable-domain bucket {@link DomainRateLimiter} keeps
 * <em>is</em> the per-credential bucket, and both hosts involved ({@code www.reddit.com} for the
 * token, {@code oauth.reddit.com} for the rest) fold into the one {@code reddit.com} budget. If a
 * second client id per platform ever appeared the key would have to become the credential, not the
 * domain; until then the reuse is exact, not approximate.
 *
 * <p>What is not stored: posts and comments whose author or body reads {@code [deleted]} or {@code
 * [removed]}. Content its author took down is not the community's opinion any more, and Reddit's
 * terms ask that it not be kept.
 */
public final class RedditIngester implements Ingester {

  private static final Logger log = LoggerFactory.getLogger(RedditIngester.class);

  /** Where the API lives; overridable so the tests can point it at a fixture server. */
  public record Endpoints(String tokenUrl, String apiBase) {
    public static Endpoints live() {
      return new Endpoints(
          "https://www.reddit.com/api/v1/access_token", "https://oauth.reddit.com");
    }
  }

  /** The env var names a Reddit community's {@code auth.env_vars} must list, in this order. */
  static final int CLIENT_ID = 0;

  static final int CLIENT_SECRET = 1;

  private static final int PAGE_SIZE = 100;
  private static final Set<String> TOMBSTONES = Set.of("[deleted]", "[removed]");

  private final Fetcher fetcher;
  private final DomainRateLimiter limiter;
  private final Endpoints endpoints;
  private final ObjectMapper json = new ObjectMapper();

  public RedditIngester(Fetcher fetcher, DomainRateLimiter limiter, Endpoints endpoints) {
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
    limiter.configure(endpoints.tokenUrl(), community.ingest().maxRps(), null);
    Instant cutoff = now.minus(Duration.ofDays(community.ingest().windowDays()));

    try {
      List<String> vars = community.ingest().auth().envVars();
      String token =
          token(credentials.get(vars.get(CLIENT_ID)), credentials.get(vars.get(CLIENT_SECRET)), n);
      Map<String, String> auth = Map.of("Authorization", "bearer " + token);

      String after = null;
      boolean done = false;
      while (!done && n.items < community.ingest().maxItems()) {
        String url =
            endpoints.apiBase()
                + "/r/"
                + community.id()
                + "/new?limit="
                + Math.min(PAGE_SIZE, community.ingest().maxItems() - n.items)
                + "&raw_json=1"
                + (after == null ? "" : "&after=" + after);
        JsonNode listing = get(url, auth, n).path("data");
        List<Mention> page = new ArrayList<>();
        List<String> postIds = new ArrayList<>();
        for (JsonNode child : listing.path("children")) {
          JsonNode post = child.path("data");
          Instant postedAt = epochSeconds(post.path("created_utc"));
          if (postedAt != null && postedAt.isBefore(cutoff)) {
            done = true; // /new is newest-first, so the first old post ends the window
            break;
          }
          if (n.items >= community.ingest().maxItems()) {
            done = true;
            break;
          }
          n.items++;
          if (isTombstone(post.path("author")) || isTombstone(post.path("selftext"))) {
            continue;
          }
          page.add(
              new Mention(
                  Mention.Source.REDDIT_POST,
                  post.path("name").asText(),
                  community.name(),
                  null,
                  post.path("title").asText(),
                  post.path("selftext").asText(""),
                  post.path("author").asText(null),
                  postedAt,
                  "https://www.reddit.com" + post.path("permalink").asText()));
          postIds.add(post.path("id").asText());
        }
        n.write(sink.write(page));

        if (community.ingest().commentsPerItem() > 0) {
          for (String postId : postIds) {
            n.write(sink.write(comments(community, postId, auth, n)));
          }
        }

        after = listing.path("after").isTextual() ? listing.path("after").asText() : null;
        if (after == null || listing.path("children").isEmpty()) {
          done = true;
        }
      }
      return n.summary(community, IngestSummary.Status.OK, null);
    } catch (IngestException e) {
      log.warn("{}: {}", community.name(), e.getMessage());
      return n.summary(community, IngestSummary.Status.FAILED, e.getMessage());
    }
  }

  /** The top-level comments of one post, up to the configured count. */
  private List<Mention> comments(
      Community community, String postId, Map<String, String> auth, Counters n)
      throws InterruptedException, IngestException {
    String url =
        endpoints.apiBase()
            + "/comments/"
            + postId
            + "?limit="
            + community.ingest().commentsPerItem()
            + "&depth=1&sort=top&raw_json=1";
    JsonNode body = get(url, auth, n);
    // The response is [post listing, comment listing]; the comment listing's children are the
    // top-level comments, plus a trailing "more" stub when there are more than asked for.
    JsonNode children = body.path(1).path("data").path("children");
    List<Mention> out = new ArrayList<>();
    for (JsonNode child : children) {
      if (!"t1".equals(child.path("kind").asText())) {
        continue;
      }
      JsonNode c = child.path("data");
      if (isTombstone(c.path("author")) || isTombstone(c.path("body"))) {
        continue;
      }
      n.comments++;
      out.add(
          new Mention(
              Mention.Source.REDDIT_COMMENT,
              c.path("name").asText(),
              community.name(),
              "t3_" + postId,
              null,
              c.path("body").asText(""),
              c.path("author").asText(null),
              epochSeconds(c.path("created_utc")),
              "https://www.reddit.com" + c.path("permalink").asText()));
    }
    return out;
  }

  private String token(String clientId, String clientSecret, Counters n)
      throws InterruptedException, IngestException {
    limiter.acquire(endpoints.tokenUrl());
    n.requests++;
    String basic =
        Base64.getEncoder()
            .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    FetchResult result =
        fetcher.postForm(
            endpoints.tokenUrl(),
            "grant_type=client_credentials",
            Map.of("Authorization", "Basic " + basic));
    if (!result.succeeded()) {
      throw new IngestException(
          "token request failed: " + result.failure().orElse("HTTP " + result.status().orElse(0)));
    }
    JsonNode token = parse(result.body().orElseThrow(), endpoints.tokenUrl()).path("access_token");
    if (!token.isTextual() || token.asText().isBlank()) {
      throw new IngestException("token response carried no access_token");
    }
    return token.asText();
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

  private static boolean isTombstone(JsonNode field) {
    return field.isTextual() && TOMBSTONES.contains(field.asText());
  }

  private static Instant epochSeconds(JsonNode field) {
    return field.isNumber() ? Instant.ofEpochSecond(field.asLong()) : null;
  }
}
