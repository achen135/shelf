package com.achen.shelf.consensus;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.Community;
import com.achen.shelf.config.CommunitySource;
import com.achen.shelf.config.IngestSpec;
import com.achen.shelf.config.SeedProduct;
import com.achen.shelf.testing.PostgresTestBase;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The consensus pass in a real Postgres: a row for every product, the window, the weights from
 * config, the quotes, and a repeat changing nothing.
 */
class ConsensusRunTest extends PostgresTestBase {

  private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

  private static Community community(String name, double weight) {
    return new Community(
        name,
        CommunitySource.YOUTUBE,
        "@" + name,
        true,
        new IngestSpec(90, 50, 100, 1.0, com.achen.shelf.config.AuthSpec.none()),
        weight,
        null);
  }

  private final CategoryConfig category =
      new CategoryConfig(
          "keyboards",
          Map.of(
              "hot_swap",
              new com.achen.shelf.config.SpecField(
                  com.achen.shelf.config.SpecType.BOOLEAN, List.of(), null)),
          List.of(),
          List.of(
              new SeedProduct("Keychron", "Q1 Pro", "Keychron Q1 Pro", Map.of()),
              new SeedProduct("Glorious", "GMMK Pro", "Glorious GMMK Pro", Map.of())),
          com.achen.shelf.config.ResolutionConfig.NONE,
          List.of(community("yt_trusted", 3.0), community("yt_other", 1.0)),
          null);

  private long product(String brand, String model) throws SQLException {
    execute(
        "insert into products (category, brand, model, brand_norm, model_norm, canonical_name) values"
            + " ('keyboards', '"
            + brand
            + "', '"
            + model
            + "', '"
            + brand.toLowerCase(java.util.Locale.ROOT)
            + "', '"
            + model.toLowerCase(java.util.Locale.ROOT)
            + "', '"
            + brand
            + " "
            + model
            + "')");
    return count("select id from products where model = '" + model + "'");
  }

  private long mention(
      long product, String community, String sentiment, String postedAt, String status)
      throws SQLException {
    String sid = "m" + count("select count(*) from raw_mentions");
    execute(
        "insert into raw_mentions (category, source, source_id, community, text, posted_at, permalink)"
            + " values ('keyboards', 'youtube_comment', '"
            + sid
            + "', '"
            + community
            + "', 'the words',"
            + (postedAt == null ? " null" : " '" + postedAt + "'")
            + ", 'https://example/"
            + sid
            + "')");
    long raw = count("select id from raw_mentions where source_id = '" + sid + "'");
    execute(
        "insert into mentions (raw_mention_id, product_id, match_score, match_status, matched_text,"
            + " sentiment, extraction_method) values ("
            + raw
            + ", "
            + product
            + ", 1.0, '"
            + status
            + "', 'x', '"
            + sentiment
            + "', 'rule')");
    return count("select id from mentions where raw_mention_id = " + raw);
  }

  @Test
  void everyProductGetsARowTheWindowHoldsAndWeightsComeFromConfig() throws Exception {
    long q1 = product("Keychron", "Q1 Pro");
    long gmmk = product("Glorious", "GMMK Pro");
    long a = mention(q1, "yt_trusted", "positive", "2026-09-10T00:00:00Z", "auto");
    long b = mention(q1, "yt_other", "negative", "2026-09-01T00:00:00Z", "auto");
    mention(q1, "yt_other", "positive", "2026-06-01T00:00:00Z", "auto"); // outside 90 days
    mention(q1, "yt_other", "positive", null, "auto"); // undatable
    mention(q1, "yt_other", "positive", "2026-09-15T00:00:00Z", "pending"); // not linked
    mention(q1, "yt_other", "positive", "2026-09-16T00:00:00Z", "rejected"); // a human said no

    ConsensusRun.Summary s = new ConsensusRun(DB, Clock.fixed(NOW, ZoneOffset.UTC)).run(category);

    assertThat(s.products()).isEqualTo(2);
    assertThat(s.withMentions()).isEqualTo(1);
    assertThat(s.mentions()).isEqualTo(2);
    assertThat(s.liked()).isEqualTo(1);
    assertThat(count("select count(*) from consensus_scores")).isEqualTo(2);
    assertThat(count("select mention_count from consensus_scores where product_id = " + gmmk))
        .isZero();
    assertThat(
            count(
                "select count(*) from consensus_scores where product_id = "
                    + gmmk
                    + " and score is null"))
        .isEqualTo(1);
    // (3·1 + 1·−1) / 4 = 0.5, from the configured weights.
    assertThat(count("select round(score * 100) from consensus_scores where product_id = " + q1))
        .isEqualTo(50);
    assertThat(count("select source_diversity from consensus_scores where product_id = " + q1))
        .isEqualTo(2);
    assertThat(count("select window_days from consensus_scores where product_id = " + q1))
        .isEqualTo(90);
    assertThat(count("select quote_mention_ids[1] from consensus_scores where product_id = " + q1))
        .isEqualTo(a);
    assertThat(count("select quote_mention_ids[2] from consensus_scores where product_id = " + q1))
        .isEqualTo(b);
    assertThat(count("select count(*) from consensus_scores where as_of = '" + NOW + "'"))
        .isEqualTo(2);

    // A repeat is the same rows; a later clock with the newest mention aged out recomputes.
    new ConsensusRun(DB, Clock.fixed(NOW, ZoneOffset.UTC)).run(category);
    assertThat(count("select count(*) from consensus_scores")).isEqualTo(2);
    assertThat(count("select round(score * 100) from consensus_scores where product_id = " + q1))
        .isEqualTo(50);
    ConsensusRun.Summary later =
        new ConsensusRun(DB, Clock.fixed(NOW.plusSeconds(86400L * 80), ZoneOffset.UTC))
            .run(category);
    assertThat(later.mentions()).isEqualTo(1); // only the 2026-09-10 one is inside now
    assertThat(count("select round(score * 100) from consensus_scores where product_id = " + q1))
        .isEqualTo(100);
  }
}
