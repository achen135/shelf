/**
 * Entity resolution: which catalog product a retailer listing is (M3).
 *
 * <p>The crawl records what a listing says — brand, title, spec. This package decides what it
 * <em>is</em>, after a cycle closes: block the pending offers by normalized brand, score each
 * against every product in its block ({@link com.achen.shelf.resolve.Scorer}), link the best
 * candidate above the auto threshold, hold the ones in between for a human ({@code shelf review}),
 * and derive each linked product's canonical spec from its offers. {@code shelf eval resolution}
 * measures the whole thing against hand-labeled pairs.
 *
 * <p>Nothing here is category-specific: the blocking key and the tokens the scorer compares come
 * from the category file's seeds and the crawl's normalized brand, both of which M7's second
 * category supplies by configuration.
 */
package com.achen.shelf.resolve;
