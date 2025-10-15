package com.achen.shelf.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Locale;

/**
 * How a retailer's endpoint is authenticated.
 *
 * <p>Credentials themselves never appear in config — only the names of the environment variables
 * that carry them. A retailer whose env vars are unset is skipped at crawl time with a warning
 * rather than failing the run, so a clone without API keys still crawls the open endpoints.
 *
 * @param mode none, api_key, or oauth2_client_credentials
 * @param envVars names of the environment variables holding the credentials
 */
public record AuthSpec(Mode mode, List<String> envVars) {

  /** Supported authentication shapes. */
  public enum Mode {
    NONE,
    API_KEY,
    OAUTH2_CLIENT_CREDENTIALS;

    @JsonCreator
    public static Mode fromYaml(String raw) {
      if (raw == null) {
        return NONE;
      }
      try {
        return valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        // See FetchMode.fromYaml: no cause, so this message survives Jackson's rewrapping.
        throw new ConfigException(
            "unknown auth mode '"
                + raw
                + "' (expected `none`, `api_key` or `oauth2_client_credentials`)");
      }
    }
  }

  public AuthSpec {
    mode = mode == null ? Mode.NONE : mode;
    envVars = envVars == null ? List.of() : List.copyOf(envVars);
  }

  /** The default when a retailer declares no `auth:` block at all. */
  public static AuthSpec none() {
    return new AuthSpec(Mode.NONE, List.of());
  }

  @JsonCreator
  static AuthSpec fromYaml(
      @JsonProperty("mode") Mode mode, @JsonProperty("env_vars") List<String> envVars) {
    return new AuthSpec(mode, envVars);
  }
}
