package com.achen.shelf.crawl;

import java.text.Normalizer.Form;

/**
 * Brand and model normalization — the identity rules the catalog is keyed on.
 *
 * <p>The rules, in order: Unicode NFKD, drop combining marks, lower-case, replace every run of
 * non-alphanumeric characters with a single space, trim. So {@code "Keychron Q6-HE"}, {@code
 * "KEYCHRON Q6 HE"} and {@code "Keychron Q6/HE"} all become {@code "keychron q6 he"}.
 *
 * <p>Two deliberate non-rules. Nothing is stemmed or abbreviated — "pro" and "professional" stay
 * different, because collapsing them silently merges genuinely different SKUs. And nothing is
 * removed as noise ("mechanical", "keyboard", "RGB"): a stop-list is a scoring decision, and
 * scoring belongs to M3's entity resolution, where it can be measured against labels rather than
 * guessed at here.
 *
 * <p>Digits are kept adjacent to letters as-is, so "60%" normalizes to "60" and "TH26" stays "th26"
 * — model designations in this category are mostly alphanumeric, and splitting them would destroy
 * the very token that identifies the product.
 */
public final class Normalizer {

  private Normalizer() {}

  /** Applies the normalization rules above. Null and blank inputs return "". */
  public static String normalize(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String decomposed = java.text.Normalizer.normalize(raw, Form.NFKD);
    StringBuilder sb = new StringBuilder(decomposed.length());
    boolean lastWasSpace = true; // leading separators collapse away
    for (int i = 0; i < decomposed.length(); i++) {
      char c = decomposed.charAt(i);
      if (Character.getType(c) == Character.NON_SPACING_MARK) {
        continue; // diacritic left over from the NFKD decomposition
      }
      if (Character.isLetterOrDigit(c)) {
        sb.append(Character.toLowerCase(c));
        lastWasSpace = false;
      } else if (!lastWasSpace) {
        sb.append(' ');
        lastWasSpace = true;
      }
    }
    return sb.toString().strip();
  }

  /**
   * True when {@code modelNorm} appears in {@code titleNorm} on whole-token boundaries.
   *
   * <p>Token boundaries are the whole point: "q6 he" must match inside "keychron q6 he qmk wireless
   * custom keyboard", while "q6" must NOT match "keychron q65". Both strings are assumed to be
   * normalized already.
   */
  public static boolean containsModel(String titleNorm, String modelNorm) {
    if (titleNorm.isEmpty() || modelNorm.isEmpty()) {
      return false;
    }
    return (" " + titleNorm + " ").contains(" " + modelNorm + " ");
  }
}
