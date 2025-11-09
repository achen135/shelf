package com.achen.shelf.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.achen.shelf.crawl.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Each feature of the scorer on the cases from the real corpus that motivated it. The numbers are
 * the hand-set weights, so a change to a weight is a change to these tests — on purpose: the
 * weights are claims about the domain, and docs/benchmarks records what they measured.
 */
class ScorerTest {

  private final Scorer scorer = new Scorer();

  private static Listing listing(String title) {
    return listing(title, Map.of());
  }

  private static Listing listing(String title, Map<String, Object> spec) {
    return new Listing(1, "keychron", Normalizer.normalize(title), spec);
  }

  private static Candidate product(String model) {
    return product(model, Map.of());
  }

  private static Candidate product(String model, Map<String, Object> spec) {
    return new Candidate(
        model.hashCode(), "keychron", Normalizer.normalize(model), "Keychron " + model, spec);
  }

  @Test
  void aModelThatAppearsWholeScoresOne() {
    Scorer.Score s =
        scorer.score(
            listing("Keychron Q6 HE QMK Wireless Custom Keyboard"), product("Q6 HE"), Set.of());
    assertThat(s.value()).isEqualTo(1.0);
    assertThat(s.reasons()).containsExactly("model appears whole");
  }

  @Test
  void wholeMeansOnTokenBoundaries() {
    // "q6" must not be found inside "q65" — M1's rule, carried forward as a feature.
    Scorer.Score s = scorer.score(listing("Keychron Q65 Custom Keyboard"), product("Q6"), Set.of());
    assertThat(s.value()).isZero();
    assertThat(s.reasons()).containsExactly("no model token in the title");
  }

  @Test
  void aModelWrittenAsOneTokenCounts() {
    Scorer.Score s = scorer.score(listing("Keychron Q1Pro Keyboard"), product("Q1 Pro"), Set.of());
    assertThat(s.value()).isEqualTo(1.0);
    assertThat(s.reasons()).containsExactly("model appears as one token");
  }

  @Test
  void everyTokenPresentButScatteredIsWorthAReview() {
    Scorer.Score s =
        scorer.score(
            listing("KBDFans KBD67 R1 Lite Barebones DIY Keyboard Kit"),
            product("KBD67 Lite"),
            Set.of());
    assertThat(s.value()).isEqualTo(Scorer.ALL_TOKENS_SCATTERED);
  }

  @Test
  void aMissingWordScalesByWeightedRecall() {
    // "One 2 Mini" against "One 2 Mini Pro": pro (weight 1) missing of one+2+mini+pro (1+2+1+1).
    Scorer.Score s =
        scorer.score(
            listing("Ducky One 2 Mini Pure White RGB LED 60% Keyboard"),
            product("One 2 Mini Pro"),
            Set.of());
    assertThat(s.value()).isCloseTo(Scorer.PARTIAL_SCALE * 4 / 5, within(1e-9));
    assertThat(s.reasons()).anyMatch(r -> r.startsWith("missing model token(s) [pro]"));
  }

  @Test
  void aMissingNumberedTokenIsNearlyDisqualifying() {
    // "One 3 SF" against "One 2 SF": the "2" is the whole difference between the generations.
    Scorer.Score s =
        scorer.score(
            listing("Ducky One 3 SF Classic 65% Hotswap RGB Mechanical Keyboard"),
            product("One 2 SF"),
            Set.of());
    double recall = 2.0 / 4; // one (1) + sf (1) of one (1) + 2 (2) + sf (1)
    assertThat(s.value())
        .isCloseTo(Scorer.PARTIAL_SCALE * recall * Scorer.DESIGNATOR_MISSING_SCALE, within(1e-9));
    assertThat(s.reasons()).contains("a numbered model token is missing");
  }

  @Test
  void aSiblingsQualifierNextToTheModelIsPenalized() {
    // The catalog has a Q6 HE and a Q15 Max, so "he" and "max" are qualifiers for the Q2.
    Set<String> siblings = Set.of("he", "max", "pro", "ultra");
    Scorer.Score he =
        scorer.score(
            listing("Keychron Q2 HE 8K Magnetic Switch Keyboard"), product("Q2"), siblings);
    Scorer.Score max =
        scorer.score(listing("Keychron Q2 Max QMK/VIA Wireless Custom"), product("Q2"), siblings);
    Scorer.Score plain =
        scorer.score(
            listing("Keychron Q2 QMK Custom Mechanical Keyboard"), product("Q2"), siblings);

    assertThat(he.value()).isCloseTo(1.0 - Scorer.QUALIFIER_PENALTY, within(1e-9));
    assertThat(he.reasons()).contains("qualifier of a sibling product next to the model: [he]");
    assertThat(max.value()).isCloseTo(1.0 - Scorer.QUALIFIER_PENALTY, within(1e-9));
    assertThat(plain.value()).isEqualTo(1.0);
  }

  @Test
  void aQualifierElsewhereInTheTitleDoesNotCount() {
    // "65" is a sibling token (an "OK-M 65" is in the block) but here it is a layout, three words
    // after the model, so it must not mark down a genuine One 2 SF.
    Scorer.Score s =
        scorer.score(
            listing("Ducky One 2 SF RGB LED 65% Double Shot PBT Mechanical Keyboard"),
            product("One 2 SF"),
            Set.of("65", "mini", "pro"));
    assertThat(s.value()).isEqualTo(1.0);
  }

