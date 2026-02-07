package com.achen.shelf.mention;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.SeedProduct;
import com.achen.shelf.config.SentimentConfig;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.MentionDao;
import com.achen.shelf.db.ProductDao;
import com.achen.shelf.db.RawMentionDao;
import com.achen.shelf.ingest.Mention;
import com.achen.shelf.resolve.MentionMatcher;
import com.achen.shelf.resolve.Resolver;
import com.achen.shelf.testing.PostgresTestBase;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pass end to end in a real Postgres: seeds (with aliases) bootstrapped, raw mentions decided,
 * rows written, a repeat changing nothing, a human's row kept, and the eval joining the labels.
 */
class MentionRunTest extends PostgresTestBase {

  private CategoryConfig category;
  private MentionRun run;

  @BeforeEach
  void setUp() {
    category =
        new CategoryConfig(
            "keyboards",
            Map.of(
                "layout_size",
                new com.achen.shelf.config.SpecField(
                    com.achen.shelf.config.SpecType.STRING, List.of("75", "60"), null)),
            List.of(),
            List.of(
                new SeedProduct("Keychron", "Q1 Pro", "Keychron Q1 Pro", Map.of()),
                new SeedProduct(
                    "Keychron", "Q8", "Keychron Q8 (Alice)", Map.of(), List.of("Q8 Alice")),
                new SeedProduct("Glorious", "GMMK Pro", "Glorious GMMK Pro", Map.of()),
                new SeedProduct("HHKB", "Studio", "HHKB Studio", Map.of())),
            com.achen.shelf.config.ResolutionConfig.NONE,
            List.of(),
            new SentimentConfig(List.of("creamy"), List.of("mushy")));
    run = new MentionRun(DB, Resolver.Thresholds.defaults());
  }

  private long raw(String sourceId, String title, String text) throws SQLException {
    RawMentionDao dao = new RawMentionDao(DB);
    DB.transaction(
        c ->
            dao.upsert(
                c,
                "keyboards",
                List.of(
                    new Mention(
                        title == null
                            ? Mention.Source.YOUTUBE_COMMENT
                            : Mention.Source.YOUTUBE_VIDEO,
                        sourceId,
                        "yt_test",
                        null,
                        title,
                        text,
                        "author",
                        Instant.parse("2026-01-30T15:00:00Z"),
                        "https://www.youtube.com/watch?v=" + sourceId)),
                Instant.parse("2026-01-31T12:00:00Z")));
    return count("select id from raw_mentions where source_id = '" + sourceId + "'");
  }

