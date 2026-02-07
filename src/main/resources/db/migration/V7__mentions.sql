-- V7__mentions.sql — mention resolution (M9): what a community's words are about.
-- See docs/Spec v2.md §3–§4 and docs/Architecture.md "Mention resolution".

-- ---------------------------------------------------------------------------
-- products.aliases — the other names a product is allowed to be recognized by.
--
-- Free text says "gmmk pro" for the Glorious GMMK Pro and "hack 70" for the HACK70; the
-- matcher reads the model and every alias as equals. Declared per seed in the category file
-- (seed_products[].aliases) and copied here on every bootstrap, so a config edit is the whole
-- change — the rule Spec v2 §3 sets: a product is found by its name and its declared aliases,
-- never by per-product code.
-- ---------------------------------------------------------------------------

alter table products add column aliases text[] not null default '{}';

-- ---------------------------------------------------------------------------
-- mentions — one row per (raw mention, product) the matcher found, with the score, the span
-- it matched, why, and what the text says about it.
--
-- A raw mention can name several products ("Q1 Pro vs GMMK Pro") or none, so the unit here is
-- the pair, not the raw row: unique (raw_mention_id, product_id), and a text that names
-- nothing in the catalog has no row at all. match_status carries the same vocabulary as
-- offers.resolution_status — pending = proposed for review, auto = linked, reviewed /
-- rejected = a human's call — with the same rule that only a human may set the last two.
-- Unlike an offer link, a mention decision is derived evidence: every pass deletes the
-- machine's rows for the category and decides afresh, keeping the human's, so a matcher or
-- config change takes effect everywhere at once and a repeat pass changes nothing.
--
-- sentiment / sentiment_score / explicit_rank are what the extraction read in the sentence
-- around the span; extraction_method says how — 'rule' for M9's lexicon and patterns — the
-- way price_observations.source says which rows are synthetic. match_reasons is the scorer's
-- sentence, because a score without its reasons is not a decision anyone can review.
-- ---------------------------------------------------------------------------

create table mentions (
    id                bigint generated always as identity primary key,
    raw_mention_id    bigint not null references raw_mentions(id) on delete cascade,
    product_id        bigint not null references products(id),
    match_score       double precision not null,
    match_status      text not null
                      check (match_status in ('pending', 'auto', 'reviewed', 'rejected')),
    match_reasons     text[] not null default '{}',
    matched_text      text not null,                    -- the span, as normalized tokens
    sentiment         text check (sentiment in ('positive', 'negative', 'neutral')),
    sentiment_score   double precision,
    explicit_rank     integer,
    extraction_method text not null check (extraction_method in ('rule', 'local_model', 'api')),
    decided_at        timestamptz not null default now(),
    unique (raw_mention_id, product_id)
);
create index mentions_product_idx on mentions (product_id, match_status);

-- The hand-labeled pairs the eval joins against, loaded from the committed TSV — the
-- counterpart of resolution_labels for mentions.
create table mention_labels (
    id             bigint generated always as identity primary key,
    raw_mention_id bigint not null references raw_mentions(id) on delete cascade,
    product_id     bigint not null references products(id),
    match          boolean not null,
    note           text,
    labeled_at     timestamptz not null default now(),
    unique (raw_mention_id, product_id)
);
