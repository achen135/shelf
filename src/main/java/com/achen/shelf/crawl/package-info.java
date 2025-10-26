/**
 * Fetching, politeness, parsing and the per-page pipeline. {@link
 * com.achen.shelf.crawl.PageCrawler} is the unit of work; {@link com.achen.shelf.crawl.CrawlRunner}
 * drives it on one thread and the {@code cluster} subpackage drives it from a queue. See
 * docs/Architecture.md.
 */
package com.achen.shelf.crawl;
