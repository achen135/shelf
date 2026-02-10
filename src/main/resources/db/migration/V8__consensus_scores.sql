-- V8__consensus_scores.sql — the community consensus per product (M10).
-- See docs/Spec v2.md §4–§5 and docs/Architecture.md "Consensus".

-- ---------------------------------------------------------------------------
-- consensus_scores — one row per product: what the configured communities said about it over
-- the trailing window, as a number that always travels with its count.
--
-- Computed by consensus/ConsensusRule over the product's linked mentions (match_status = 'auto')
-- whose raw mention was posted inside the window, each weighted by its community's configured
-- weight: score = Σ w·s / Σ w with s = +1 / 0 / −1 for a positive / neutral / negative reading.
-- The counts are the confidence — a score from three mentions and one from three hundred must
-- never read the same — and the check ties score to count so a row can not say "0.8" about
-- nothing. quote_mention_ids are the rows the page quotes (the newest positive and the newest
-- negative, else the newest), chosen at compute time so the quotes and the score agree.
--
-- Refreshed as the last step of every mention pass (and by `shelf consensus`), the way the
-- signal pass follows the rollup pass: recomputed for every product in the category, never
-- accumulated. It sits beside deal_signals and is never blended into it (Spec v2 §3).
-- ---------------------------------------------------------------------------

create table consensus_scores (
    product_id        bigint primary key references products(id),
    score             double precision,                 -- in [-1, 1]; null when nothing was said
    mention_count     integer not null,
    positive_count    integer not null,
    negative_count    integer not null,
    neutral_count     integer not null,
    positive_share    double precision,                 -- positive_count / mention_count
    source_diversity  integer not null,                 -- distinct communities behind the count
    window_days       integer not null,
    quote_mention_ids bigint[] not null default '{}',
    as_of             timestamptz not null,
    computed_at       timestamptz not null default now(),
    check ((mention_count = 0) = (score is null)),
    check (positive_count + negative_count + neutral_count = mention_count)
);
