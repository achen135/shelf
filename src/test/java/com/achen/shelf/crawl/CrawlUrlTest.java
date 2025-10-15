package com.achen.shelf.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.achen.shelf.config.AuthSpec;
import com.achen.shelf.config.BrandSource;
import com.achen.shelf.config.FetchMode;
import com.achen.shelf.config.FetchSpec;
import com.achen.shelf.config.Retailer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** URL building from configured list paths. */
class CrawlUrlTest {

  private static Retailer retailer(String path, int pageSize) {
    return new Retailer(
        "shop",
        "https://shop.test",
        true,
        BrandSource.VENDOR,
        null,
        new FetchSpec(FetchMode.API, List.of(path), pageSize, 2, 0.2, true, AuthSpec.none()),
        "shopify_products_json",
        null);
  }

  @Test
  void substitutesPageAndLimit() {
    Retailer r = retailer("/collections/x/products.json?limit={limit}&page={page}", 250);

    assertThat(CrawlRunner.buildUrl(r, r.fetch().listPaths().get(0), 2))
        .isEqualTo("https://shop.test/collections/x/products.json?limit=250&page=2");
  }

  @Test
  void leavesPathsWithNoPlaceholdersAlone() {
    Retailer r = retailer("/collections/x", 250);

    assertThat(CrawlRunner.buildUrl(r, r.fetch().listPaths().get(0), 1))
        .isEqualTo("https://shop.test/collections/x");
  }

  @Test
  void refusesToFetchAnUnfinishedListPath() {
    // The disabled Best Buy / eBay entries carry deliberately unfilled placeholders. Enabling
    // one without finishing its config must fail loudly, not request a URL with a brace in it.
    Retailer r = retailer("/v1/products(categoryPath.id={category_path_id})?page={page}", 100);

    assertThatThrownBy(() -> CrawlRunner.buildUrl(r, r.fetch().listPaths().get(0), 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unresolved placeholder")
        .hasMessageContaining("shop");
  }
}
