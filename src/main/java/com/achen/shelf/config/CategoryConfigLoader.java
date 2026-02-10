package com.achen.shelf.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads and validates {@code categories/<name>.yaml}.
 *
 * <p>Validation is deliberately strict and happens once, at load: an unknown key, a bad enum, a
 * missing retailer path or an out-of-range rate limit stops the process with a message naming the
 * file and the offending path. The alternative — a half-valid config that fails somewhere inside a
 * crawl — is much harder to debug, and a category file is the one thing a person hand-edits.
 */
public final class CategoryConfigLoader {

  /** Politeness ceiling. A category file cannot configure a faster crawl than this. */
  public static final double MAX_ALLOWED_RPS = 2.0;

  private final ObjectMapper mapper;

  public CategoryConfigLoader() {
    this.mapper =
        new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
  }

  /** Loads {@code <dir>/<category>.yaml}. */
  public CategoryConfig load(Path categoriesDir, String category) {
    Path file = categoriesDir.resolve(category + ".yaml");
    if (!Files.isRegularFile(file)) {
      throw new ConfigException(
          "no config for category '" + category + "': expected a file at " + file.toAbsolutePath());
    }
    return load(file);
  }

  /** Loads every {@code *.yaml} in a directory, by category name — what the API serves. */
  public List<CategoryConfig> loadAll(Path categoriesDir) {
    if (!Files.isDirectory(categoriesDir)) {
      throw new ConfigException(
          "categories directory not found: " + categoriesDir.toAbsolutePath());
    }
    List<Path> files;
    try (var stream = Files.list(categoriesDir)) {
      files = stream.filter(f -> f.getFileName().toString().endsWith(".yaml")).sorted().toList();
    } catch (IOException e) {
      throw new ConfigException(
          "cannot list " + categoriesDir.toAbsolutePath() + ": " + e.getMessage());
    }
    List<CategoryConfig> out = new ArrayList<>();
    for (Path f : files) {
      out.add(load(f));
    }
    return List.copyOf(out);
  }

