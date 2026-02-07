package com.achen.shelf.resolve;

import com.achen.shelf.crawl.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * How much a listing looks like a product: a score in {@code [0, 1]} with the reasons behind it.
 *
 * <p>Everything is computed on the normalized title and the candidate's normalized model, both
 * tokenized on spaces ({@code crawl/Normalizer} has already folded case, punctuation and marks).
 * The features, and why each one exists:
 *
 * <ol>
 *   <li><b>The model appears whole</b> — every model token, in order, on token boundaries ("q6 he"
 *       inside "keychron q6 he qmk …", but never "q6" inside "q65"). This was M1's whole rule; it
 *       is now the strongest feature rather than the only one. A model written as one token
 *       ("q1pro") counts too.
 *   <li><b>Token recall</b>, when it does not — how much of the model is there. Tokens that carry a
 *       digit weigh double, and a missing one caps the score low: in this domain "One 2 SF" and
 *       "One 3 SF" differ in exactly that token, and a scorer that shrugged at it would link every
 *       generation of a line to whichever one is in the catalog.
 *   <li><b>A sibling's qualifier next to the model.</b> The catalog's own structure says which
 *       tokens distinguish products within a brand: if a Q6 HE and a Q15 Max are in the block, "he"
 *       and "max" are qualifiers, and a title reading "q2 he" or "q8 max" is about a relative of
 *       the Q2, not the Q2. Only tokens touching or inside the matched span count — "65" in "… 65%
 *       layout" three words later is a layout, not a model.
 *   <li><b>A late mention</b> — a model that first appears deep into the title is more often an
 *       accessory or a compatibility note than the product itself.
 *   <li><b>A spec conflict</b> on one of the category's identity fields (see {@code
 *       ResolutionConfig}): a 65% listing is not a product the catalog knows is 75%.
 *   <li><b>A non-product phrase</b> from the category file — "bundle", "module", "with pbtfans" —
 *       which says the listing is a bundle, a part or a custom order rather than the product. Heavy
 *       enough that a whole-model match lands in review, never in an automatic link.
 * </ol>
 *
 * <p>The weights are hand-set, not fitted, so that every score can be read back as a sentence;
 * their effect is measured, not assumed, by {@code shelf eval resolution} against labeled pairs.
 */
public final class Scorer {

  /**
   * A score and, for the review queue and the eval report, why.
   *
   * @param value in [0, 1]
   * @param reasons the features that fired, as sentences
   * @param start index of the first title token the model matched, or -1 when nothing matched
   * @param end index of the last, inclusive; the span M9's mention matcher reads a sentence around
   */
  public record Score(double value, List<String> reasons, int start, int end) {
    public Score {
      reasons = List.copyOf(reasons);
    }

    /** The v1 shape: a score with no span. */
    public Score(double value, List<String> reasons) {
      this(value, reasons, -1, -1);
    }

    public static final Score NONE = new Score(0, List.of("no model token in the title"), -1, -1);

    public boolean matched() {
      return start >= 0;
    }
  }

  /**
   * Where a name was found in a token list and how much of it: the shared core of listing and
   * mention scoring (features 1 and 2 above). {@code start}/{@code end} bound the matched span;
   * {@code modelSet} is every token that counts as part of the name there, so a qualifier check can
   * tell a sibling's token inside the span from the name's own.
   */
  record Located(double base, int start, int end, Set<String> modelSet, List<String> reasons) {
    static final Located NONE = new Located(0, -1, -1, Set.of(), List.of());

    boolean found() {
      return start >= 0;
    }
  }

  static final double MODEL_WHOLE = 1.0;
  static final double ALL_TOKENS_SCATTERED = 0.8;
  static final double PARTIAL_SCALE = 0.6;
  static final double DESIGNATOR_MISSING_SCALE = 0.3;
  static final double QUALIFIER_PENALTY = 0.35;
  static final int QUALIFIER_CAP = 2;
  static final double LATE_MENTION_PENALTY = 0.1;
  static final int LATE_MENTION_AFTER = 4;
  static final double SPEC_CONFLICT_PENALTY = 0.3;
  static final double NON_PRODUCT_PENALTY = 0.5;

