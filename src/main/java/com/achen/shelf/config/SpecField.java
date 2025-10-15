package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One field in a category's spec schema, e.g. {@code switch_type: {type: string, enum: [...]}}.
 *
 * @param type the value type a product's spec must supply for this field
 * @param enumValues permitted values; empty means "any value of {@code type}". Only meaningful for
 *     {@link SpecType#STRING}.
 * @param unit free-text unit for display (e.g. "mm", "hz"); not validated
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SpecField(SpecType type, List<String> enumValues, String unit) {

  public SpecField {
    enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
  }

  /** Jackson entry point: maps the YAML key `enum` onto {@link #enumValues}. */
  @com.fasterxml.jackson.annotation.JsonCreator
  static SpecField fromYaml(
      @com.fasterxml.jackson.annotation.JsonProperty("type") SpecType type,
      @com.fasterxml.jackson.annotation.JsonProperty("enum") List<String> enumValues,
      @com.fasterxml.jackson.annotation.JsonProperty("unit") String unit) {
    return new SpecField(type, enumValues, unit);
  }
}
