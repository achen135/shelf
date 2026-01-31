/**
 * Community ingestion (M8): reading what a category's configured subreddits and YouTube channels
 * say, verbatim, into {@code raw_mentions} — and nothing more. No product matching, no sentiment;
 * that is M9's, on the far side of a deliberate seam.
 *
 * <p>A sibling of {@code crawl/}, not a part of it, because the fetch shape differs: authenticated
 * calls to two platform APIs from one process, paced per credential, rather than anonymous
 * robots.txt-governed page fetches spread over a worker pool. It borrows {@code crawl/}'s pieces
 * where they fit — {@link com.achen.shelf.crawl.Fetcher} for HTTP with retries, {@link
 * com.achen.shelf.crawl.DomainRateLimiter} for pacing — and mirrors its structure: an {@link
 * com.achen.shelf.ingest.Ingester} plays {@code PageCrawler}'s role (fetch a page, hand over what
 * it held), {@link com.achen.shelf.ingest.IngestRunner} plays {@code CrawlRunner}'s (loop over the
 * communities, write, summarize). See docs/Architecture.md "Ingestion".
 */
package com.achen.shelf.ingest;
