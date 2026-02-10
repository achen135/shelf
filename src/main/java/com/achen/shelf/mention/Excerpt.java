package com.achen.shelf.mention;

import com.achen.shelf.crawl.Normalizer;
import com.achen.shelf.resolve.Scorer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The part of a text worth quoting for a mention: the sentence that names the product, with its
 * neighbours when they fit, cut to a length a page can show (M10). The same sentence split the
 * sentiment rule reads, so the quote and the verdict come from the same words.
 */
public final class Excerpt {

  private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|[\\r\\n]+");

  private Excerpt() {}

  /**
   * The naming sentence, extended to its neighbours while the result stays under {@code maxChars};
   * the whole text when no sentence names it; an ellipsis marks a cut.
   */
  public static String of(String rawText, List<String> nameTokens, int maxChars) {
    if (rawText == null) {
      return "";
    }
    String[] sentences = SENTENCE_END.split(rawText, -1);
    int at = -1;
    for (int i = 0; i < sentences.length && at < 0; i++) {
      if (Scorer.indexOfRun(Scorer.tokens(Normalizer.normalize(sentences[i])), nameTokens) >= 0) {
        at = i;
      }
    }
    if (at < 0) {
      return cut(rawText.strip(), maxChars);
    }
    String out = sentences[at].strip();
    if (at + 1 < sentences.length && out.length() + sentences[at + 1].length() + 1 <= maxChars) {
      out = out + " " + sentences[at + 1].strip();
    }
    if (at > 0 && sentences[at - 1].length() + out.length() + 1 <= maxChars) {
      out = sentences[at - 1].strip() + " " + out;
    }
    return cut(out, maxChars);
  }

  private static String cut(String s, int maxChars) {
    if (s.length() <= maxChars) {
      return s;
    }
    int end = s.lastIndexOf(' ', maxChars - 1);
    return s.substring(0, end > maxChars / 2 ? end : maxChars - 1).stripTrailing() + "…";
  }
}
