package com.achen.shelf.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.crawl.CrawlRunner;
import com.achen.shelf.crawl.Fetcher;
import com.achen.shelf.crawl.RawStore;
import com.achen.shelf.testing.FixtureCategory;
import com.achen.shelf.testing.FixtureServer;
import com.achen.shelf.testing.PostgresTestBase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code shelf eval resolution}, end to end: a crawl of the fixture server fills {@code offers},
 * the fixture label set is joined against it, and the resolver is scored. Small enough that every
 * number in the report can be checked by hand against the label file.
 */
class ResolutionEvalTest extends PostgresTestBase {

  private FixtureServer server;
  private CategoryConfig category;

  @TempDir Path tempDir;

  @BeforeEach
  void crawlTheFixtures() throws IOException, SQLException, InterruptedException {
    server = FixtureCategory.serveStandardFixtures(FixtureServer.start());
    category = FixtureCategory.load(tempDir, server);
    new CrawlRunner(
            DB,
            new Fetcher(
                "ShelfBot/0.1 (+https://example.test/shelf)",
                2,
                Duration.ofMillis(5),
                Duration.ofSeconds(5)),
            new RawStore(tempDir.resolve("raw")),
            Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC))
        .runOnce(category);
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private Path labelsForThisServer() throws IOException {
    String port = server.baseUrl().substring(server.baseUrl().lastIndexOf(':') + 1);
    Path file = tempDir.resolve("labels.tsv");
    Files.writeString(
        file,
        FixtureServer.Fixtures.read("labels/good.tsv").replace("__PORT__", port),
        StandardCharsets.UTF_8);
    return file;
  }

  @Test
  void scoresTheResolverAgainstTheLabelsAndRecordsThem() throws IOException, SQLException {
    ResolutionRun run = new ResolutionRun(DB, Resolver.Thresholds.defaults());
    Catalog catalog = run.loadCatalog(category);
    Resolver resolver =
        new Resolver(
            catalog,
            new Scorer(
                category.resolution().identityFields(), category.resolution().nonProductPhrases()),
            Resolver.Thresholds.defaults());

    ResolutionEval.Loaded loaded =
        ResolutionEval.load(DB, catalog, Labels.read(labelsForThisServer()));

    // Seven labels: five join, one names an offer the crawl never saw, one a product not seeded.
    assertThat(loaded.pairs()).hasSize(5);
    assertThat(loaded.skipped())
        .hasSize(2)
        .anyMatch(s -> s.startsWith("no offer at "))
        .anyMatch(s -> s.equals("no catalog product Nobody Nothing"));
    assertThat(count("select count(*) from resolution_labels")).isEqualTo(5);
    assertThat(count("select count(*) from resolution_labels where match")).isEqualTo(3);

    ResolutionEval.Report report = ResolutionEval.evaluate(resolver, loaded.pairs());

    // Q6 HE → Q6 HE (tp), Q6 HE → Q65 (tn), K5 Ultra → K2 Ultra (tn), K2 Ultra (tp), 80HE (tp).
    assertThat(report.pairs()).isEqualTo(5);
    assertThat(report.positives()).isEqualTo(3);
    assertThat(report.operating().tp()).isEqualTo(3);
    assertThat(report.operating().fp()).isZero();
    assertThat(report.operating().fn()).isZero();
    assertThat(report.operating().tn()).isEqualTo(2);
    assertThat(report.operating().precision()).isEqualTo(1.0);
    assertThat(report.operating().recall()).isEqualTo(1.0);
    assertThat(report.mistakes()).isEmpty();
    assertThat(report.unjudgedLinks()).isZero();

    // The sweep is 21 points from 0 to 1. At threshold 0 every pair whose product is the
    // resolver's best counts as linked: the three positives, and the K5 Ultra × K2 Ultra pair
    // (best in its block at 0.06, nothing else Keychron is closer) — the one the real threshold
    // exists to refuse. The Q6 HE × Q65 pair never links at any threshold: the Q6 HE outranks it.
    assertThat(report.sweep()).hasSize(21);
    assertThat(report.sweep().get(0).threshold()).isZero();
    assertThat(report.sweep().get(20).threshold()).isEqualTo(1.0);
    assertThat(report.sweep().get(0).tp()).isEqualTo(3);
    assertThat(report.sweep().get(0).fp()).isEqualTo(1);
    assertThat(report.sweep().get(2).fp()).as("at 0.10 the K5 pair is refused").isZero();

    String text = ResolutionEval.render(report, "keyboards", catalog.size(), 0, loaded.skipped());
    assertThat(text)
        .contains("5 labeled pairs (3 match, 2 not)")
        .contains("precision 1.000  recall 1.000")
        .contains("2 label(s) skipped:");
  }

  @Test
  void missesAreReportedWithTheReasonAndTheBand() throws IOException, SQLException {
    ResolutionRun run = new ResolutionRun(DB, Resolver.Thresholds.defaults());
    Catalog catalog = run.loadCatalog(category);
    // Every fixture title says "keyboard": as a non-product phrase it pushes each whole match
    // down to 0.5 — the review band — so all three positives become misses of the soft kind.
    Resolver cautious =
        new Resolver(
            catalog, new Scorer(List.of(), List.of("keyboard")), Resolver.Thresholds.defaults());
    ResolutionEval.Loaded loaded =
        ResolutionEval.load(DB, catalog, Labels.read(labelsForThisServer()));

    ResolutionEval.Report report = ResolutionEval.evaluate(cautious, loaded.pairs());

    assertThat(report.operating().tp()).isZero();
    assertThat(report.operating().fn()).isEqualTo(3);
    assertThat(report.operating().fp()).isZero();
    assertThat(report.operating().recall()).isZero();
    assertThat(report.operating().precision())
        .as("nothing linked: precision is vacuous")
        .isEqualTo(1.0);
    assertThat(report.inReviewBand()).isEqualTo(3);
    assertThat(report.mistakes()).hasSize(3);
    assertThat(report.mistakes())
        .allSatisfy(
            m -> {
              assertThat(m.kind()).isEqualTo("FN");
              assertThat(m.outcome()).isEqualTo(Resolver.Outcome.REVIEW);
              assertThat(m.best()).get().extracting(b -> b.score().value()).isEqualTo(0.5);
            });
    // The sweep shows where the recall went: everything links again at a 0.5 bar.
    ResolutionEval.Point atHalf = report.sweep().get(10); // 21 points, 0.05 apart
    assertThat(atHalf.threshold()).isCloseTo(0.5, within(1e-9));
    assertThat(atHalf.tp()).isEqualTo(3);

    String text = ResolutionEval.render(report, "keyboards", catalog.size(), 0, List.of());
    assertThat(text)
        .contains("3 labeled pair(s) land in the review band")
        .contains("mistakes at the operating point:")
        .contains("FN  Keychron Q6 HE QMK Wireless Custom Keyboard");
  }
}
