-- V4__rollups.sql — offer retirement and the price rollups (M4).
-- See docs/Design Decisions.md ("An offer retires after three unseen cycles", "Rollups are a
-- table recomputed per cycle, not a materialized view") and docs/Architecture.md "Rollups".

-- ---------------------------------------------------------------------------
-- offers.retired_at — liveness.
--
-- A crawl only ever adds. An offer that disappears from a retailer's listing keeps its row,
-- its history and its last price, and until now nothing said it was gone — so a delisted SKU
-- would have sat in every "current" number forever. The rollup pass marks an offer retired once
-- it has gone unseen for N consecutive completed crawl cycles of its category (its last_seen is
-- older than the start of the Nth most recent finished run; N = 3 by default), and the crawl
-- clears the mark the moment it sees the offer again. Retired offers keep their rows and their
-- observations; they stop counting as current: a product's price, and every window in its
-- rollup, is computed over live offers only.
-- ---------------------------------------------------------------------------

alter table offers add column retired_at timestamptz;

-- ---------------------------------------------------------------------------
-- crawl_runs.kind — a backfill is a run too.
--
-- price_observations.crawl_run_id is not null, and the synthetic backfill writes observations,
-- so it gets a crawl_runs row of its own — marked, so nothing mistakes it for a crawl. The
-- coordinator's "is a cycle due" and the retirement cutoff look at crawl runs only.
-- ---------------------------------------------------------------------------

alter table crawl_runs
    add column kind text not null default 'crawl' check (kind in ('crawl', 'backfill'));

-- ---------------------------------------------------------------------------
-- price_rollups — trailing-window statistics, one row per offer and one per product.
--
-- A table, not a materialized view: a view can only be refreshed whole, and a plain REFRESH
-- takes a lock that blocks every reader, while the pass after a crawl cycle knows exactly which
-- offers it touched (price_observations_run_idx) and recomputes those rows in place. Every row
-- is the output of one statement over price_observations (db/RollupDao), so the table is as
-- re-derivable as a view: `shelf rollup --category X` rebuilds it from scratch.
--
-- Two grains share the shape. An offer row summarises that listing's own history. A product row
-- summarises the *cheapest in-stock live listing at each instant* — the price a shopper could
-- have paid — over the offers linked to it, and its current price is the cheapest in-stock
-- listing right now (null when nothing is in stock: an unbuyable product has no current price).
--
-- Windows end at as_of — the instant the pass ran, so a row states exactly which "trailing 30
-- days" it means. Observations are counted with their synthetic share (M4 backfill), so a
-- number that leans on synthetic history can say so all the way to the page.
--
-- sale_windows: the trailing year's sales, newest first, each {start, end, min_price_cents,
-- off_pct}. A sale is a run of consecutive observations at or below 90% of the list price, and
-- the list price is the most common price of the year — a retailer sells at list most days.
-- ---------------------------------------------------------------------------

create table price_rollups (
    id                  bigint generated always as identity primary key,
    offer_id            bigint references offers(id),
    product_id          bigint references products(id),
    as_of               timestamptz not null,
    -- now
    current_price_cents integer,
    current_observed_at timestamptz,
    current_in_stock    boolean,
    current_offer_id    bigint references offers(id),   -- product rows: the listing behind the price
    list_price_cents    integer,
    -- trailing windows, each ending at as_of; null when the window holds no observation
    min_7d              integer,
    median_7d           integer,
    max_7d              integer,
    min_30d             integer,
    median_30d          integer,
    max_30d             integer,
    min_90d             integer,
    median_90d          integer,
    max_90d             integer,
    min_365d            integer,
    median_365d         integer,
    max_365d            integer,
    -- where today sits in the year: the share of the year's prices strictly below the current
    -- one (0 = a year low), and the year's coefficient of variation
    percentile_365d     double precision,
    volatility_365d     double precision,
    observations_365d   integer not null default 0,
    synthetic_365d      integer not null default 0,
    -- sales
    sale_windows        jsonb not null default '[]'::jsonb,
    sale_days_365d      integer not null default 0,
    last_sale_ended_at  timestamptz,
    computed_at         timestamptz not null default now(),
    check (num_nonnulls(offer_id, product_id) = 1)
);

-- One row per offer and one per product; also what the recompute's upsert conflicts on.
create unique index price_rollups_offer_idx   on price_rollups (offer_id)   where offer_id is not null;
create unique index price_rollups_product_idx on price_rollups (product_id) where product_id is not null;
