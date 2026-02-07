package com.achen.shelf.mention;

import com.achen.shelf.config.SentimentConfig;
import com.achen.shelf.crawl.Normalizer;
import com.achen.shelf.resolve.Scorer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a text says about a product it names, read by rule (M9; Spec v2 §2 — the cheapest extraction
 * first, measured before anything opaque is reached for).
 *
 * <p>The unit is <em>the sentence naming the product and its neighbours</em>: the raw text is split
 * at sentence punctuation and line breaks, the first sentence in which the name appears whole is
 * found, and that sentence with the one before and the one after is what is read — twelve tokens
 * either side of the name, no further. The rest of the text is not: a comment that praises one
 * board and pans another should not average out. The first version of this rule read the naming
 * sentence alone, eight tokens either side, and the labeled sample showed the verdict lands in the
 * next sentence as often as not ("Just bought epomaker he108. Glad with it :)") or a title sentence
 * before ("I Bought The Dumbest Headphones Ever Made. | I tried the Dyson Zone…");
 * docs/benchmarks/m9-mention-resolution.md records both passes.
 *
 * <ol>
 *   <li><b>A question is neutral.</b> The naming sentence ends in {@code ?} → neutral, whatever its
 *       neighbours contain: "is it worth it" is a question, not a verdict.
 *   <li><b>Losing a comparison is negative.</b> {@code than}, {@code from} or {@code over} in the
 *       four tokens before the name, with a positive word before that ("better than the X", "a step
 *       up from the X", "pick this over the X") → the named product is the one that lost.
 *   <li><b>Otherwise, count the lexicon.</b> Positive and negative words and phrases in the window
 *       — general English in this class, the category's own verdict words ("creamy", "mushy") from
 *       its {@code sentiment:} config — each flipped when a negator ({@code not}, {@code never},
 *       the {@code t} of "don't", {@code almost}, …) sits within three tokens before it. Longer
 *       phrases win over the words inside them ("no regrets" is one positive, not a negator and a
 *       negative). Positive minus negative decides; zero is neutral.
 * </ol>
 *
 * <p>The evidence is returned with the verdict so a row can say "positive: love" rather than just
 * be positive.
 */
public final class SentimentRule {

  /** The verdict. */
  public enum Sentiment {
    POSITIVE,
    NEGATIVE,
    NEUTRAL;

    public String dbValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * A reading.
   *
   * @param sentiment the verdict
   * @param score (positive − negative) / (positive + negative) over the window, 0 when nothing
   *     fired; the sign is the verdict, the magnitude how one-sided the sentence was
   * @param evidence the words and rules that fired
   */
  public record Reading(Sentiment sentiment, double score, List<String> evidence) {
    public Reading {
      evidence = List.copyOf(evidence);
    }

    static final Reading NOT_FOUND = new Reading(Sentiment.NEUTRAL, 0, List.of("name not found"));
  }

  static final int WINDOW = 12;
  static final int NEGATION_REACH = 3;
  static final int COMPARATIVE_REACH = 4;

  private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|[\\r\\n]+");

  static final List<String> POSITIVE =
      List.of(
          "love",
          "loved",
          "loving",
          "great",
          "good",
          "nice",
          "amazing",
          "awesome",
          "perfect",
          "excellent",
          "best",
          "favorite",
          "favourite",
          "recommend",
          "recommended",
          "happy",
          "glad",
          "impressive",
          "impressed",
          "solid",
          "fantastic",
          "incredible",
          "incredibly",
          "beautiful",
          "cool",
          "worth",
          "better",
          "wow",
          "insane",
          "luxurious",
          "enjoy",
          "enjoying",
          "enjoyed",
          "fun",
          "cute",
          "gorgeous",
          "stunning",
          "op",
          "goat",
          "banger",
          "blew me away",
          "step up",
          "just get",
          "trust me",
          "no regrets",
          "ticks all the boxes",
          "more than great",
          "highly recommend");

  static final List<String> NEGATIVE =
      List.of(
          "bad",
          "worst",
          "worse",
          "terrible",
          "awful",
          "trash",
          "garbage",
          "scam",
          "scams",
          "scamming",
          "sucks",
          "sucked",
          "suck",
          "hate",
          "hated",
          "disappointed",
          "disappointing",
          "disappointment",
          "regret",
          "returned",
          "refund",
          "broke",
          "broken",
          "issue",
          "issues",
          "problem",
          "problems",
          "overpriced",
          "unhinged",
          "dumb",
          "dumbest",
          "stupid",
          "pointless",
          "wrong",
          "ugly",
          "meh",
          "mediocre",
          "poor",
          "kill",
          "waste",
          "wasted",
          "junk",
          "flimsy",
          "avoid",
          "cheap looking",
          "fell apart",
          "dont buy",
          "don t buy");

  static final Set<String> NEGATORS =
      Set.of(
          "not", "no", "never", "dont", "doesnt", "isnt", "wasnt", "cant", "cannot", "wouldnt",
          "couldnt", "hardly", "without", "almost", "t", "nor");

  static final Set<String> COMPARATIVE_CONNECTIVES = Set.of("than", "from", "over");

  private final List<List<String>> positive;
  private final List<List<String>> negative;

  public SentimentRule(SentimentConfig config) {
    List<List<String>> pos = new ArrayList<>();
    List<List<String>> neg = new ArrayList<>();
    for (String p : POSITIVE) {
      pos.add(Scorer.tokens(Normalizer.normalize(p)));
    }
    for (String p : config.positive()) {
      pos.add(Scorer.tokens(Normalizer.normalize(p)));
    }
    for (String p : NEGATIVE) {
      neg.add(Scorer.tokens(Normalizer.normalize(p)));
    }
    for (String p : config.negative()) {
      neg.add(Scorer.tokens(Normalizer.normalize(p)));
    }
    // Longest first, so a phrase claims its tokens before a word inside it can.
    pos.sort((a, b) -> b.size() - a.size());
    neg.sort((a, b) -> b.size() - a.size());
    this.positive = List.copyOf(pos);
    this.negative = List.copyOf(neg);
  }

  /** The general lexicon alone. */
  public SentimentRule() {
    this(SentimentConfig.NONE);
  }

  /** Reads the sentence of {@code rawText} in which {@code nameTokens} appears whole. */
  public Reading read(String rawText, List<String> nameTokens) {
    if (rawText == null || nameTokens.isEmpty()) {
      return Reading.NOT_FOUND;
    }
    String[] sentences = SENTENCE_END.split(rawText, -1);
    for (int i = 0; i < sentences.length; i++) {
      List<String> own = Scorer.tokens(Normalizer.normalize(sentences[i]));
      int at = Scorer.indexOfRun(own, nameTokens);
      if (at < 0) {
        continue;
      }
      List<String> before =
          i > 0 ? Scorer.tokens(Normalizer.normalize(sentences[i - 1])) : List.of();
      List<String> after =
          i + 1 < sentences.length
              ? Scorer.tokens(Normalizer.normalize(sentences[i + 1]))
              : List.of();
      List<String> tokens = new ArrayList<>(before.size() + own.size() + after.size());
      tokens.addAll(before);
      tokens.addAll(own);
      tokens.addAll(after);
      int start = before.size() + at;
      return readSentence(sentences[i].strip(), tokens, start, start + nameTokens.size() - 1);
    }
    return Reading.NOT_FOUND;
  }

  private Reading readSentence(String sentence, List<String> tokens, int start, int end) {
    List<String> evidence = new ArrayList<>();
    if (sentence.endsWith("?")) {
      evidence.add("a question");
      return new Reading(Sentiment.NEUTRAL, 0, evidence);
    }
    int from = Math.max(0, start - WINDOW);
    int to = Math.min(tokens.size(), end + 1 + WINDOW);

    // 2: the name follows a comparative connective that follows praise — it lost
    for (int i = Math.max(from, start - COMPARATIVE_REACH); i < start; i++) {
      if (COMPARATIVE_CONNECTIVES.contains(tokens.get(i))) {
        List<String> before = tokens.subList(from, i);
        List<String> praise = hits(before, positive, from, start);
        if (!praise.isEmpty()) {
          evidence.add("loses a comparison: '" + praise.get(0) + " " + tokens.get(i) + "'");
          return new Reading(Sentiment.NEGATIVE, -1, evidence);
        }
      }
    }

    // 3: the lexicon, negated where a negator precedes
    boolean[] claimed = new boolean[tokens.size()];
    for (int i = start; i <= end; i++) {
      claimed[i] = true;
    }
    int pos = 0;
    int neg = 0;
    for (List<String> phrase : positive) {
      for (int i : occurrences(tokens, phrase, from, to, claimed)) {
        boolean negated = negated(tokens, i, from);
        if (negated) {
          neg++;
          evidence.add("not " + String.join(" ", phrase));
        } else {
          pos++;
          evidence.add(String.join(" ", phrase));
        }
      }
    }
    for (List<String> phrase : negative) {
      for (int i : occurrences(tokens, phrase, from, to, claimed)) {
        boolean negated = negated(tokens, i, from);
        if (negated) {
          pos++;
          evidence.add("not " + String.join(" ", phrase));
        } else {
          neg++;
          evidence.add(String.join(" ", phrase));
        }
      }
    }
    if (pos + neg == 0) {
      evidence.add("nothing in the lexicon");
      return new Reading(Sentiment.NEUTRAL, 0, evidence);
    }
    double score = (double) (pos - neg) / (pos + neg);
    Sentiment s =
        pos > neg ? Sentiment.POSITIVE : neg > pos ? Sentiment.NEGATIVE : Sentiment.NEUTRAL;
    return new Reading(s, score, evidence);
  }

  /** Start indices of {@code phrase} within [from, to) whose tokens are unclaimed; claims them. */
  private static List<Integer> occurrences(
      List<String> tokens, List<String> phrase, int from, int to, boolean[] claimed) {
    List<Integer> out = new ArrayList<>();
    outer:
    for (int i = from; i + phrase.size() <= to; i++) {
      for (int j = 0; j < phrase.size(); j++) {
        if (claimed[i + j] || !tokens.get(i + j).equals(phrase.get(j))) {
          continue outer;
        }
      }
      for (int j = 0; j < phrase.size(); j++) {
        claimed[i + j] = true;
      }
      out.add(i);
    }
    return out;
  }

  private static List<String> hits(
      List<String> window, List<List<String>> lexicon, int offset, int stopAt) {
    List<String> out = new ArrayList<>();
    for (List<String> phrase : lexicon) {
      int at = Scorer.indexOfRun(window, phrase);
      if (at >= 0 && offset + at < stopAt) {
        out.add(String.join(" ", phrase));
      }
    }
    return out;
  }

  private static boolean negated(List<String> tokens, int at, int from) {
    for (int i = Math.max(from, at - NEGATION_REACH); i < at; i++) {
      if (NEGATORS.contains(tokens.get(i))) {
        return true;
      }
    }
    return false;
  }
}
