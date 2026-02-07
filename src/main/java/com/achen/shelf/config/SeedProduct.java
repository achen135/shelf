package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
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
 * @param aliases other names the product goes by in free text (M9) — "gmmk pro", "hack 70"; mention
 *     resolution reads each as it reads the model. Never needed for a listing title, which names
 *     the product the way the retailer does
 */
public record SeedProduct(
    String brand,
    String model,
    String canonicalName,
    Map<String, Object> spec,
    List<String> aliases) {

  public SeedProduct {
    spec = spec == null ? Map.of() : Map.copyOf(spec);
    aliases = aliases == null ? List.of() : List.copyOf(aliases);
  }

  /** A seed with no aliases — the v1 shape, kept for the tests that build seeds by hand. */
  public SeedProduct(String brand, String model, String canonicalName, Map<String, Object> spec) {
    this(brand, model, canonicalName, spec, List.of());
  }

  @JsonCreator
  static SeedProduct fromYaml(
      @JsonProperty("brand") String brand,
      @JsonProperty("model") String model,
      @JsonProperty("canonical_name") String canonicalName,
      @JsonProperty("spec") Map<String, Object> spec,
      @JsonProperty("aliases") List<String> aliases) {
    return new SeedProduct(brand, model, canonicalName, spec, aliases);
  }
}
