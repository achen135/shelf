package com.achen.shelf.mention;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An explicit rank the text gives a product, read by rule (M9): a list marker or a rank word right
 * before the name — {@code 3. Keychron Q1 Pro}, {@code #1 GMMK Pro}, {@code number 2: Athena 75} —
 * on the raw text, because the markers live in the punctuation that normalization removes.
 *
 * <p>Only literal markers count. "Top 5", "best", "my favourite" say nothing about <em>which</em>
 * rank, and a bare number before a name ("2 Epomaker P65", "I bought 2") is a quantity as often as
 * a rank, so it does not count either. Absent on most of the corpus, and says so with a null.
 */
public final class RankRule {

  private RankRule() {}

  /** The rank the text assigns {@code nameTokens}, if it states one. */
  public static Optional<Integer> rank(String rawText, List<String> nameTokens) {
    if (rawText == null || nameTokens.isEmpty()) {
      return Optional.empty();
    }
    StringBuilder name = new StringBuilder();
    for (String t : nameTokens) {
      if (name.length() > 0) {
        name.append("[^\\p{Alnum}]+");
      }
      name.append(Pattern.quote(t));
    }
    // A marker: at a line start "N." / "N)" / "#N", or anywhere "#N", "no. N", "number N",
    // "rank N", "ranked N" — then optional punctuation, then at most two tokens (the brand,
    // usually: "1. Keychron Q1 Pro" names the Q1 Pro), then the name on a token boundary.
    Pattern p =
        Pattern.compile(
            "(?:(?<=^|[\\r\\n])\\s*#?(\\d{1,2})[.):\\-\\u2013\\u2014]|(?:#|\\bno\\.?\\s?|\\bnumber\\s+|\\brank(?:ed)?\\s+)(\\d{1,2}))"
                + "\\s*[:.)\\-\\u2013\\u2014]?\\s*(?:[\\p{Alnum}]+[^\\p{Alnum}\\r\\n]+){0,2}(?<![\\p{Alnum}])"
                + name
                + "(?![\\p{Alnum}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    Matcher m = p.matcher(rawText);
    if (m.find()) {
      String n = m.group(1) != null ? m.group(1) : m.group(2);
      return Optional.of(Integer.parseInt(n));
    }
    return Optional.empty();
  }
}
