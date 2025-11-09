package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A whole category, as loaded from {@code categories/<name>.yaml}.
 *
 * <p>This is the unit the "general in architecture, niche in configuration" claim rests on: the
 * crawl, the spec validation and the catalog bootstrap all read this record, so onboarding a
 * category is a new file plus parsers (proven in M7).
 *
 * @param name category id; must match the file name
 * @param specSchema field name to its declared type/enum
 * @param retailers where to look, and how
 * @param seedProducts catalog bootstrap
 * @param resolution what entity resolution should know about this category; optional
 */
public record CategoryConfig(
    String name,
    Map<String, SpecField> specSchema,
    List<Retailer> retailers,
    List<SeedProduct> seedProducts,
    ResolutionConfig resolution) {

  public CategoryConfig {
    specSchema = specSchema == null ? Map.of() : Map.copyOf(specSchema);
    retailers = retailers == null ? List.of() : List.copyOf(retailers);
    seedProducts = seedProducts == null ? List.of() : List.copyOf(seedProducts);
    resolution = resolution == null ? ResolutionConfig.NONE : resolution;
  }

  /** The retailers a crawl should actually visit, in config order. */
  public List<Retailer> enabledRetailers() {
    return retailers.stream().filter(Retailer::enabled).toList();
  }

  /** Looks a retailer up by its config name. */
  public Optional<Retailer> retailer(String retailerName) {
    return retailers.stream().filter(r -> r.name().equals(retailerName)).findFirst();
  }

  @JsonCreator
  static CategoryConfig fromYaml(
      @JsonProperty("name") String name,
      @JsonProperty("spec_schema") Map<String, SpecField> specSchema,
      @JsonProperty("retailers") List<Retailer> retailers,
      @JsonProperty("seed_products") List<SeedProduct> seedProducts,
      @JsonProperty("resolution") ResolutionConfig resolution) {
    return new CategoryConfig(name, specSchema, retailers, seedProducts, resolution);
  }
}
