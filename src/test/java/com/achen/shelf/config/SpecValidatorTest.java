package com.achen.shelf.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Spec validation keeps what fits the schema and explains what it dropped. */
class SpecValidatorTest {

  private final SpecValidator validator =
      new SpecValidator(
          Map.of(
              "switch_type", new SpecField(SpecType.STRING, List.of("linear", "tactile"), null),
              "hot_swap", new SpecField(SpecType.BOOLEAN, List.of(), null),
              "key_count", new SpecField(SpecType.INTEGER, List.of(), null),
              "weight_kg", new SpecField(SpecType.NUMBER, List.of(), "kg")));

  @Test
  void keepsValuesThatMatchTheSchema() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("switch_type", "linear");
    raw.put("hot_swap", true);
    raw.put("key_count", 68L);
    raw.put("weight_kg", 1.5);

    SpecValidator.Result result = validator.validate(raw);

    assertThat(result.isClean()).isTrue();
    assertThat(result.accepted())
        .containsEntry("switch_type", "linear")
        .containsEntry("hot_swap", true)
        .containsEntry("key_count", 68L)
        .containsEntry("weight_kg", 1.5);
  }

  @Test
  void coercesTheStringFormsThatYamlAndJsonHandUs() {
    SpecValidator.Result result =
        validator.validate(Map.of("hot_swap", "true", "key_count", "104", "weight_kg", "0.75"));

    assertThat(result.isClean()).isTrue();
    assertThat(result.accepted())
        .containsEntry("hot_swap", true)
        .containsEntry("key_count", 104L)
        .containsEntry("weight_kg", 0.75);
  }

  @Test
  void dropsUnknownFieldsWithAReason() {
    SpecValidator.Result result = validator.validate(Map.of("rgb", "yes"));

    assertThat(result.accepted()).isEmpty();
    assertThat(result.warnings()).singleElement().asString().contains("unknown field 'rgb'");
  }

  @Test
  void dropsValuesOutsideAnEnum() {
    SpecValidator.Result result = validator.validate(Map.of("switch_type", "clicky"));

    assertThat(result.accepted()).isEmpty();
    assertThat(result.warnings()).singleElement().asString().contains("'clicky' is not one of");
  }

  @Test
  void dropsValuesOfTheWrongType() {
    SpecValidator.Result result =
        validator.validate(Map.of("key_count", "sixty-eight", "hot_swap", "maybe"));

    assertThat(result.accepted()).isEmpty();
    assertThat(result.warnings()).hasSize(2);
  }

  @Test
  void keepsTheGoodFieldsWhenOnlySomeAreBad() {
    // A retailer inventing one tag must not cost us the whole listing's specs.
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("switch_type", "tactile");
    raw.put("mystery", "?");

    SpecValidator.Result result = validator.validate(raw);

    assertThat(result.accepted()).containsOnlyKeys("switch_type");
    assertThat(result.warnings()).hasSize(1);
  }
}
