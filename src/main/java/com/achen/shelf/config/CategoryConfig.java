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
 * @param communities where people talk about these products (M8); optional, empty means the
 *     category has no community track
 * @param sentiment the category's own words of praise and complaint (M9); optional
 */
public record CategoryConfig(
    String name,
    Map<String, SpecField> specSchema,
    List<Retailer> retailers,
    List<SeedProduct> seedProducts,
    ResolutionConfig resolution,
    List<Community> communities,
    SentimentConfig sentiment) {

  public CategoryConfig {
    specSchema = specSchema == null ? Map.of() : Map.copyOf(specSchema);
    retailers = retailers == null ? List.of() : List.copyOf(retailers);
    seedProducts = seedProducts == null ? List.of() : List.copyOf(seedProducts);
    resolution = resolution == null ? ResolutionConfig.NONE : resolution;
    communities = communities == null ? List.of() : List.copyOf(communities);
    sentiment = sentiment == null ? SentimentConfig.NONE : sentiment;
  }

  /** The v1 + M8 shape, for callers that build a config by hand. */
  public CategoryConfig(
      String name,
      Map<String, SpecField> specSchema,
      List<Retailer> retailers,
      List<SeedProduct> seedProducts,
      ResolutionConfig resolution,
      List<Community> communities) {
    this(name, specSchema, retailers, seedProducts, resolution, communities, null);
  }

  /** The retailers a crawl should actually visit, in config order. */
  public List<Retailer> enabledRetailers() {
    return retailers.stream().filter(Retailer::enabled).toList();
  }

  /** Looks a retailer up by its config name. */
  public Optional<Retailer> retailer(String retailerName) {
    return retailers.stream().filter(r -> r.name().equals(retailerName)).findFirst();
  }

  /** The communities an ingest should actually read, in config order. */
  public List<Community> enabledCommunities() {
    return communities.stream().filter(Community::enabled).toList();
  }

  @JsonCreator
  static CategoryConfig fromYaml(
      @JsonProperty("name") String name,
      @JsonProperty("spec_schema") Map<String, SpecField> specSchema,
      @JsonProperty("retailers") List<Retailer> retailers,
      @JsonProperty("seed_products") List<SeedProduct> seedProducts,
      @JsonProperty("resolution") ResolutionConfig resolution,
      @JsonProperty("communities") List<Community> communities,
      @JsonProperty("sentiment") SentimentConfig sentiment) {
    return new CategoryConfig(
        name, specSchema, retailers, seedProducts, resolution, communities, sentiment);
  }
}
