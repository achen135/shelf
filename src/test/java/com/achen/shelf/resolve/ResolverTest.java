package com.achen.shelf.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.achen.shelf.crawl.Normalizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Blocking, sibling tokens, ranking and the two thresholds, on a small in-memory catalog. */
class ResolverTest {

  private static final Candidate Q2 = candidate(1, "Keychron", "Q2");
  private static final Candidate Q6_HE = candidate(2, "Keychron", "Q6 HE");
  private static final Candidate Q15_MAX = candidate(3, "Keychron", "Q15 Max");
  private static final Candidate ONE_2_SF = candidate(4, "Ducky", "One 2 SF");
  private static final Candidate ONE_2_MINI_PRO = candidate(5, "Ducky", "One 2 Mini Pro");

  private static final Catalog CATALOG =
      Catalog.of(List.of(Q2, Q6_HE, Q15_MAX, ONE_2_SF, ONE_2_MINI_PRO));

  private static Candidate candidate(long id, String brand, String model) {
    return new Candidate(
        id,
        Normalizer.normalize(brand),
        Normalizer.normalize(model),
        brand + " " + model,
        Map.of());
  }

  private static Listing listing(String brand, String title) {
    return new Listing(99, Normalizer.normalize(brand), Normalizer.normalize(title), Map.of());
  }

  private Resolver resolver() {
    return new Resolver(CATALOG, new Scorer(), Resolver.Thresholds.defaults());
  }

  @Test
  void blocksByNormalizedBrand() {
    assertThat(CATALOG.block(listing("KEYCHRON", "anything")))
        .containsExactlyInAnyOrder(Q2, Q6_HE, Q15_MAX);
    assertThat(CATALOG.block(listing("Ducky", "anything")))
        .containsExactlyInAnyOrder(ONE_2_SF, ONE_2_MINI_PRO);
  }

  @Test
  void aListingWithNoBrandBlockFallsBackToBrandsNamedInItsTitle() {
    assertThat(CATALOG.block(listing("Third Party", "Keychron Q2 QMK Custom Keyboard")))
        .containsExactlyInAnyOrder(Q2, Q6_HE, Q15_MAX);
    assertThat(CATALOG.block(listing("", "Ducky One 2 SF")))
        .containsExactlyInAnyOrder(ONE_2_SF, ONE_2_MINI_PRO);
    assertThat(CATALOG.block(listing("Nobody", "Some Other Keyboard"))).isEmpty();
  }

  @Test
  void siblingTokensAreTheBlocksOtherModelTokens() {
    assertThat(CATALOG.siblingTokens(Q2)).containsExactlyInAnyOrder("q6", "he", "q15", "max");
    assertThat(CATALOG.siblingTokens(Q6_HE)).containsExactlyInAnyOrder("q2", "q15", "max");
    assertThat(CATALOG.siblingTokens(ONE_2_SF)).containsExactlyInAnyOrder("mini", "pro");
    assertThat(CATALOG.siblingTokens(candidate(42, "Unknown", "X"))).isEmpty();
  }

  @Test
  void theBestCandidateWinsAndAWholeMatchIsAuto() {
    Resolver.Decision d =
        resolver().decide(listing("Ducky", "Ducky One 2 SF RGB LED 65% Double Shot PBT"));
    assertThat(d.outcome()).isEqualTo(Resolver.Outcome.AUTO);
    assertThat(d.best()).get().extracting(s -> s.candidate()).isEqualTo(ONE_2_SF);
  }

  @Test
  void aRelativeOfACatalogProductGoesToReview() {
    // Q2 HE: the Q2 appears whole but "he" is a sibling's qualifier.
    Resolver.Decision d =
        resolver().decide(listing("Keychron", "Keychron Q2 HE 8K Magnetic Switch"));
    assertThat(d.outcome()).isEqualTo(Resolver.Outcome.REVIEW);
    assertThat(d.best()).get().extracting(s -> s.candidate()).isEqualTo(Q2);
  }

  @Test
  void anotherGenerationIsNothing() {
    Resolver.Decision d = resolver().decide(listing("Ducky", "Ducky One 3 SF Classic 65% Hotswap"));
    assertThat(d.outcome()).isEqualTo(Resolver.Outcome.NONE);
    assertThat(d.best()).get().extracting(s -> s.candidate()).isEqualTo(ONE_2_SF);
  }

  @Test
  void anEmptyBlockDecidesNothing() {
    Resolver.Decision d = resolver().decide(listing("Nobody", "Some Other Keyboard"));
    assertThat(d.outcome()).isEqualTo(Resolver.Outcome.NONE);
    assertThat(d.best()).isEmpty();
  }

  @Test
  void tiesBreakTowardTheLongerModel() {
    Catalog withPlainQ6 = Catalog.of(List.of(Q6_HE, candidate(6, "Keychron", "Q6")));
    Resolver r = new Resolver(withPlainQ6, new Scorer(), Resolver.Thresholds.defaults());
    // Both "q6" and "q6 he" appear whole; the more specific product is the one meant.
    List<Resolver.Scored> ranked = r.rank(listing("Keychron", "Keychron Q6 HE QMK Wireless"));
    assertThat(ranked.get(0).candidate()).isEqualTo(Q6_HE);
  }

  @Test
  void thresholdsMapScoresToOutcomes() {
    Resolver r = resolver();
    assertThat(r.outcomeFor(1.0)).isEqualTo(Resolver.Outcome.AUTO);
    assertThat(r.outcomeFor(0.9)).isEqualTo(Resolver.Outcome.AUTO);
    assertThat(r.outcomeFor(0.89)).isEqualTo(Resolver.Outcome.REVIEW);
    assertThat(r.outcomeFor(0.4)).isEqualTo(Resolver.Outcome.REVIEW);
    assertThat(r.outcomeFor(0.39)).isEqualTo(Resolver.Outcome.NONE);
  }

  @Test
  void thresholdsMustBeOrdered() {
    assertThatThrownBy(() -> new Resolver.Thresholds(0.4, 0.9))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Resolver.Thresholds(1.1, 0.5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
