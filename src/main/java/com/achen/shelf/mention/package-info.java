/**
 * Mention resolution (M9): which catalog products the community's words are about, and what the
 * words say about them. The second stage of the community track, over the rows {@code ingest/}
 * staged in {@code raw_mentions}; writes {@code mentions}, one row per (raw mention, product).
 *
 * <p>The matching itself is {@link com.achen.shelf.resolve.MentionMatcher}, a text front-end on the
 * listing scorer's core, and lives in {@code resolve/} beside it. This package is the pass ({@link
 * com.achen.shelf.mention.MentionRun}), the rule-based reading of the sentence around a match
 * ({@link com.achen.shelf.mention.SentimentRule}, {@link com.achen.shelf.mention.RankRule}), and
 * the two evals against the frozen label files. See docs/Architecture.md "Mention resolution".
 */
package com.achen.shelf.mention;
