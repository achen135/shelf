package com.achen.shelf.testing;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The end-to-end test category ({@code fixtures/categories/fixture-server.yaml.template}) pointed
 * at a running {@link FixtureServer}, written to a directory so that the real config loader — and a
 * real {@code shelf} subprocess reading {@code SHELF_CATEGORIES_DIR} — can load it.
 *
 * <p>What it describes, for the assertions that depend on it: {@code json_store} (two Shopify JSON
 * pages, 3 products × 2 variants on the first, empty second, max_pages 3), {@code html_store} (one
 * page of 4 cards), {@code forbidden_store} (its one path is disallowed by robots.txt), and a
 * disabled store that must never be fetched. Four seeds, of which three match listings.
 */
public final class FixtureCategory {

  private FixtureCategory() {}

  /** Writes {@code keyboards.yaml} for the server into {@code dir} and returns the directory. */
  public static Path write(Path dir, FixtureServer server) {
    String baseUrl = server.baseUrl();
    String port = baseUrl.substring(baseUrl.lastIndexOf(':') + 1);
    String yaml =
        FixtureServer.Fixtures.read("categories/fixture-server.yaml.template")
            .replace("__PORT__", port);
    try {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("keyboards.yaml"), yaml, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write the fixture category", e);
    }
    return dir;
  }

  /** Writes and loads it. */
  public static CategoryConfig load(Path dir, FixtureServer server) {
    return new CategoryConfigLoader().load(write(dir, server), "keyboards");
  }

  /**
   * Registers the standard fixture responses on a server: robots, two JSON pages, one HTML page.
   */
  public static FixtureServer serveStandardFixtures(FixtureServer server) {
    server.serveFixture("/robots.txt", "robots/shopify-style.txt", "text/plain");
    server.serveFixture(
        "/collections/keyboards/products.json?limit=250&page=1",
        "shopify/keychron-products.json",
        "application/json");
    server.serveFixture(
        "/collections/keyboards/products.json?limit=250&page=2",
        "shopify/empty-products.json",
        "application/json");
    server.serveFixture(
        "/collections/html-keyboards", "html/mechanicalkeyboards-collection.html", "text/html");
    return server;
  }

  /** The path of the JSON store's first page, as the server sees it. */
  public static final String JSON_PAGE_1 = "/collections/keyboards/products.json?limit=250&page=1";

  /** The JSON store's list path as configured (with placeholders). */
  public static final String JSON_LIST_PATH =
      "/collections/keyboards/products.json?limit={limit}&page={page}";

  /** The HTML store's list path. */
  public static final String HTML_LIST_PATH = "/collections/html-keyboards";

  /** The forbidden store's list path. */
  public static final String FORBIDDEN_LIST_PATH =
      "/collections/keyboards/products.json?sort_by=price&page={page}";

  /** Offers the standard fixtures yield: 6 JSON variants + 4 HTML cards. */
  public static final int EXPECTED_OFFERS = 10;
}
