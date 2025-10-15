package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/** The value types a category spec field may declare. */
public enum SpecType {
  STRING,
  BOOLEAN,
  INTEGER,
  NUMBER;

  /** Accepts the lower-case spellings used in the YAML (`string`, `boolean`, …). */
  @JsonCreator
  public static SpecType fromYaml(String raw) {
    if (raw == null) {
      throw new ConfigException("spec field is missing `type`");
    }
    try {
      return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      // No cause on purpose: Jackson reports the ROOT cause of anything a creator throws,
      // so chaining the IllegalArgumentException would replace this message with the far
      // less useful "No enum constant ...".
      throw new ConfigException(
          "unknown spec field type '"
              + raw
              + "' (expected one of: string, boolean, integer, number)");
    }
  }
}
