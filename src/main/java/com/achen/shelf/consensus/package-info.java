/**
 * The community consensus (M10): what the configured communities said about each product over a
 * trailing window, aggregated from {@code mentions} into one {@code consensus_scores} row per
 * product — a score that always carries its mention count — by a rule fixed before it ran.
 * Recomputed as the last step of every mention pass, the way the signal pass follows the rollup
 * pass. See docs/Architecture.md "Consensus".
 */
package com.achen.shelf.consensus;