  @Test
  void bootstrapsSeedsWithTheirAliasesThenDecidesEveryRawMention() throws Exception {
    raw(
        "c1",
        null,
        "I started with a keychron q1 pro and I love it. It has been a year. Then I bought a gmmk"
            + " pro and it sucked.");
    raw("c2", null, "my keychron q8 alice is so creamy");
    raw("c3", null, "i need one for my studio");
    raw("v1", "Top 3 for 2026", "1. Keychron Q1 Pro\n2. Glorious GMMK Pro\n3. HHKB Studio");

    MentionRun.Summary s = run.run(category);

    assertThat(s.catalogSize()).isEqualTo(4);
    assertThat(s.rawMentions()).isEqualTo(4);
    assertThat(s.rawWithAMatch()).isEqualTo(3); // c3 names nothing: a bare word without its maker
    assertThat(s.autoLinked()).isEqualTo(6); // c1: 2, c2: 1, v1: 3
    assertThat(s.proposed()).isZero();
    assertThat(s.ranked()).isEqualTo(3);
    assertThat(count("select count(*) from mentions")).isEqualTo(6);
    assertThat(count("select count(*) from mentions where match_status = 'auto'")).isEqualTo(6);
    assertThat(count("select count(*) from mentions where extraction_method = 'rule'"))
        .isEqualTo(6);
    // The alias landed in products.aliases and was what matched c2.
    assertThat(
            count(
                "select count(*) from products where model_norm = 'q8' and aliases = '{\"Q8 Alice\"}'"))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from mentions m join raw_mentions r on r.id = m.raw_mention_id"
                    + " where r.source_id = 'c2' and m.matched_text = 'q8 alice'"
                    + " and m.sentiment = 'positive'"
                    + " and 'matched the alias ''q8 alice''' = any (m.match_reasons)"))
        .isEqualTo(1);
    // c1: two products in one text, each read in the sentence naming it (and its neighbours).
    assertThat(
            count(
                "select count(*) from mentions m join raw_mentions r on r.id = m.raw_mention_id"
                    + " join products p on p.id = m.product_id where r.source_id = 'c1'"
                    + " and ((p.model_norm = 'q1 pro' and m.sentiment = 'positive')"
                    + "   or (p.model_norm = 'gmmk pro' and m.sentiment = 'negative'))"))
        .isEqualTo(2);
    // v1: explicit ranks read off the list markers.
    assertThat(
            count(
                "select count(*) from mentions m join raw_mentions r on r.id = m.raw_mention_id"
                    + " join products p on p.id = m.product_id where r.source_id = 'v1'"
                    + " and ((p.model_norm = 'q1 pro' and m.explicit_rank = 1)"
                    + "   or (p.model_norm = 'gmmk pro' and m.explicit_rank = 2)"
                    + "   or (p.model_norm = 'studio' and m.explicit_rank = 3))"))
        .isEqualTo(3);
  }

  @Test
  void aRepeatPassChangesNothingAndAHumansRowSurvives() throws Exception {
    raw("c1", null, "the keychron q1 pro is great");
    raw("c4", null, "the gmmk pro is fine");
    run.run(category);
    assertThat(count("select count(*) from mentions")).isEqualTo(2);
    // A human rejects the gmmk pro row; the machine's rows are rewritten around it.
    execute(
        "update mentions set match_status = 'rejected' where product_id ="
            + " (select id from products where model_norm = 'gmmk pro')");
    long rejectedId = count("select id from mentions where match_status = 'rejected'");

    MentionRun.Summary again = run.run(category);

    assertThat(again.autoLinked()).isEqualTo(2);
    assertThat(again.keptByHuman()).isEqualTo(1);
    assertThat(count("select count(*) from mentions")).isEqualTo(2);
    assertThat(count("select id from mentions where match_status = 'rejected'"))
        .isEqualTo(rejectedId);
    assertThat(count("select count(*) from mentions where match_status = 'auto'")).isEqualTo(1);
  }

  @Test
  void anAliasAddedToTheConfigTakesEffectOnTheNextPass() throws Exception {
    raw("c5", null, "the glorious pro is the one everyone started on");
    assertThat(run.run(category).autoLinked()).isZero();

    CategoryConfig withAlias =
        new CategoryConfig(
            category.name(),
            category.specSchema(),
            category.retailers(),
            List.of(
                new SeedProduct("Keychron", "Q1 Pro", "Keychron Q1 Pro", Map.of()),
                new SeedProduct(
                    "Keychron", "Q8", "Keychron Q8 (Alice)", Map.of(), List.of("Q8 Alice")),
                new SeedProduct(
                    "Glorious", "GMMK Pro", "Glorious GMMK Pro", Map.of(), List.of("Glorious Pro")),
                new SeedProduct("HHKB", "Studio", "HHKB Studio", Map.of())),
            category.resolution(),
            category.communities(),
            category.sentiment());
    MentionRun.Summary s = run.run(withAlias);
    assertThat(s.autoLinked()).isEqualTo(1);
    assertThat(count("select count(*) from mentions where matched_text = 'glorious pro'"))
        .isEqualTo(1);
  }

  @Test
  void theEvalJoinsLabelsToTextsAndProductsAndReportsWhatItCannotFind() throws Exception {
    raw("c1", null, "the keychron q1 pro is great");
    raw("c6", null, "keychron q1 max");
    MentionMatcher matcher = run.matcher(category);
    List<MentionLabels.MatchLabel> labels =
        List.of(
            new MentionLabels.MatchLabel(
                "youtube_comment", "c1", "…", "Keychron", "Q1 Pro", true, ""),
            new MentionLabels.MatchLabel(
                "youtube_comment", "c6", "…", "Keychron", "Q1 Pro", false, "sibling"),
            new MentionLabels.MatchLabel(
                "youtube_comment", "gone", "…", "Keychron", "Q1 Pro", true, ""),
            new MentionLabels.MatchLabel(
                "youtube_comment", "c1", "…", "Keychron", "Q99", false, ""));

    MentionEval.Loaded loaded = MentionEval.load(DB, matcher.catalog(), labels);
    assertThat(loaded.pairs()).hasSize(2);
    assertThat(loaded.skipped())
        .containsExactly("no raw mention youtube_comment gone", "no catalog product Keychron Q99");
    assertThat(count("select count(*) from mention_labels")).isEqualTo(2);

    MentionEval.Report r = MentionEval.evaluate(matcher, loaded.pairs());
    assertThat(r.operating().tp()).isEqualTo(1);
    assertThat(r.operating().fp()).isZero();
    assertThat(r.operating().fn()).isZero();
    assertThat(r.mistakes()).isEmpty();
    assertThat(MentionEval.render("keyboards", r, loaded.skipped()))
        .contains("precision 1.000  recall 1.000")
        .contains("skipped: no raw mention youtube_comment gone");

    List<String> skipped = new java.util.ArrayList<>();
    List<SentimentEval.Case> cases =
        SentimentEval.load(
            DB,
            List.of(
                new MentionLabels.SentimentLabel(
                    "youtube_comment", "c1", "q1 pro", SentimentRule.Sentiment.POSITIVE, ""),
                new MentionLabels.SentimentLabel(
                    "youtube_comment", "c6", "q1 max", SentimentRule.Sentiment.NEGATIVE, "")),
            skipped);
    SentimentEval.Report sr =
        SentimentEval.evaluate(new SentimentRule(category.sentiment()), cases);
    assertThat(sr.cases()).isEqualTo(2);
    assertThat(sr.right()).isEqualTo(1);
    assertThat(sr.misses()).hasSize(1);
    assertThat(SentimentEval.render("keyboards", sr, skipped)).contains("accuracy 0.500");
  }

  @Test
  void theDaoReadsWhatTheIngesterWrote() throws Exception {
    long id = raw("v2", "A title", "and a body");
    MentionDao dao = new MentionDao(DB);
    List<MentionDao.Raw> all = dao.raw("keyboards");
    assertThat(all).hasSize(1);
    assertThat(all.get(0).id()).isEqualTo(id);
    assertThat(all.get(0).fullText()).isEqualTo("A title\nand a body");
    assertThat(new ProductDao(DB).list("keyboards")).isEmpty();
  }

  static Database db() {
    return DB;
  }
}
