/**
 * The distributed layer over the crawl (M2): a leader-elected {@link
 * com.achen.shelf.crawl.cluster.Coordinator} that enqueues cycles and reaps leases, and a {@link
 * com.achen.shelf.crawl.cluster.Worker} pool that claims pages from {@code crawl_tasks} and runs
 * {@link com.achen.shelf.crawl.PageCrawler} on each. Postgres is the queue, the lock and the shared
 * politeness bucket; there is no other coordination service.
 */
package com.achen.shelf.crawl.cluster;