  /** Loads a specific category file. */
  public CategoryConfig load(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      return validate(read(in, file.toString()), file.toString());
    } catch (IOException e) {
      throw new ConfigException(
          "could not read category config " + file + ": " + e.getMessage(), e);
    }
  }

  /** Loads from an already-open stream; {@code source} is used only in error messages. */
  public CategoryConfig load(InputStream in, String source) {
    return validate(read(in, source), source);
  }

  private CategoryConfig read(InputStream in, String source) {
    try {
      CategoryConfig parsed = mapper.readValue(in, CategoryConfig.class);
      if (parsed == null) {
        throw new ConfigException(source + " is empty");
      }
      return parsed;
    } catch (JsonMappingException e) {
      // Jackson's own message already carries the YAML line/column; keep it, prefix the file.
      throw new ConfigException(
          source + " is not a valid category config: " + e.getOriginalMessage(), e);
    } catch (IOException e) {
      throw new ConfigException(source + " could not be parsed as YAML: " + e.getMessage(), e);
    }
  }

  private CategoryConfig validate(CategoryConfig cfg, String source) {
    List<String> errors = new ArrayList<>();

    if (isBlank(cfg.name())) {
      errors.add("`name` is required");
    }
    validateSpecSchema(cfg, errors);
    validateRetailers(cfg, errors);
    validateSeeds(cfg, errors);
    validateResolution(cfg, errors);
    validateCommunities(cfg, errors);
    validateSentiment(cfg, errors);

    if (!errors.isEmpty()) {
      StringBuilder sb = new StringBuilder(source + " is not a valid category config:");
      for (String e : errors) {
        sb.append("\n  - ").append(e);
      }
      throw new ConfigException(sb.toString());
    }
    return cfg;
  }

  private void validateSpecSchema(CategoryConfig cfg, List<String> errors) {
    if (cfg.specSchema().isEmpty()) {
      errors.add("`spec_schema` must declare at least one field");
    }
    for (Map.Entry<String, SpecField> e : cfg.specSchema().entrySet()) {
      String path = "spec_schema." + e.getKey();
      SpecField f = e.getValue();
      if (f == null || f.type() == null) {
        errors.add(path + ": `type` is required");
        continue;
      }
      if (!f.enumValues().isEmpty() && f.type() != SpecType.STRING) {
        errors.add(path + ": `enum` is only supported for string fields, not " + lower(f.type()));
      }
      if (f.enumValues().stream().anyMatch(CategoryConfigLoader::isBlank)) {
        errors.add(path + ": `enum` contains a blank value");
      }
    }
  }

  private void validateRetailers(CategoryConfig cfg, List<String> errors) {
    if (cfg.retailers().isEmpty()) {
      errors.add("`retailers` must declare at least one retailer");
    }
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < cfg.retailers().size(); i++) {
      Retailer r = cfg.retailers().get(i);
      String path = "retailers[" + i + "]";
      if (isBlank(r.name())) {
        errors.add(path + ": `name` is required");
      } else {
        path = "retailers." + r.name();
        if (!seen.add(r.name())) {
          errors.add(path + ": duplicate retailer name");
        }
      }
      validateBaseUrl(r, path, errors);
      if (isBlank(r.parser())) {
        errors.add(path + ": `parser` is required");
      }
      if (r.brandSource() == BrandSource.FIXED && isBlank(r.brand())) {
        errors.add(path + ": `brand` is required when brand_source is `fixed`");
      }
      validateFetch(r, path, errors);
    }
  }

  private void validateBaseUrl(Retailer r, String path, List<String> errors) {
    if (isBlank(r.baseUrl())) {
      errors.add(path + ": `base_url` is required");
      return;
    }
    try {
      URI uri = new URI(r.baseUrl());
      String scheme = uri.getScheme();
      if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
        errors.add(
            path + ": `base_url` must be an absolute http(s) URL, got '" + r.baseUrl() + "'");
      } else if (uri.getHost() == null) {
        errors.add(path + ": `base_url` has no host: '" + r.baseUrl() + "'");
      } else if (r.baseUrl().endsWith("/")) {
        errors.add(
            path + ": `base_url` must not end with '/' (list_paths supply the leading slash)");
      }
    } catch (URISyntaxException e) {
      errors.add(path + ": `base_url` is not a valid URL: " + e.getMessage());
    }
  }

  private void validateFetch(Retailer r, String path, List<String> errors) {
    FetchSpec f = r.fetch();
    if (f == null) {
      errors.add(path + ": `fetch` is required");
      return;
    }
    if (f.mode() == null) {
      errors.add(path + ".fetch: `mode` is required (`api` or `html`)");
    }
    if (f.listPaths().isEmpty()) {
      errors.add(path + ".fetch: `list_paths` must declare at least one path");
    }
    for (String p : f.listPaths()) {
      if (isBlank(p) || !p.startsWith("/")) {
        errors.add(path + ".fetch.list_paths: '" + p + "' must be a path starting with '/'");
      }
    }
    if (f.maxRps() <= 0 || f.maxRps() > MAX_ALLOWED_RPS) {
      errors.add(
          path
              + ".fetch: `max_rps` must be > 0 and <= "
              + MAX_ALLOWED_RPS
              + " (got "
              + f.maxRps()
              + ")");
    }
    if (f.pageSize() <= 0) {
      errors.add(path + ".fetch: `page_size` must be > 0 (got " + f.pageSize() + ")");
    }
    if (f.maxPages() <= 0) {
      errors.add(path + ".fetch: `max_pages` must be > 0 (got " + f.maxPages() + ")");
    }
    if (!f.respectRobots()) {
      // Not a toggle. Honouring robots.txt is the project's posture (docs/Design Decisions.md,
      // "API-first, polite crawler"), so a config that tries to switch it off is an error, not
      // an option the crawler quietly accepts.
      errors.add(
          path + ".fetch: `respect_robots: false` is not allowed — robots.txt is always honoured");
    }
    if (f.auth().mode() != AuthSpec.Mode.NONE && f.auth().envVars().isEmpty()) {
      errors.add(
          path + ".fetch.auth: `env_vars` must name the environment variables holding credentials");
    }
  }

  private void validateSeeds(CategoryConfig cfg, List<String> errors) {
    if (cfg.seedProducts().isEmpty()) {
      errors.add("`seed_products` must declare at least one product");
    }
    SpecValidator specValidator = new SpecValidator(cfg.specSchema());
    for (int i = 0; i < cfg.seedProducts().size(); i++) {
      SeedProduct s = cfg.seedProducts().get(i);
      String path = "seed_products[" + i + "]";
      if (isBlank(s.brand())) {
        errors.add(path + ": `brand` is required");
      }
      if (isBlank(s.model())) {
        errors.add(path + ": `model` is required");
      }
      if (isBlank(s.canonicalName())) {
        errors.add(path + ": `canonical_name` is required");
      }
      // Seeds are hand-written, so a spec typo here is a config bug: reject it rather than
      // dropping the field the way the crawler does for retailer-supplied values.
      for (String problem : specValidator.validate(s.spec()).warnings()) {
        errors.add(path + ".spec: " + problem);
      }
      Set<String> aliasesSeen = new HashSet<>();
      for (String alias : s.aliases()) {
        if (isBlank(alias)) {
          errors.add(path + ".aliases: contains a blank alias");
        } else if (!aliasesSeen.add(alias.strip().toLowerCase(Locale.ROOT))) {
          errors.add(path + ".aliases: '" + alias + "' is listed twice");
        }
      }
    }
  }

  private void validateResolution(CategoryConfig cfg, List<String> errors) {
    for (String field : cfg.resolution().identityFields()) {
      if (!cfg.specSchema().containsKey(field)) {
        errors.add("resolution.identity_fields: '" + field + "' is not a field in `spec_schema`");
      }
    }
    if (cfg.resolution().nonProductPhrases().stream().anyMatch(CategoryConfigLoader::isBlank)) {
      errors.add("resolution.non_product_phrases: contains a blank phrase");
    }
  }

  private void validateCommunities(CategoryConfig cfg, List<String> errors) {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < cfg.communities().size(); i++) {
      Community c = cfg.communities().get(i);
      String path = "communities[" + i + "]";
      if (isBlank(c.name())) {
        errors.add(path + ": `name` is required");
      } else {
        path = "communities." + c.name();
        if (!seen.add(c.name())) {
          errors.add(path + ": duplicate community name");
        }
      }
      if (c.source() == null) {
        errors.add(path + ": `source` is required (`reddit` or `youtube`)");
      }
      if (isBlank(c.id())) {
        errors.add(path + ": `id` is required (a subreddit name, a channel id or an @handle)");
      } else if (c.source() == CommunitySource.REDDIT && c.id().startsWith("r/")) {
        errors.add(path + ": `id` is the bare subreddit name, without `r/` (got '" + c.id() + "')");
      }
      if (!(c.weight() > 0) || c.weight() > 10) {
        errors.add(path + ": `weight` must be > 0 and <= 10 (got " + c.weight() + ")");
      }
      validateIngest(c, path, errors);
    }
  }

  private void validateIngest(Community c, String path, List<String> errors) {
    IngestSpec s = c.ingest();
    if (s == null) {
      errors.add(path + ": `ingest` is required");
      return;
    }
    if (s.windowDays() <= 0) {
      errors.add(path + ".ingest: `window_days` must be > 0 (got " + s.windowDays() + ")");
    }
    if (s.maxItems() <= 0) {
      errors.add(path + ".ingest: `max_items` must be > 0 (got " + s.maxItems() + ")");
    }
    if (s.commentsPerItem() < 0) {
      errors.add(
          path + ".ingest: `comments_per_item` must be >= 0 (got " + s.commentsPerItem() + ")");
    }
    if (s.maxRps() <= 0 || s.maxRps() > MAX_ALLOWED_RPS) {
      errors.add(
          path
              + ".ingest: `max_rps` must be > 0 and <= "
              + MAX_ALLOWED_RPS
              + " (got "
              + s.maxRps()
              + ")");
    }
    // The ingesters read credentials by position, so the count is part of the contract: Reddit's
    // application-only OAuth takes a client id and a secret, YouTube's Data API one key.
    int want = c.source() == CommunitySource.REDDIT ? 2 : 1;
    if (c.source() != null && s.auth().envVars().size() != want) {
      errors.add(
          path
              + ".ingest.auth: `env_vars` must name exactly "
              + (want == 2
                  ? "two variables (client id, client secret)"
                  : "one variable (the API key)")
              + " for a "
              + lower(c.source())
              + " community (got "
              + s.auth().envVars().size()
              + ")");
    }
  }

  private void validateSentiment(CategoryConfig cfg, List<String> errors) {
    if (cfg.sentiment().positive().stream().anyMatch(CategoryConfigLoader::isBlank)) {
      errors.add("sentiment.positive: contains a blank phrase");
    }
    if (cfg.sentiment().negative().stream().anyMatch(CategoryConfigLoader::isBlank)) {
      errors.add("sentiment.negative: contains a blank phrase");
    }
    Set<String> both = new HashSet<>(cfg.sentiment().positive());
    both.retainAll(cfg.sentiment().negative());
    if (!both.isEmpty()) {
      errors.add("sentiment: " + both + " listed as both positive and negative");
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static String lower(Enum<?> e) {
    return e.name().toLowerCase(Locale.ROOT);
  }
}
