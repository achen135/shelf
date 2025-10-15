package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * A product the catalog is bootstrapped with.
 *
 * <p>Seeds exist so the catalog has canonical rows before any crawling happens: a parsed listing
 * that normalizes onto a seed's (brand, model) links straight to it, and everything else is left
 * unresolved for M3.
 *
 * @param brand manufacturer, as displayed
 * @param model model designation, as displayed
 * @param canonicalName the name shown in the UI
 * @param spec optional known spec values, validated against the category spec schema
 */
public record SeedProduct(
    String brand, String model, String canonicalName, Map<String, Object> spec) {

  public SeedProduct {
    spec = spec == null ? Map.of() : Map.copyOf(spec);
  }

  @JsonCreator
  static SeedProduct fromYaml(
      @JsonProperty("brand") String brand,
      @JsonProperty("model") String model,
      @JsonProperty("canonical_name") String canonicalName,
      @JsonProperty("spec") Map<String, Object> spec) {
    return new SeedProduct(brand, model, canonicalName, spec);
  }
}
