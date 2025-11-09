package com.achen.shelf.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;

/** The one way a {@code jsonb} spec column crosses the JDBC boundary in either direction. */
public final class Jsonb {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private Jsonb() {}

  /** A JSON object as an unmodifiable map; null or blank is the empty map. */
  public static Map<String, Object> toMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return Collections.unmodifiableMap(JSON.readValue(json, MAP));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("a jsonb column should always hold valid JSON", e);
    }
  }

  /** A map as a JSON object. */
  public static String fromMap(Map<String, Object> map) {
    try {
      return JSON.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("a validated spec map should always serialize", e);
    }
  }
}