  private final List<String> identityFields;
  private final List<String> nonProductPhrases;

  /**
   * @param identityFields spec fields every variant of a product shares; a disagreement on one is a
   *     penalty. Empty means specs play no part.
   * @param nonProductPhrases phrases whose presence in a title marks a bundle, a part or a custom
   *     order; normalized here, matched on whole tokens
   */
  public Scorer(Collection<String> identityFields, Collection<String> nonProductPhrases) {
    this.identityFields = List.copyOf(identityFields);
    this.nonProductPhrases =
        nonProductPhrases.stream().map(Normalizer::normalize).filter(p -> !p.isEmpty()).toList();
  }

  /** A scorer with neither identity fields nor non-product phrases. */
  public Scorer() {
    this(List.of(), List.of());
  }

  /** Scores {@code listing} against {@code candidate}, given the candidate's siblings' tokens. */
  public Score score(Listing listing, Candidate candidate, Set<String> siblingTokens) {
    List<String> title = tokens(listing.titleNorm());
    List<String> model = tokens(candidate.modelNorm());
    if (title.isEmpty() || model.isEmpty()) {
      return Score.NONE;
    }
    Located at = locate(title, model, true);
    if (!at.found()) {
      return Score.NONE;
    }
    List<String> reasons = new ArrayList<>(at.reasons());
    double base = at.base();
    int start = at.start();
    int end = at.end();
    Set<String> modelSet = at.modelSet();

    // 3: a sibling's qualifier touching or inside the matched span
    List<String> qualifiers = touching(title, start, end, modelSet, siblingTokens);
    double penalty = 0;
    if (!qualifiers.isEmpty()) {
      penalty += QUALIFIER_PENALTY * Math.min(qualifiers.size(), QUALIFIER_CAP);
      reasons.add("qualifier of a sibling product next to the model: " + qualifiers);
    }

    // 4: a late mention
    if (start > LATE_MENTION_AFTER) {
      penalty += LATE_MENTION_PENALTY;
      reasons.add("model first mentioned at token " + (start + 1));
    }

    // 5: identity-field spec conflicts
    List<String> conflicts = specConflicts(listing.spec(), candidate.spec());
    if (!conflicts.isEmpty()) {
      penalty += SPEC_CONFLICT_PENALTY;
      reasons.add("spec conflict on " + conflicts);
    }

    // 6: the title says this is not the product itself
    List<String> flags =
        nonProductPhrases.stream()
            .filter(p -> Normalizer.containsModel(listing.titleNorm(), p))
            .toList();
    if (!flags.isEmpty()) {
      penalty += NON_PRODUCT_PENALTY;
      reasons.add("title marks a bundle, part or custom order: " + flags);
    }

    double value = Math.max(0, Math.min(1, base - penalty));
    return new Score(value, reasons, start, end);
  }

  /**
   * Features 1 and 2: the name whole on token boundaries (also as one token, and — new in M9 — a
   * one-token name written apart, "hack 70" for {@code hack70}), else, when {@code allowPartial},
   * weighted token recall with a missing numbered token scaled down. Free text never takes the
   * partial branch: in a two-hundred-word comment, the scattered tokens of a model mean nothing.
   */
  static Located locate(List<String> text, List<String> model, boolean allowPartial) {
    if (text.isEmpty() || model.isEmpty()) {
      return Located.NONE;
    }
    Set<String> modelSet = new LinkedHashSet<>(model);
    int whole = indexOfRun(text, model);
    if (whole >= 0) {
      return new Located(
          MODEL_WHOLE, whole, whole + model.size() - 1, modelSet, List.of("model appears whole"));
    }
    if (model.size() > 1) {
      String joined = String.join("", model);
      int asOneToken = text.indexOf(joined);
      if (asOneToken >= 0) {
        modelSet.add(joined);
        return new Located(
            MODEL_WHOLE, asOneToken, asOneToken, modelSet, List.of("model appears as one token"));
      }
    } else {
      List<String> apart = splitLettersFromDigits(model.get(0));
      if (apart.size() > 1) {
        int written = indexOfRun(text, apart);
        if (written >= 0) {
          modelSet.addAll(apart);
          return new Located(
              MODEL_WHOLE,
              written,
              written + apart.size() - 1,
              modelSet,
              List.of("model appears written apart (" + String.join(" ", apart) + ")"));
        }
      }
    }
    if (!allowPartial) {
      return Located.NONE;
    }
    double have = 0;
    double total = 0;
    boolean designatorMissing = false;
    List<String> missing = new ArrayList<>();
    int start = Integer.MAX_VALUE;
    int end = -1;
    for (String t : model) {
      double w = hasDigit(t) ? 2 : 1;
      total += w;
      int at = text.indexOf(t);
      if (at >= 0) {
        have += w;
        start = Math.min(start, at);
        end = Math.max(end, at);
      } else {
        missing.add(t);
        designatorMissing |= hasDigit(t);
      }
    }
    if (end < 0) {
      return Located.NONE;
    }
    List<String> reasons = new ArrayList<>();
    double recall = have / total;
    double base;
    if (missing.isEmpty()) {
      base = ALL_TOKENS_SCATTERED;
      reasons.add("every model token present, not together");
    } else {
      base = PARTIAL_SCALE * recall;
      reasons.add("missing model token(s) " + missing + ", recall " + fmt(recall));
    }
    if (designatorMissing) {
      base *= DESIGNATOR_MISSING_SCALE;
      reasons.add("a numbered model token is missing");
    }
    return new Located(base, start, end, modelSet, reasons);
  }