  @Test
  void aQualifierInsideAScatteredSpanCounts() {
    // "GMMK 3 PRO HE" has every token of "GMMK 3 HE", with a sibling's "pro" in the middle.
    Scorer.Score s =
        scorer.score(
            listing("GMMK 3 PRO HE Prebuilt Keyboard"), product("GMMK 3 HE"), Set.of("pro"));
    assertThat(s.value())
        .isCloseTo(Scorer.ALL_TOKENS_SCATTERED - Scorer.QUALIFIER_PENALTY, within(1e-9));
  }

  @Test
  void onlyQualifiersTouchingTheModelCountAndTheirPenaltyIsCapped() {
    Set<String> siblings = Set.of("pro", "max", "he", "ultra");
    // Whole match: the tokens on either side of "q2" are looked at, nothing further.
    Scorer.Score twoTouching =
        scorer.score(listing("Keychron Pro Q2 Max HE Ultra"), product("Q2"), siblings);
    assertThat(twoTouching.value())
        .isCloseTo(1.0 - Scorer.QUALIFIER_PENALTY * Scorer.QUALIFIER_CAP, within(1e-9));
    assertThat(twoTouching.reasons())
        .contains("qualifier of a sibling product next to the model: [pro, max]");

    // Scattered match: everything inside the span counts, capped at two.
    Scorer.Score threeInside =
        scorer.score(listing("Keychron Q2 Pro Max Ultra HE"), product("Q2 HE"), siblings);
    assertThat(threeInside.value())
        .isCloseTo(
            Scorer.ALL_TOKENS_SCATTERED - Scorer.QUALIFIER_PENALTY * Scorer.QUALIFIER_CAP,
            within(1e-9));
  }

  @Test
  void aLateMentionIsMarkedDown() {
    // A collab prefix of four tokens is still fine ("Varmilo x MK Glintstone Minilo VXT67" links
    // at 1.0 in the labeled set); one more and the model is deep enough to doubt.
    Scorer.Score fourBefore =
        scorer.score(
            listing("Varmilo x MK Glintstone Minilo VXT67 65% Hotswap Keyboard"),
            product("Minilo VXT67"),
            Set.of());
    Scorer.Score fiveBefore =
        scorer.score(
            listing("Case and plate set for the Varmilo Minilo VXT67"),
            product("Minilo VXT67"),
            Set.of());

    assertThat(fourBefore.value()).isEqualTo(1.0);
    assertThat(fiveBefore.value()).isCloseTo(1.0 - Scorer.LATE_MENTION_PENALTY, within(1e-9));
    assertThat(fiveBefore.reasons()).contains("model first mentioned at token 8");
  }

  @Test
  void aConflictOnAnIdentityFieldIsPenalized() {
    Scorer withLayout = new Scorer(List.of("layout_size"), List.of());
    Listing sixtyFive = listing("Keychron Q1 Pro 65%", Map.of("layout_size", "65"));
    Candidate seventyFive = product("Q1 Pro", Map.of("layout_size", "75"));

    Scorer.Score conflict = withLayout.score(sixtyFive, seventyFive, Set.of());
    Scorer.Score ignored = scorer.score(sixtyFive, seventyFive, Set.of());

    assertThat(conflict.value()).isCloseTo(1.0 - Scorer.SPEC_CONFLICT_PENALTY, within(1e-9));
    assertThat(conflict.reasons()).contains("spec conflict on [layout_size]");
    assertThat(ignored.value()).isEqualTo(1.0);
  }

  @Test
  void aFieldMissingOnEitherSideIsNotAConflict() {
    Scorer withLayout = new Scorer(List.of("layout_size"), List.of());
    Scorer.Score s =
        withLayout.score(
            listing("Keychron Q1 Pro", Map.of("switch_type", "linear")),
            product("Q1 Pro", Map.of("layout_size", "75")),
            Set.of());
    assertThat(s.value()).isEqualTo(1.0);
  }

  @Test
  void aNonProductPhraseKeepsAWholeMatchOutOfAutoRange() {
    Scorer phrased = new Scorer(List.of(), List.of("with PBTfans", "module", "custom order"));
    Scorer.Score bundle =
        phrased.score(listing("Athena 75 with PBTfans Fairy R2"), product("Athena 75"), Set.of());
    Scorer.Score part =
        phrased.score(
            listing("Wooting 60HE V2 Magnetic 8K Keyboard PCB Module"),
            product("60HE V2"),
            Set.of());
    Scorer.Score plain =
        phrased.score(listing("Athena 75 — Anodized black"), product("Athena 75"), Set.of());

    assertThat(bundle.value()).isCloseTo(1.0 - Scorer.NON_PRODUCT_PENALTY, within(1e-9));
    assertThat(bundle.reasons())
        .contains("title marks a bundle, part or custom order: [with pbtfans]");
    assertThat(part.value()).isCloseTo(1.0 - Scorer.NON_PRODUCT_PENALTY, within(1e-9));
    assertThat(plain.value()).isEqualTo(1.0);
  }

  @Test
  void scoresNeverLeaveTheUnitInterval() {
    Scorer phrased = new Scorer(List.of("layout_size"), List.of("bundle"));
    Scorer.Score s =
        phrased.score(
            listing(
                "Bundle: a b c d e Keychron Q2 Pro Max HE Bundle 65%", Map.of("layout_size", "65")),
            product("Q2", Map.of("layout_size", "75")),
            Set.of("pro", "max", "he"));
    assertThat(s.value()).isBetween(0.0, 1.0);
  }

  @Test
  void blankInputsScoreNothing() {
    assertThat(scorer.score(listing(""), product("Q2"), Set.of()).value()).isZero();
    assertThat(scorer.score(listing("Keychron Q2"), product(""), Set.of()).value()).isZero();
  }
}
