/**
 * Price rollups (M4): after a crawl cycle closes, retire the offers that cycle proved gone, then
 * recompute the trailing-window statistics of every offer and product the cycle touched.
 *
 * <p>The SQL lives in {@code db/RollupDao}; this package decides <em>what</em> to recompute and
 * <em>when</em>: {@link com.achen.shelf.rollup.RollupRun} is the post-run hook next to entity
 * resolution, and {@code shelf rollup} runs it by hand over a whole category.
 */
package com.achen.shelf.rollup;
