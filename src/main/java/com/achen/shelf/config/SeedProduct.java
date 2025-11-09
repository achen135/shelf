package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * A product the catalog is bootstrapped with.
 *
 * <p>Seeds exist so the catalog has canonical rows before any crawling happens. Their normalized
 * brand and model are what entity resolution blocks and scores listings against (M3), and a spec
 * given here is authoritative: it wins over anything derived from linked listings.
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
