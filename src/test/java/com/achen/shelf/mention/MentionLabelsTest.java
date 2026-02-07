package com.achen.shelf.mention;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The committed label files parse, and hold what the benchmark doc says they hold. */
class MentionLabelsTest {

  @Test
  void theCommittedResolutionLabelsParse() {
    List<MentionLabels.MatchLabel> labels =
        MentionLabels.readMatches(Path.of("data/labels/keyboards-mention-resolution.tsv"));
    assertThat(labels).hasSize(87);
    assertThat(labels.stream().filter(MentionLabels.MatchLabel::match).count()).isEqualTo(13);
    assertThat(labels).allSatisfy(l -> assertThat(l.source()).startsWith("youtube_"));
    assertThat(labels).anySatisfy(l -> assertThat(l.note()).contains("Yunzii"));
  }

  @Test
  void theCommittedSentimentLabelsParse() {
    List<MentionLabels.SentimentLabel> labels =
        MentionLabels.readSentiments(Path.of("data/labels/keyboards-mention-sentiment.tsv"));
    assertThat(labels).hasSize(63);
    assertThat(
            labels.stream().filter(l -> l.sentiment() == SentimentRule.Sentiment.POSITIVE).count())
        .isEqualTo(28);
    assertThat(
            labels.stream().filter(l -> l.sentiment() == SentimentRule.Sentiment.NEGATIVE).count())
        .isEqualTo(13);
  }
}
