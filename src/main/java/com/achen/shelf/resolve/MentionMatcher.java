package com.achen.shelf.resolve;

import com.achen.shelf.crawl.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which catalog products a piece of free text names, and how surely — the {@link Scorer}'s core
 * behind a text front-end (M9).
 *
 * <p>A comment is not a listing title. A title names one product, near its start, with the brand
 * the retailer files it under; a comment names none, one or several, anywhere, usually without the
 * brand, in a paragraph where the scattered tokens of any model can be found by accident. So the
 * matcher keeps what transfers — {@link Scorer#locate the same "is the name here, whole" core} and
 * {@link Scorer#touching the same sibling-qualifier check} — and replaces what does not. The rules,
 * all fixed before the labeled set was scored (docs/benchmarks/m9-mention-resolution.md):
 *
 * <ol>
 *   <li><b>A name appears whole</b> — the model, or any declared alias, on token boundaries, as one
 *       token, or (a one-token name) written apart: "hack 70" for {@code hack70}. Score 1.0.
 *       Nothing partial: there is no listing-style abbreviation to recover in free text, and in a
 *       long comment "one … x … mini" scattered is noise, not a Ducky. Text with no whole name gets
 *       no row.
 *   <li><b>A bare word is not a product name unless its maker is named.</b> A one-token name with
 *       no digit ("studio", "flow") whose brand appears nowhere in the text is not a match at all —
 *       "my studio", "dry studio" are not the HHKB Studio, and no score could tell a human
 *       otherwise.
 *   <li><b>A sibling's qualifier touching the span</b> — −0.35 each, at most two, exactly as for a
 *       listing: "60he v2" is not the 60HE+.
 *   <li><b>Another catalog brand touching the span</b> — −0.35: the free-text stand-in for blocking
 *       by brand, which a comment cannot be.
 *   <li><b>The brand unstated</b> and the name a single token — −0.2, so "rt75" alone is a proposal
 *       for a human (another maker's RT75 exists) while "gmmk pro" or "rainy 75 pro", two or three
 *       tokens that identify themselves, stand on their own.
 * </ol>
 *
 * <p>Thresholds are the resolver's: 0.9 links, 0.4 proposes. When two products claim overlapping
 * spans ("60he v2" for both the 60HE V2 and the 60HE+) only the better claim survives, ties to the
 * longer span, then the longer name — one span, one product, as {@link Resolver#rank} breaks its
 * ties.
 */
public final class MentionMatcher {

  static final double BRAND_UNSTATED_PENALTY = 0.2;
  static final double OTHER_BRAND_PENALTY = 0.35;

  /** Free text as the matcher reads it: normalized tokens and which catalog brands it names. */
  public record Text(List<String> tokens, Set<String> brandsStated) {
    public Text {
      tokens = List.copyOf(tokens);
      brandsStated = Set.copyOf(brandsStated);
    }
  }

  /** One product the text names, with the score and the span. */
  public record Match(Candidate candidate, Scorer.Score score, String phrase) {
    public Resolver.Outcome outcome(Resolver.Thresholds thresholds) {
      return thresholds.auto() <= score.value()
          ? Resolver.Outcome.AUTO
          : thresholds.review() <= score.value() ? Resolver.Outcome.REVIEW : Resolver.Outcome.NONE;
    }
  }

  private final Catalog catalog;
  private final Resolver.Thresholds thresholds;
  private final Set<String> brandTokens;

  public MentionMatcher(Catalog catalog, Resolver.Thresholds thresholds) {
    this.catalog = catalog;
    this.thresholds = thresholds;
    Set<String> tokens = new HashSet<>();
    for (String brand : catalog.brands()) {
      tokens.addAll(Scorer.tokens(brand));
    }
    this.brandTokens = Set.copyOf(tokens);
  }

  public Resolver.Thresholds thresholds() {
    return thresholds;
  }

  public Catalog catalog() {
    return catalog;
  }

  /** Normalizes raw text and notes which catalog brands it states anywhere. */
  public Text text(String raw) {
    String norm = Normalizer.normalize(raw);
    Set<String> stated = new LinkedHashSet<>();
    for (String brand : catalog.brands()) {
      if (Normalizer.containsModel(norm, brand)) {
        stated.add(brand);
      }
    }
    return new Text(Scorer.tokens(norm), stated);
  }

  /** Scores one product against the text: the best of its names, then the rules above. */
  public Scorer.Score score(Text text, Candidate candidate) {
    Scorer.Located best = Scorer.Located.NONE;
    String bestName = null;
    for (String name : candidate.namesNorm()) {
      Scorer.Located at = Scorer.locate(text.tokens(), Scorer.tokens(name), false);
      if (at.found() && (!best.found() || at.end() - at.start() > best.end() - best.start())) {
        best = at;
        bestName = name;
      }
    }
    if (!best.found()) {
      return Scorer.Score.NONE;
    }
    List<String> nameTokens = Scorer.tokens(bestName);
    boolean brandStated = text.brandsStated().contains(candidate.brandNorm());
    List<String> reasons = new ArrayList<>(best.reasons());
    if (!bestName.equals(candidate.modelNorm())) {
      reasons.add("matched the alias '" + bestName + "'");
    }

    // 2: a bare word needs its maker
    if (nameTokens.size() == 1 && !hasDigit(nameTokens.get(0)) && !brandStated) {
      return new Scorer.Score(
          0,
          List.of("'" + bestName + "' is a bare word and the brand is not named"),
          best.start(),
          best.end());
    }

    double penalty = 0;

    // 3: a sibling's qualifier touching the span
    List<String> qualifiers =
        Scorer.touching(
            text.tokens(),
            best.start(),
            best.end(),
            best.modelSet(),
            catalog.siblingTokens(candidate));
    if (!qualifiers.isEmpty()) {
      penalty += Scorer.QUALIFIER_PENALTY * Math.min(qualifiers.size(), Scorer.QUALIFIER_CAP);
      reasons.add("qualifier of a sibling product next to the name: " + qualifiers);
    }

    // 4: another catalog brand touching the span
    Set<String> others = new HashSet<>(brandTokens);
    others.removeAll(Scorer.tokens(candidate.brandNorm()));
    List<String> otherBrands =
        Scorer.touching(text.tokens(), best.start(), best.end(), best.modelSet(), others);
    if (!otherBrands.isEmpty()) {
      penalty += OTHER_BRAND_PENALTY;
      reasons.add("another catalog brand next to the name: " + otherBrands);
    }

    // 5: the brand unstated, the name a single token
    if (!brandStated && nameTokens.size() == 1) {
      penalty += BRAND_UNSTATED_PENALTY;
      reasons.add("brand not named anywhere and the name is one token");
    }

    double value = Math.max(0, Math.min(1, best.base() - penalty));
    return new Scorer.Score(value, reasons, best.start(), best.end());
  }

  /** Every product the text names at or above the review threshold, one per span, best first. */
  public List<Match> matches(Text text) {
    List<Match> claims = new ArrayList<>();
    for (Candidate c : catalog.all()) {
      Scorer.Score s = score(text, c);
      if (s.matched() && s.value() >= thresholds.review()) {
        claims.add(
            new Match(c, s, String.join(" ", text.tokens().subList(s.start(), s.end() + 1))));
      }
    }
    claims.sort(
        Comparator.comparingDouble((Match m) -> m.score().value())
            .reversed()
            .thenComparing(m -> -(m.score().end() - m.score().start()))
            .thenComparing(m -> -m.candidate().modelNorm().length()));
    List<Match> kept = new ArrayList<>();
    for (Match m : claims) {
      boolean overlaps =
          kept.stream()
              .anyMatch(
                  k ->
                      m.score().start() <= k.score().end() && k.score().start() <= m.score().end());
      if (!overlaps) {
        kept.add(m);
      }
    }
    return kept;
  }

  private static boolean hasDigit(String token) {
    for (int i = 0; i < token.length(); i++) {
      if (Character.isDigit(token.charAt(i))) {
        return true;
      }
    }
    return false;
  }
}
