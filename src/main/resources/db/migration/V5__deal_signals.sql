-- V5__deal_signals.sql — the buy / wait / neutral signal (M5).
-- See docs/Design Decisions.md ("Rule-based deal signal first; ML deferred to v2") and
-- docs/Architecture.md "Signal".

-- ---------------------------------------------------------------------------
-- deal_signals — one row per product: what a shopper should do about today's price, and why.
--
-- The signal is a rule over the product's price_rollups row (signal/DealRule): where today's
-- price sits in the trailing year, whether it is a sale by the rollup's own definition, how
-- close it is to the year's low, and whether the product goes on sale at all. It is refreshed
-- in place after every cycle for the products the rollup pass just recomputed — the same
-- touched set, one step further down the hook chain — and by `shelf signal` for a whole
-- category. Every decision carries its reason codes, so a `buy` on the page can say
-- "on sale, at the year's low" rather than just be a badge.
--
-- as_of is the rollup instant the call is a function of; it matches the product's
-- price_rollups.as_of when the two are in step, and a mismatch means the signal is stale.
-- best_offer_id is the listing behind the price (the rollup's current_offer_id): a `buy` must
-- point at something buyable, hence the check.
-- ---------------------------------------------------------------------------

create table deal_signals (
    product_id     bigint primary key references products(id),
    signal         text not null check (signal in ('buy', 'wait', 'neutral')),
    reason_codes   text[] not null default '{}',
    best_offer_id  bigint references offers(id),
    as_of          timestamptz not null,
    computed_at    timestamptz not null default now(),
    check (signal <> 'buy' or best_offer_id is not null)
);
