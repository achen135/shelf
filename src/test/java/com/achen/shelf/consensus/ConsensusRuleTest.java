package com.achen.shelf.consensus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The consensus rule on hand-built mention lists; the cut-offs are the pre-registered ones. */
class ConsensusRuleTest {

  private static ConsensusRule.Mention m(
      long id, String community, String sentiment, double w, int day) {
    return new ConsensusRule.Mention(
        id,
        community,
        sentiment,
        w,
        Instant.parse("2026-09-01T00:00:00Z").plusSeconds(86400L * day));
  }

  @Test
  void nothingSaidIsNoScoreNotZero() {
    ConsensusRule.Consensus k = ConsensusRule.of(List.of());
    assertThat(k.score()).isEmpty();
    assertThat(k.leaning()).isEqualTo(ConsensusRule.Leaning.UNHEARD);
    assertThat(k.mentionCount()).isZero();
    assertThat(k.positiveShare()).isEmpty();
    assertThat(k.quoteMentionIds()).isEmpty();
  }

  @Test
  void theScoreIsTheWeightedMeanAndNeutralsCount() {
    ConsensusRule.Consensus k =
        ConsensusRule.of(
            List.of(
                m(1, "a", "positive", 1, 1),
                m(2, "a", "neutral", 1, 2),
                m(3, "b", "neutral", 1, 3),
                m(4, "b", "negative", 1, 4)));
    assertThat(k.score()).hasValue(0.0);
    assertThat(k.leaning()).isEqualTo(ConsensusRule.Leaning.MIXED);
    assertThat(k.mentionCount()).isEqualTo(4);
    assertThat(k.positiveCount()).isEqualTo(1);
    assertThat(k.negativeCount()).isEqualTo(1);
    assertThat(k.neutralCount()).isEqualTo(2);
    assertThat(k.positiveShare()).hasValue(0.25);
    assertThat(k.sourceDiversity()).isEqualTo(2);
  }

  @Test
  void aCommunitysWeightTiltsTheMean() {
    ConsensusRule.Consensus k =
        ConsensusRule.of(
            List.of(m(1, "trusted", "positive", 3, 1), m(2, "other", "negative", 1, 2)));
    assertThat(k.score().orElseThrow()).isCloseTo(0.5, within(1e-9)); // (3 − 1) / 4
    assertThat(k.leaning()).isEqualTo(ConsensusRule.Leaning.LIKED);
  }

  @Test
  void theLeaningCutOffsAreFixed() {
    assertThat(ConsensusRule.leaning(0.25)).isEqualTo(ConsensusRule.Leaning.LIKED);
    assertThat(ConsensusRule.leaning(0.24)).isEqualTo(ConsensusRule.Leaning.MIXED);
    assertThat(ConsensusRule.leaning(-0.24)).isEqualTo(ConsensusRule.Leaning.MIXED);
    assertThat(ConsensusRule.leaning(-0.25)).isEqualTo(ConsensusRule.Leaning.DISLIKED);
  }

  @Test
  void quotesAreTheNewestPositiveAndTheNewestNegativeElseTheNewest() {
    List<ConsensusRule.Mention> ms =
        List.of(
            m(1, "a", "positive", 1, 1),
            m(2, "a", "positive", 1, 5),
            m(3, "a", "negative", 1, 2),
            m(4, "a", "neutral", 1, 9));
    assertThat(ConsensusRule.of(ms).quoteMentionIds()).containsExactly(2L, 3L);
    assertThat(
            ConsensusRule.of(List.of(m(4, "a", "neutral", 1, 9), m(5, "a", "neutral", 1, 3)))
                .quoteMentionIds())
        .containsExactly(4L, 5L);
    assertThat(ConsensusRule.of(List.of(m(6, "a", "positive", 1, 1))).quoteMentionIds())
        .containsExactly(6L);
  }
}
