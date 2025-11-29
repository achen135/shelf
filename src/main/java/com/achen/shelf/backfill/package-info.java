/**
 * The history backfill (M4): a labeled synthetic year of prices behind every offer, so that the
 * rollups, the deal signal and the backtest have depth from day one.
 *
 * <p>No public source of these retailers' price history exists in a usable form (docs/Design
 * Decisions.md, "The backfill is synthetic, and says so"), so every row this package writes carries
 * {@code source = 'synthetic'} from the moment it is written, and every number computed over it
 * carries the count. {@link com.achen.shelf.backfill.SyntheticSeries} is the generator — a pure,
 * seeded function, so the backfill is re-derivable — and {@link
 * com.achen.shelf.backfill.SyntheticBackfill} writes it.
 */
package com.achen.shelf.backfill;
