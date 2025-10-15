package com.achen.shelf.crawl;

import java.util.List;

/**
 * What one crawl run did, per retailer and in total.
 *
 * <p>Returned rather than only logged so the CLI can print it and the integration test can assert
 * on it — the same numbers that go in the Sessions entry.
 */
public record CrawlSummary(
    long runId, String category, List<RetailerSummary> retailers, int seededProducts) {

  public CrawlSummary {
    retailers = List.copyOf(retailers);
  }

  /**
   * One retailer's contribution to a run.
   *
   * @param retailer config name
   * @param mode which fetch mode it used, for the api-vs-html record
   * @param pages responses successfully fetched and parsed
   * @param offersSeen offers the parsers produced
   * @param offersWritten offer rows upserted
   * @param observationsWritten new price_observations rows
   * @param matchedToSeed offers linked to a seeded product
   * @param errors fetches that failed after retries, plus parse failures
   * @param skippedByRobots URLs robots.txt told us not to fetch
   */
  public record RetailerSummary(
      String retailer,
      String mode,
      int pages,
      int offersSeen,
      int offersWritten,
      int observationsWritten,
      int matchedToSeed,
      int errors,
      int skippedByRobots) {}

  public int totalPages() {
    return retailers.stream().mapToInt(RetailerSummary::pages).sum();
  }

  public int totalErrors() {
    return retailers.stream().mapToInt(RetailerSummary::errors).sum();
  }

  public int totalOffersWritten() {
    return retailers.stream().mapToInt(RetailerSummary::offersWritten).sum();
  }

  public int totalObservations() {
    return retailers.stream().mapToInt(RetailerSummary::observationsWritten).sum();
  }

  public int totalMatched() {
    return retailers.stream().mapToInt(RetailerSummary::matchedToSeed).sum();
  }
}
