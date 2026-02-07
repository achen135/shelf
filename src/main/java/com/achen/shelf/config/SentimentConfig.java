package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * What a category tells the sentiment rule about its own vocabulary (M9). Optional.
 *
 * <p>The rule's lexicon is general English — love, great, terrible, returned — and lives in code.
 * What it cannot know is the words a hobby uses as verdicts: a keyboard that sounds "creamy" or
 * "thocky" is being praised, one that is "mushy" or "pingy" is not, and none of those means
 * anything about a monitor. Those come from here, matched on whole normalized tokens like the
 * resolution section's phrases.
 *
 * @param positive phrases that praise, in this category
 * @param negative phrases that disparage, in this category
 */
public record SentimentConfig(List<String> positive, List<String> negative) {

  /** Nothing configured: the general lexicon alone. */
  public static final SentimentConfig NONE = new SentimentConfig(List.of(), List.of());

  public SentimentConfig {
    positive = positive == null ? List.of() : List.copyOf(positive);
    negative = negative == null ? List.of() : List.copyOf(negative);
  }

  @JsonCreator
  static SentimentConfig fromYaml(
      @JsonProperty("positive") List<String> positive,
      @JsonProperty("negative") List<String> negative) {
    return new SentimentConfig(positive, negative);
  }
}
