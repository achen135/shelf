package com.achen.shelf.consensus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A product's consensus from its mentions in the window — a pure function, fixed before it ran on
 * the corpus (M10).
 *
 * <ul>
 *   <li><b>The score</b> is the weighted mean of each mention's reading, +1 positive / 0 neutral /
 *       −1 negative, weighted by the community's configured weight: {@code Σ w·s / Σ w}, in [−1,
 *       1]. A neutral mention counts in the mean — "day 1 of asking for a review" is attention
 *       without an opinion, and ten of them beside one "love it" is a product people are curious
 *       about, not one they love. No row → no score, never zero.
 *   <li><b>The confidence is the count</b> — the mention count and how many distinct communities it
 *       comes from — shown beside the score everywhere, never folded into it. A score from three
 *       mentions and one from three hundred are different facts.
 *   <li><b>The leaning</b> is the score in words at fixed cut-offs: {@code liked} at or above
 *       +0.25, {@code disliked} at or below −0.25, {@code mixed} between, {@code unheard} with no
 *       mention. Cut-offs chosen before the corpus was scored, not to it.
 *   <li><b>The quotes</b> are the newest positive and the newest negative mention in the window,
 *       else the newest of whatever there is — at most two, chosen here so that the page's quotes
 *       and its score come from the same rows.
 * </ul>
 */
public final class ConsensusRule {

  /** One linked mention as the rule reads it. */
  public record Mention(
      long mentionId,
      String community,
      String sentiment,
      double communityWeight,
      Instant postedAt) {}

  /** The score in words. */
  public enum Leaning {
    LIKED,
    MIXED,
    DISLIKED,
    UNHEARD;

    public String dbValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** The row {@code consensus_scores} stores, before it is one. */
  public record Consensus(
      Optional<Double> score,
      Leaning leaning,
      int mentionCount,
      int positiveCount,
      int negativeCount,
      int neutralCount,
      Optional<Double> positiveShare,
      int sourceDiversity,
      List<Long> quoteMentionIds) {
    public Consensus {
      quoteMentionIds = List.copyOf(quoteMentionIds);
    }
  }

  static final double LIKED_AT = 0.25;
  static final double DISLIKED_AT = -0.25;
  static final int QUOTES = 2;

  private ConsensusRule() {}

  /** The consensus over the given mentions, all of which are inside the window. */
  public static Consensus of(List<Mention> mentions) {
    if (mentions.isEmpty()) {
      return new Consensus(
          Optional.empty(), Leaning.UNHEARD, 0, 0, 0, 0, Optional.empty(), 0, List.of());
    }
    double weighted = 0;
    double weights = 0;
    int pos = 0;
    int neg = 0;
    int neu = 0;
    Set<String> communities = new HashSet<>();
    for (Mention m : mentions) {
      int s =
          switch (m.sentiment()) {
            case "positive" -> 1;
            case "negative" -> -1;
            default -> 0;
          };
      if (s > 0) {
        pos++;
      } else if (s < 0) {
        neg++;
      } else {
        neu++;
      }
      weighted += m.communityWeight() * s;
      weights += m.communityWeight();
      communities.add(m.community());
    }
    double score = weights == 0 ? 0 : weighted / weights;
    return new Consensus(
        Optional.of(score),
        leaning(score),
        mentions.size(),
        pos,
        neg,
        neu,
        Optional.of((double) pos / mentions.size()),
        communities.size(),
        quotes(mentions));
  }

  /** The score in words. */
  public static Leaning leaning(double score) {
    if (score >= LIKED_AT) {
      return Leaning.LIKED;
    }
    if (score <= DISLIKED_AT) {
      return Leaning.DISLIKED;
    }
    return Leaning.MIXED;
  }

  /** The newest positive and the newest negative, else the newest; at most two. */
  static List<Long> quotes(List<Mention> mentions) {
    Comparator<Mention> newestFirst =
        Comparator.comparing((Mention m) -> m.postedAt() == null ? Instant.EPOCH : m.postedAt())
            .reversed()
            .thenComparing(Mention::mentionId);
    List<Mention> sorted = new ArrayList<>(mentions);
    sorted.sort(newestFirst);
    List<Long> out = new ArrayList<>();
    sorted.stream()
        .filter(m -> m.sentiment().equals("positive"))
        .findFirst()
        .ifPresent(m -> out.add(m.mentionId()));
    sorted.stream()
        .filter(m -> m.sentiment().equals("negative"))
        .findFirst()
        .ifPresent(m -> out.add(m.mentionId()));
    for (Mention m : sorted) {
      if (out.size() >= QUOTES) {
        break;
      }
      if (!out.contains(m.mentionId())) {
        out.add(m.mentionId());
      }
    }
    return List.copyOf(out);
  }
}
