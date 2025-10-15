package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * Where a listing's brand comes from.
 *
 * <p>Single-brand storefronts need {@code FIXED}: Keychron's Shopify `vendor` field holds a product
 * series ("Q HE series"), not the brand, so trusting it would create one bogus product per series.
 * Multi-brand retailers carry the real manufacturer in `vendor`, so they use {@code VENDOR}.
 */
public enum BrandSource {
  FIXED,
  VENDOR;

  @JsonCreator
  public static BrandSource fromYaml(String raw) {
    if (raw == null) {
      return VENDOR;
    }
    try {
      return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      // No cause on purpose: Jackson reports the ROOT cause of anything a creator throws,
      // so chaining the IllegalArgumentException would replace this message with the far
      // less useful "No enum constant ...".
      throw new ConfigException(
          "unknown brand_source '" + raw + "' (expected `fixed` or `vendor`)");
    }
  }
}
