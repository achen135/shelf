package com.achen.shelf.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checks product spec values against a category's spec schema.
 *
 * <p>Two callers with different needs, one implementation. The config loader treats any warning as
 * a hard error, because seeds are hand-written. The crawler does the opposite: a retailer can
 * rename a tag or invent a value at any time, and one odd spec field is not a reason to drop a
 * price observation — so parsers keep {@link Result#accepted()} and log the warnings.
 */
public final class SpecValidator {

  private final Map<String, SpecField> schema;

  public SpecValidator(Map<String, SpecField> schema) {
    this.schema = Map.copyOf(schema);
  }

  /**
   * The outcome of validating one spec map.
   *
   * @param accepted values that matched the schema, in insertion order
   * @param warnings human-readable reasons for every value that was dropped
   */
  public record Result(Map<String, Object> accepted, List<String> warnings) {
    public Result {
      accepted = Map.copyOf(accepted);
      warnings = List.copyOf(warnings);
    }

    public boolean isClean() {
      return warnings.isEmpty();
    }
  }

  /** Validates {@code raw}, keeping what fits the schema and explaining what did not. */
  public Result validate(Map<String, Object> raw) {
    Map<String, Object> accepted = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();

    for (Map.Entry<String, Object> e : raw.entrySet()) {
      String key = e.getKey();
      Object value = e.getValue();
      SpecField field = schema.get(key);
      if (field == null) {
        warnings.add("unknown field '" + key + "' (not in the category spec schema)");
        continue;
      }
      if (value == null) {
        warnings.add("field '" + key + "' is null");
        continue;
      }
      Object coerced = coerce(field, value);
      if (coerced == null) {
        warnings.add(
            "field '"
                + key
                + "' expected "
                + field.type().name().toLowerCase(Locale.ROOT)
                + " but got '"
                + value
                + "'");
        continue;
      }
      if (!field.enumValues().isEmpty() && !field.enumValues().contains(coerced.toString())) {
        warnings.add(
            "field '" + key + "' value '" + coerced + "' is not one of " + field.enumValues());
        continue;
      }
      accepted.put(key, coerced);
    }
    return new Result(accepted, warnings);
  }

  /**
   * Returns the value as the schema's type, or null if it cannot be represented as one.
   *
   * <p>YAML and JSON both hand us strings where a schema says integer, so a string that parses
   * cleanly is accepted; anything lossy is rejected rather than guessed at.
   */
  private static Object coerce(SpecField field, Object value) {
    return switch (field.type()) {
      case STRING -> value instanceof String s ? s : null;
      case BOOLEAN -> {
        if (value instanceof Boolean b) {
          yield b;
        }
        if (value instanceof String s) {
          String t = s.trim().toLowerCase(Locale.ROOT);
          yield t.equals("true") ? Boolean.TRUE : t.equals("false") ? Boolean.FALSE : null;
        }
        yield null;
      }
      case INTEGER -> {
        if (value instanceof Integer i) {
          yield i.longValue();
        }
        if (value instanceof Long l) {
          yield l;
        }
        if (value instanceof String s) {
          try {
            yield Long.valueOf(s.trim());
          } catch (NumberFormatException ex) {
            yield null;
          }
        }
        yield null;
      }
      case NUMBER -> {
        if (value instanceof Number n) {
          yield n.doubleValue();
        }
        if (value instanceof String s) {
          try {
            yield Double.valueOf(s.trim());
          } catch (NumberFormatException ex) {
            yield null;
          }
        }
        yield null;
      }
    };
  }
}
