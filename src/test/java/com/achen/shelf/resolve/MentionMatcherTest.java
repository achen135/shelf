package com.achen.shelf.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Each rule of the mention matcher on the case from the real corpus that motivated it
 * (data/labels/keyboards-mention-resolution.tsv). The catalog here is the slice of the keyboards
 * seeds those cases need.
 */
class MentionMatcherTest {

  private static Candidate product(long id, String brand, String model, String... aliases) {
    return new Candidate(
        id,
        brand.toLowerCase(java.util.Locale.ROOT),
        com.achen.shelf.crawl.Normalizer.normalize(model),
        brand + " " + model,
        Map.of(),
        List.of(aliases));
  }

  private static final Candidate Q6_HE = product(1, "Keychron", "Q6 HE");
  private static final Candidate Q2 = product(2, "Keychron", "Q2");
  private static final Candidate Q15_MAX = product(3, "Keychron", "Q15 Max");
  private static final Candidate WOOTING_60HE_PLUS = product(4, "Wooting", "60HE+", "60HE plus");
  private static final Candidate WOOTING_60HE_V2 = product(5, "Wooting", "60HE V2");
  private static final Candidate RT75 = product(6, "Epomaker", "RT75");
  private static final Candidate HACK70 = product(7, "Epomaker", "HACK70");
  private static final Candidate HHKB_STUDIO = product(8, "HHKB", "Studio");
  private static final Candidate GMMK_PRO = product(9, "Glorious", "GMMK Pro");
  private static final Candidate GMMK_3 = product(10, "Glorious", "GMMK 3");
  private static final Candidate RAINY = product(11, "WOBKEY", "Rainy 75 Pro");
  private static final Candidate Q8 = product(12, "Keychron", "Q8", "Q8 Alice");

  private final MentionMatcher matcher =
      new MentionMatcher(
          Catalog.of(
              List.of(
                  Q6_HE,
                  Q2,
                  Q15_MAX,
                  WOOTING_60HE_PLUS,
                  WOOTING_60HE_V2,
                  RT75,
                  HACK70,
                  HHKB_STUDIO,
                  GMMK_PRO,
                  GMMK_3,
                  RAINY,
                  Q8)),
          Resolver.Thresholds.defaults());

  private Scorer.Score score(String text, Candidate c) {
    return matcher.score(matcher.text(text), c);
  }

  @Test
  void aNameWholeWithItsBrandLinks() {
    Scorer.Score s = score("i have the keychron Q15 Max and i absolutely love it!", Q15_MAX);
    assertThat(s.value()).isEqualTo(1.0);
    assertThat(s.reasons()).containsExactly("model appears whole");
    assertThat(s.start()).isEqualTo(4);
    assertThat(s.end()).isEqualTo(5);
  }

  @Test
  void aMultiTokenNameStandsWithoutItsBrand() {
    // "gmmk pro", "rainy 75 pro": two or three tokens identify themselves.
    assertThat(
            score("I got my start with a keychron and unfortunately a gmmk pro", GMMK_PRO).value())
        .isEqualTo(1.0);
    assertThat(score("I was eyeing the Rainy 75 pro black with DE-ISO", RAINY).value())
        .isEqualTo(1.0);
  }

  @Test
  void aOneTokenNameWrittenApartCounts() {
    Scorer.Score s = score("I tried the Epomaker Hack 70 keyboard... so you don't have to", HACK70);
    assertThat(s.value()).isEqualTo(1.0);
    assertThat(s.reasons()).containsExactly("model appears written apart (hack 70)");
  }

  @Test
  void nothingPartialInFreeText() {
    // "q3 he" is not the Q6 HE, and the scattered "he" does not make it one.
    Scorer.Score s = score("try keychron q3 he 8k tkl its so creamy", Q6_HE);
    assertThat(s.value()).isZero();
    assertThat(s.matched()).isFalse();
  }

  @Test
  void aBareWordNeedsItsMaker() {
    Scorer.Score s = score("that's absolutely ridiculous. i need one for my studio", HHKB_STUDIO);
    assertThat(s.value()).isZero();
    assertThat(s.reasons()).containsExactly("'studio' is a bare word and the brand is not named");
    // Named, it is the product.
    assertThat(score("the HHKB Studio is the one with the mouse keys", HHKB_STUDIO).value())
        .isEqualTo(1.0);
  }

  @Test
  void aSiblingsQualifierNextToTheNameIsAPenalty() {
    Scorer.Score s = score("Day 1 of asking for a wooting 60he v2", WOOTING_60HE_PLUS);
    assertThat(s.value()).isCloseTo(0.65, within(1e-9));
    assertThat(s.reasons()).contains("qualifier of a sibling product next to the name: [v2]");
  }

  @Test
  void anotherCatalogBrandNextToTheNameIsAPenalty() {
    Scorer.Score s = score("the keychron gmmk pro clone", GMMK_PRO);
    assertThat(s.value()).isCloseTo(0.65, within(1e-9));
    assertThat(s.reasons()).contains("another catalog brand next to the name: [keychron]");
  }

  @Test
  void aSingleTokenNameWithoutItsBrandIsAProposal() {
    // Yunzii's RT75 Pro is not Epomaker's RT75: brand absent, one token → review, never a link.
    Scorer.Score s = score("Yo Hipyo, can you review the Yunzii RT75 Pro?", RT75);
    assertThat(s.value()).isCloseTo(0.8, within(1e-9));
    assertThat(s.reasons()).contains("brand not named anywhere and the name is one token");
    assertThat(new MentionMatcher.Match(RT75, s, "rt75").outcome(Resolver.Thresholds.defaults()))
        .isEqualTo(Resolver.Outcome.REVIEW);
  }

  @Test
  void anAliasIsReadLikeTheModel() {
    Scorer.Score s = score("the keychron q8 alice is the ergonomic one", Q8);
    assertThat(s.value()).isEqualTo(1.0);
    assertThat(s.reasons()).contains("matched the alias 'q8 alice'");
    assertThat(s.end() - s.start()).isEqualTo(1); // the longer name's span
  }

  @Test
  void oneSpanOneProduct() {
    // "wooting 60he v2" claims the 60HE V2 (1.0) and the 60HE+ (0.65, sibling qualifier); the
    // spans overlap, so only the better claim survives.
    List<MentionMatcher.Match> m =
        matcher.matches(matcher.text("5 hundo likes and HE buys me a WOOTING 60HE V2"));
    assertThat(m).extracting(x -> x.candidate().productId()).containsExactly(5L);
    assertThat(m.get(0).phrase()).isEqualTo("60he v2");
  }

  @Test
  void severalProductsInOneTextEachGetAMatch() {
    List<MentionMatcher.Match> m =
        matcher.matches(
            matcher.text("Keychron Q2 vs Glorious GMMK Pro after a year — which would you keep?"));
    assertThat(m).extracting(x -> x.candidate().productId()).containsExactlyInAnyOrder(2L, 9L);
    assertThat(m).allSatisfy(x -> assertThat(x.score().value()).isEqualTo(1.0));
  }

  @Test
  void textStatesWhichBrandsItNames() {
    MentionMatcher.Text t = matcher.text("Glad to see some HHKB love. Wooting is better.");
    assertThat(t.brandsStated()).containsExactlyInAnyOrder("hhkb", "wooting");
    assertThat(t.tokens()).startsWith("glad", "to", "see", "some", "hhkb");
  }
}