  /**
   * A one-token name split where letters meet digits: {@code hack70} → [hack, 70], {@code 60he} →
   * [60, he], {@code kbd67} → [kbd, 67]. A token that is all letters or all digits stays whole.
   */
  static List<String> splitLettersFromDigits(String token) {
    List<String> parts = new ArrayList<>();
    StringBuilder run = new StringBuilder();
    boolean lastDigit = false;
    for (int i = 0; i < token.length(); i++) {
      char ch = token.charAt(i);
      boolean digit = Character.isDigit(ch);
      if (i > 0 && digit != lastDigit) {
        parts.add(run.toString());
        run.setLength(0);
      }
      run.append(ch);
      lastDigit = digit;
    }
    parts.add(run.toString());
    return parts;
  }

  /**
   * Feature 3's evidence: the tokens touching or inside the matched span that belong to {@code
   * vocabulary} (a sibling's qualifiers, or — for mentions — another brand's name) and not to the
   * matched name itself.
   */
  static List<String> touching(
      List<String> text, int start, int end, Set<String> modelSet, Set<String> vocabulary) {
    Set<String> touching = new LinkedHashSet<>();
    if (start > 0) {
      touching.add(text.get(start - 1));
    }
    if (end < text.size() - 1) {
      touching.add(text.get(end + 1));
    }
    for (int i = start; i <= end; i++) {
      if (!modelSet.contains(text.get(i))) {
        touching.add(text.get(i));
      }
    }
    return touching.stream().filter(vocabulary::contains).toList();
  }

  /** Identity fields present on both sides with different values. */
  List<String> specConflicts(Map<String, Object> listing, Map<String, Object> candidate) {
    List<String> conflicts = new ArrayList<>();
    for (String field : identityFields) {
      Object a = listing.get(field);
      Object b = candidate.get(field);
      if (a != null && b != null && !Objects.equals(String.valueOf(a), String.valueOf(b))) {
        conflicts.add(field);
      }
    }
    return conflicts;
  }

  /** Space-separated tokens of a normalized string; empty for blank input. */
  public static List<String> tokens(String norm) {
    if (norm == null || norm.isBlank()) {
      return List.of();
    }
    return List.of(norm.strip().split(" "));
  }

  /** Index of the first occurrence of {@code run} as a contiguous sub-list of {@code in}. */
  public static int indexOfRun(List<String> in, List<String> run) {
    outer:
    for (int i = 0; i + run.size() <= in.size(); i++) {
      for (int j = 0; j < run.size(); j++) {
        if (!in.get(i + j).equals(run.get(j))) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static boolean hasDigit(String token) {
    for (int i = 0; i < token.length(); i++) {
      if (Character.isDigit(token.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static String fmt(double d) {
    return String.format(java.util.Locale.ROOT, "%.2f", d);
  }
}
