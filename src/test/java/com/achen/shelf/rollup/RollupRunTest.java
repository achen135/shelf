package com.achen.shelf.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.CategoryConfigLoader;
import com.achen.shelf.db.ObservationDao.Source;
import com.achen.shelf.db.RollupDao;
import com.achen.shelf.db.RollupDao.Rollup;
import com.achen.shelf.testing.FixtureServer;
import com.achen.shelf.testing.PostgresTestBase;
import com.achen.shelf.testing.PriceHistory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pass: what a run-scoped pass recomputes, that it agrees with a full pass, and that retirement
 * happens inside it and reaches the product.
 */
class RollupRunTest extends PostgresTestBase {

  private static final Instant AS_OF = Instant.parse("2030-06-01T00:00:00Z");

  private PriceHistory history;
  private CategoryConfig category;
  private RollupRun run;

  private static Instant at(int daysAgo) {
    return AS_OF.minus(daysAgo, ChronoUnit.DAYS);
  }

  @BeforeEach
  void setUp() {
    history = new PriceHistory(DB);
    // The fixture category's first retailer is renamed so its offers are PriceHistory's.
    String yaml =
        FixtureServer.Fixtures.read("categories/fixture-server.yaml.template")
            .replace("__PORT__", "1")
            .replace("name: json_store", "name: " + PriceHistory.RETAILER);
    category =
        new CategoryConfigLoader()
            .load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "keyboards");
    run = new RollupRun(DB, RollupRun.Settings.defaults(), Clock.fixed(AS_OF, ZoneOffset.UTC));
  }

  private Optional<Rollup> offerRollup(long id) throws SQLException {
    try (Connection c = DB.connection()) {
      return RollupDao.offerRollup(c, id);
    }
  }

  private Optional<Rollup> productRollup(long id) throws SQLException {
    try (Connection c = DB.connection()) {
      return RollupDao.productRollup(c, id);
    }
  }

  @Test
  void aRunScopedPassRecomputesWhatTheRunTouchedAndAgreesWithAFullPass() throws SQLException {
    long product = history.product("Keychron", "Q2");
    long x = history.offer();
    long y = history.offer();
    history.link(x, product, "auto");
    history.link(y, product, "auto");
    long earlier = history.finishedRun(at(10));
    history.observe(x, at(10), 10000, true, Source.OBSERVED, earlier);
    history.observe(y, at(10), 9000, true, Source.OBSERVED, earlier);
    long latest = history.finishedRun(at(0));
    history.observe(x, at(0), 9500, true, Source.OBSERVED, latest);

    RollupRun.Summary scoped = run.run(category, new RollupRun.Scope.Run(latest));

    assertThat(scoped.retired()).isZero();
    assertThat(scoped.offersRecomputed()).isEqualTo(1);
    assertThat(scoped.productsRecomputed()).isEqualTo(1);
    assertThat(scoped.asOf()).isEqualTo(AS_OF);
    Rollup xScoped = offerRollup(x).orElseThrow();
    Rollup pScoped = productRollup(product).orElseThrow();
    assertThat(offerRollup(y)).isEmpty();
    // y's $90 is ten days old but still its latest, and y is live: the product's price is $90
    assertThat(pScoped.currentPriceCents()).isEqualTo(9000);
    assertThat(pScoped.currentOfferId()).isEqualTo(y);

    RollupRun.Summary full = run.run(category, new RollupRun.Scope.All());

    assertThat(full.offersRecomputed()).isEqualTo(2);
    assertThat(full.productsRecomputed()).isEqualTo(1);
    assertThat(offerRollup(y)).isPresent();
    assertThat(offerRollup(x).orElseThrow())
        .usingRecursiveComparison()
        .ignoringFields("computedAt")
        .isEqualTo(xScoped);
    assertThat(productRollup(product).orElseThrow())
        .usingRecursiveComparison()
        .ignoringFields("computedAt")
        .isEqualTo(pScoped);
  }

  @Test
  void retirementHappensInsideThePassAndReachesTheProduct() throws SQLException {
    long product = history.product("Keychron", "Q2");
    long stillListed = history.offer();
    long delisted = history.offer();
    history.link(stillListed, product, "auto");
    history.link(delisted, product, "auto");
    long unlinked = history.offer();
    // Four finished cycles; the delisted offer was last seen before the third most recent.
    history.observe(stillListed, at(30), 10000, true, Source.OBSERVED, history.finishedRun(at(30)));
    history.observe(delisted, at(30), 5000, true, Source.OBSERVED, history.finishedRun(at(30)));
    history.observe(stillListed, at(20), 10000, true, Source.OBSERVED, history.finishedRun(at(20)));
    history.observe(stillListed, at(10), 10000, true, Source.OBSERVED, history.finishedRun(at(10)));
    long latest = history.finishedRun(at(0));
    history.observe(unlinked, at(0), 4000, true, Source.OBSERVED, latest);
    history.lastSeen(delisted, at(30));
    history.lastSeen(stillListed, at(10));
    history.lastSeen(unlinked, at(0));

    RollupRun.Summary summary = run.run(category, new RollupRun.Scope.Run(latest));

    assertThat(summary.retired()).isEqualTo(1);
    // the run observed one offer; the retired one is recomputed too, and so is their product
    assertThat(summary.offersRecomputed()).isEqualTo(2);
    assertThat(summary.productsRecomputed()).isEqualTo(1);
    assertThat(count("select count(*) from offers where retired_at is not null")).isEqualTo(1);
    Rollup p = productRollup(product).orElseThrow();
    assertThat(p.currentPriceCents()).isEqualTo(10000);
    assertThat(p.currentOfferId()).isEqualTo(stillListed);
    assertThat(p.d365().min()).isEqualTo(10000);
    assertThat(offerRollup(delisted)).isPresent();
  }

  @Test
  void settingsNeedAtLeastOneCycle() {
    assertThatThrownBy(() -> new RollupRun.Settings(0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
