/**
 * Per-retailer parsing: response body in, {@link com.achen.shelf.crawl.ParsedOffer} out.
 *
 * <p>Implementations are pure and golden-file tested against bodies saved from live retailers, so a
 * site changing its markup surfaces as a failing test rather than as missing rows.
 */
package com.achen.shelf.crawl.parse;
