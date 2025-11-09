package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * What a category tells entity resolution about itself (M3). Optional; everything defaults off.
 *
 * <p>The scorer itself is category-agnostic. Two things it cannot know come from here. Which spec
 * fields identify a product versus a variant of one: every variant of a keyboard shares its layout,
 * but its switch type is a per-variant choice, so a listing that disagrees with the catalog on an
 * identity field is not that product while disagreement on any other field means nothing. And which
 * title phrases mean a listing is not a purchasable instance of any product at all — a bundle with
 * a keycap set, a bare PCB module, a custom order — whose price would be wrong as the product's
 * price; the scorer marks those down far enough that they can reach the review queue but never an
 * automatic link.
 *
 * @param identityFields spec fields every variant of a product shares; each must be declared in the
 *     category's {@code spec_schema}
 * @param nonProductPhrases title phrases (matched on whole normalized tokens) that mark a bundle, a
 *     part or a custom order
 */
public record ResolutionConfig(List<String> identityFields, List<String> nonProductPhrases) {

  /** Nothing configured: specs and phrases play no part in scoring. */
  public static final ResolutionConfig NONE = new ResolutionConfig(List.of(), List.of());

  public ResolutionConfig {
    identityFields = identityFields == null ? List.of() : List.copyOf(identityFields);
    nonProductPhrases = nonProductPhrases == null ? List.of() : List.copyOf(nonProductPhrases);
  }

  @JsonCreator
  static ResolutionConfig fromYaml(
      @JsonProperty("identity_fields") List<String> identityFields,
      @JsonProperty("non_product_phrases") List<String> nonProductPhrases) {
    return new ResolutionConfig(identityFields, nonProductPhrases);
  }
}
