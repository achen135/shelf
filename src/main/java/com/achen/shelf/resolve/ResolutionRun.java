package com.achen.shelf.resolve;

import com.achen.shelf.config.CategoryConfig;
import com.achen.shelf.config.Retailer;
import com.achen.shelf.config.SeedProduct;
import com.achen.shelf.crawl.Normalizer;
import com.achen.shelf.db.Database;
import com.achen.shelf.db.ProductDao;
import com.achen.shelf.db.ResolutionDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One resolution pass over a category: every pending offer decided, every touched product's spec
 * re-derived.
 *
 * <p>Runs after a crawl cycle closes (the coordinator and {@code shelf crawl --once} both call it)
 * and on demand ({@code shelf resolve}). It is idempotent and safe to repeat: a pass only ever
 * reads pending offers, and a second pass over the same data makes the same decisions. Auto links
 * are never re-scored by a later pass — a link is a decision, not a cache — so a scorer change
 * takes effect on new listings, and on old ones only if an operator asks for a re-score.
 *
 * <p>Decisions are written in one transaction, so a reader never sees half a pass; a pass over the
 * whole M2 corpus (seven thousand offers, fifty products) takes well under a second.
 */
public final class ResolutionRun {

  private static final Logger log = LoggerFactory.getLogger(ResolutionRun.class);

  /** What a pass did. */
  public record Summary(
      String category,
      int catalogSize,
      int considered,
      int autoLinked,
      int queuedForReview,
      int unmatched,
      int productsWithSpecDerived,
      int reviewQueueSize) {}

  private final Database db;
  private final ProductDao products;
  private final ResolutionDao dao;
  private final Resolver.Thresholds thresholds;

  public ResolutionRun(Database db, Resolver.Thresholds thresholds) {
    this.db = db;
    this.products = new ProductDao(db);
    this.dao = new ResolutionDao(db);
    this.thresholds = thresholds;
  }

  /** The catalog for a category as it stands in the database, indexed for blocking. */
  public Catalog loadCatalog(CategoryConfig category) throws SQLException {
    List<Candidate> candidates = new ArrayList<>();
    for (ProductDao.Row p : products.list(category.name())) {
      candidates.add(
          new Candidate(
              p.id(), p.brandNorm(), p.modelNorm(), p.canonicalName(), p.spec(), p.aliases()));
    }
    return Catalog.of(candidates);
  }

  /** A resolver over the category's current catalog. */
  public Resolver resolver(CategoryConfig category) throws SQLException {
    return new Resolver(
        loadCatalog(category),
        new Scorer(
            category.resolution().identityFields(), category.resolution().nonProductPhrases()),
        thresholds);
  }

  /** The names of the retailers whose offers belong to this category. */
  public static List<String> retailerNames(CategoryConfig category) {
    return category.retailers().stream().map(Retailer::name).toList();
  }

  /** Runs one pass over the pending offers. */
  public Summary run(CategoryConfig category) throws SQLException {
    return run(category, false);
  }

  /**
   * Runs one pass; with {@code rescore}, first returns every auto link to pending so the pass
   * decides it afresh under the current scorer and config. Human decisions are never reopened. The
   * reset and the pass are one transaction, so no reader sees the catalog unlinked.
   */
  public Summary run(CategoryConfig category, boolean rescore) throws SQLException {
    Resolver resolver = resolver(category);
    List<String> retailers = retailerNames(category);

    return db.transaction(
        c -> {
          if (rescore) {
            int reset = dao.resetAutoLinks(c, retailers);
            log.info("resolution pass for {}: {} auto link(s) reopened", category.name(), reset);
          }
          List<ResolutionDao.PendingOffer> pending = dao.pending(c, retailers);
          int auto = 0;
          int review = 0;
          int none = 0;
          Set<Long> touched = new LinkedHashSet<>();
          for (ResolutionDao.PendingOffer offer : pending) {
            Resolver.Decision d = resolver.decide(toListing(offer));
            switch (d.outcome()) {
              case AUTO -> {
                Candidate c1 = d.best().orElseThrow().candidate();
                dao.link(c, offer.id(), c1.productId(), d.best().orElseThrow().score().value());
                touched.add(c1.productId());
                auto++;
              }
              case REVIEW -> {
                Resolver.Scored best = d.best().orElseThrow();
                dao.propose(c, offer.id(), best.candidate().productId(), best.score().value());
                review++;
              }
              case NONE -> {
                dao.propose(c, offer.id(), null, d.best().map(s -> s.score().value()).orElse(0.0));
                none++;
              }
            }
          }
          int derived = deriveSpecs(c, category, touched);
          Summary summary =
              new Summary(
                  category.name(),
                  resolver.catalog().size(),
                  pending.size(),
                  auto,
                  review,
                  none,
                  derived,
                  dao.reviewQueueSize(c, retailers));
          log.info(
              "resolution pass for {}: {} pending considered, {} auto-linked, {} for review,"
                  + " {} unmatched; specs derived for {} product(s); review queue now {}",
              summary.category(),
              summary.considered(),
              summary.autoLinked(),
              summary.queuedForReview(),
              summary.unmatched(),
              summary.productsWithSpecDerived(),
              summary.reviewQueueSize());
          return summary;
        });
  }

  /**
   * Re-derives the canonical spec of each given product from its linked offers.
   *
   * <p>The rule: per field, the most common value across the product's linked offers, then the
   * seed's own values laid over the top — a hand-written seed spec is authoritative, a derived one
   * is a summary. It is a summary because variants of one product legitimately disagree (a Q1 Pro
   * ships with linear or tactile switches); the per-variant truth stays on {@code offers.spec}, and
   * a spec filter that must not miss a variant should look there.
   *
   * @return how many products were updated
   */
  public int deriveSpecs(Connection c, CategoryConfig category, Collection<Long> productIds)
      throws SQLException {
    if (productIds.isEmpty()) {
      return 0;
    }
    Map<String, Map<String, Object>> seedSpecs = seedSpecsByIdentity(category);
    Map<Long, ProductDao.Row> rows = new HashMap<>();
    for (ProductDao.Row p : products.list(category.name())) {
      if (productIds.contains(p.id())) {
        rows.put(p.id(), p);
      }
    }
    Map<Long, Map<String, Object>> derived = dao.derivedSpecs(c, productIds);
    int updated = 0;
    for (Long id : productIds) {
      ProductDao.Row row = rows.get(id);
      if (row == null) {
        continue;
      }
      Map<String, Object> spec = new LinkedHashMap<>(derived.getOrDefault(id, Map.of()));
      spec.putAll(seedSpecs.getOrDefault(row.brandNorm() + "|" + row.modelNorm(), Map.of()));
      if (!spec.equals(row.spec())) {
        dao.updateSpec(c, id, spec);
        updated++;
      }
    }
    return updated;
  }

  private static Map<String, Map<String, Object>> seedSpecsByIdentity(CategoryConfig category) {
    Map<String, Map<String, Object>> out = new HashMap<>();
    for (SeedProduct seed : category.seedProducts()) {
      out.put(
          Normalizer.normalize(seed.brand()) + "|" + Normalizer.normalize(seed.model()),
          seed.spec());
    }
    return out;
  }

  /** The resolver's view of a stored offer. */
  public static Listing toListing(ResolutionDao.PendingOffer offer) {
    return new Listing(
        offer.id(), offer.brandNorm(), Normalizer.normalize(offer.title()), offer.spec());
  }
}
