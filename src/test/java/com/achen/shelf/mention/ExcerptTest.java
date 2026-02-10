package com.achen.shelf.mention;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExcerptTest {

  @Test
  void theNamingSentenceWithItsNeighboursWhenTheyFit() {
    String text =
        "First thought. I got the rainy 75 pro for 74 dollars. Is it worth it? Long tail here.";
    assertThat(Excerpt.of(text, List.of("rainy", "75", "pro"), 280))
        .isEqualTo("First thought. I got the rainy 75 pro for 74 dollars. Is it worth it?");
    assertThat(Excerpt.of(text, List.of("rainy", "75", "pro"), 40))
        .isEqualTo("I got the rainy 75 pro for 74 dollars.");
  }

  @Test
  void aLongSentenceIsCutAtAWordWithAnEllipsis() {
    String text = "the rainy 75 pro " + "word ".repeat(80);
    String out = Excerpt.of(text, List.of("rainy", "75", "pro"), 60);
    assertThat(out).endsWith("…").hasSizeLessThanOrEqualTo(61);
    assertThat(out).startsWith("the rainy 75 pro word");
  }

  @Test
  void aTextThatDoesNotNameItIsCutFromTheStart() {
    assertThat(Excerpt.of("nothing about it here at all", List.of("q1", "pro"), 12))
        .isEqualTo("nothing…");
    assertThat(Excerpt.of(null, List.of("q1"), 12)).isEmpty();
  }
}
