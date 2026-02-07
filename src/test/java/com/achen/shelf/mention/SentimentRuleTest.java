package com.achen.shelf.mention;

import static org.assertj.core.api.Assertions.assertThat;

import com.achen.shelf.config.SentimentConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each rule of the sentiment reading on the sentence from the corpus that motivated it. */
class SentimentRuleTest {

  private final SentimentRule rule =
      new SentimentRule(new SentimentConfig(List.of("creamy"), List.of("mushy")));

  private SentimentRule.Reading read(String text, String phrase) {
    return rule.read(text, List.of(phrase.split(" ")));
  }

  @Test
  void praiseInTheNamingSentenceIsPositive() {
    SentimentRule.Reading r =
        read("i have the keychron Q15 Max and i absolutely love it!", "q15 max");
    assertThat(r.sentiment()).isEqualTo(SentimentRule.Sentiment.POSITIVE);
    assertThat(r.score()).isEqualTo(1.0);
    assertThat(r.evidence()).containsExactly("love");
  }

  @Test
  void theVerdictInTheNextSentenceCounts() {
    SentimentRule.Reading r = read("Just bought epomaker he108.  Glad with it :)", "he108");
    assertThat(r.sentiment()).isEqualTo(SentimentRule.Sentiment.POSITIVE);
    assertThat(r.evidence()).containsExactly("glad");
  }

  @Test
  void theVerdictInTheSentenceBeforeCounts() {
    SentimentRule.Reading r =
        read(
            "I Bought The Dumbest Headphones Ever Made.\nI tried the Dyson Zone headphones",
            "dyson zone");
    assertThat(r.sentiment()).isEqualTo(SentimentRule.Sentiment.NEGATIVE);
    assertThat(r.evidence()).containsExactly("dumbest");
  }

  @Test
  void twoSentencesAwayIsOutOfReach() {
    SentimentRule.Reading r =
        read(
            "Yunzii rt75 pro. Wireless 8k. I tone everything down. Sound really nice.", "rt75 pro");
    assertThat(r.sentiment()).isEqualTo(SentimentRule.Sentiment.NEUTRAL);
    assertThat(r.evidence()).containsExactly("nothing in the lexicon");
  }

  @Test
  void aQuestionIsNeutralWhateverItSays() {
    SentimentRule.Reading r = read("Question is the epomaker he68 lite any good?", "he68 lite");
    assertThat(r.sentiment()).isEqualTo(SentimentRule.Sentiment.NEUTRAL);
    assertThat(r.evidence()).containsExactly("a question");
  }

  @Test
  void losingAComparisonIsNegative() {
    SentimentRule.Reading r =
        read("Very impressive board. Definitely a step up from the Evo75.", "evo75");
    assertThat(r.sentiment()).isEqualTo(SentimentRule.Sentiment.NEGATIVE);
    assertThat(r.evidence()).containsExactly("loses a comparison: 'step up from'");
    assertThat(
            read("Sounds better than my modded wooting with lubed switches", "wooting").sentiment())
        .isEqualTo(SentimentRule.Sentiment.NEGATIVE);
  }

  @Test
  void aNegatorFlipsTheWordAfterIt() {
    // "can't go wrong" — the t of can't negates "wrong".
    SentimentRule.Reading r =
        read(
            "I was eyeing the Rainy 75 pro. You can't really go wrong with that right.",
            "rainy 75 pro");
    assertThat(r.sentiment()).isEqualTo(SentimentRule.Sentiment.POSITIVE);
    assertThat(r.evidence()).containsExactly("not wrong");
    assertThat(read("the gmmk pro is not good", "gmmk pro").sentiment())
        .isEqualTo(SentimentRule.Sentiment.NEGATIVE);
    assertThat(
            read("Epomaker are kings of making stuff that's almost cool", "epomaker").sentiment())
        .isEqualTo(SentimentRule.Sentiment.NEGATIVE);
  }

  @Test
  void aPhraseClaimsItsTokensBeforeAWordInsideIt() {
    SentimentRule.Reading r = read("bought the tofu60 redux, no regrets", "tofu60 redux");
    assertThat(r.sentiment()).isEqualTo(SentimentRule.Sentiment.POSITIVE);
    assertThat(r.evidence()).containsExactly("no regrets");
  }

  @Test
  void theCategorysOwnWordsCount() {
    assertThat(read("try keychron q3 he 8k tkl its so creamy", "q3 he").sentiment())
        .isEqualTo(SentimentRule.Sentiment.POSITIVE);
    assertThat(read("the stock switches on the q3 he are mushy", "q3 he").sentiment())
        .isEqualTo(SentimentRule.Sentiment.NEGATIVE);
  }

  @Test
  void praiseAndComplaintCancelToNeutralAndTheScoreSaysHowOneSided() {
    SentimentRule.Reading r = read("the q3 he sounds great but the software is terrible", "q3 he");
    assertThat(r.sentiment()).isEqualTo(SentimentRule.Sentiment.NEUTRAL);
    assertThat(r.score()).isZero();
    assertThat(r.evidence()).containsExactlyInAnyOrder("great", "terrible");
    assertThat(read("the q3 he is great, love it, but a bit mushy", "q3 he").score())
        .isCloseTo(1.0 / 3, org.assertj.core.api.Assertions.within(1e-9));
  }

  @Test
  void aNameNotInTheTextReadsAsNotFound() {
    assertThat(read("nothing about keyboards here", "q3 he"))
        .isEqualTo(SentimentRule.Reading.NOT_FOUND);
  }
}
