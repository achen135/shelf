package com.achen.shelf.ingest;

import com.achen.shelf.config.AuthSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Turns a community's {@link AuthSpec} — environment variable <em>names</em> — into values.
 *
 * <p>Empty when any named variable is unset, and the caller skips the community with a warning
 * rather than failing the run: that is the contract {@link AuthSpec} states, and it is what lets a
 * clone with a YouTube key but no Reddit approval ingest what it can. Values never reach a log line
 * or an exception message; only the names do.
 */
final class Credentials {

  private Credentials() {}

  /** Every variable's value by name, or empty naming the first one that is missing. */
  static Optional<Map<String, String>> resolve(AuthSpec auth, Function<String, String> env) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String name : auth.envVars()) {
      String value = env.apply(name);
      if (value == null || value.isBlank()) {
        return Optional.empty();
      }
      out.put(name, value);
    }
    return Optional.of(Map.copyOf(out));
  }

  /** The names that are unset, for the warning. */
  static List<String> missing(AuthSpec auth, Function<String, String> env) {
    return auth.envVars().stream()
        .filter(
            name -> {
              String v = env.apply(name);
              return v == null || v.isBlank();
            })
        .toList();
  }
}
