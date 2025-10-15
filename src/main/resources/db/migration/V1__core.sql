-- V1__core.sql — Shelf core schema (M0).
-- Tables the M1 single-threaded crawl needs. The work queue + leases come in M2 (V2).
-- See docs/Spec.md §6 and docs/Design Decisions.md ("Postgres for everything").

-- ---------------------------------------------------------------------------
-- catalog
-- ---------------------------------------------------------------------------

-- brand_norm / model_norm hold the normalized identity the crawler matches on
-- (lowercased, punctuation folded, whitespace collapsed — see crawl/Normalizer.java).
-- They are the blocking key M3's entity resolution will reuse, which is why the
-- uniqueness constraint lives on them rather than on the display spellings.
create table products (
    id             bigint generated always as identity primary key,
    category       text not null,
    brand          text not null,
    model          text not null,
    brand_norm     text not null,
    model_norm     text not null,
    canonical_name text not null,
    spec           jsonb not null default '{}'::jsonb,   -- validated in-app vs the category spec_schema
    first_seen     timestamptz not null default now(),
    last_seen      timestamptz not null default now(),
    unique (category, brand_norm, model_norm)
);

create table offers (
    id                bigint generated always as identity primary key,
    product_id        bigint references products(id),     -- null until entity resolution (M3)
    retailer          text not null,
    url               text not null unique,
    title             text,                               -- the listing's own title; M3 scores against it
    retailer_sku      text,                               -- retailer-side id where the API exposes one
    condition         text not null default 'new',
    currency          text not null default 'USD',
    resolution_status text not null default 'pending'
                      check (resolution_status in ('pending','auto','reviewed','rejected')),
    resolution_score  double precision,
    first_seen        timestamptz not null default now(),
    last_seen         timestamptz not null default now()
);
create index offers_product_id_idx on offers (product_id);
create index offers_retailer_idx   on offers (retailer);

-- ---------------------------------------------------------------------------
-- crawl bookkeeping
-- ---------------------------------------------------------------------------

create table crawl_runs (
    id           bigint generated always as identity primary key,
    category     text not null,
    started_at   timestamptz not null default now(),
    finished_at  timestamptz,
    pages        integer not null default 0,
    errors       integer not null default 0,
    worker_count integer not null default 1
);

create table raw_fetches (
    id           bigint generated always as identity primary key,
    url          text not null,
    crawl_run_id bigint not null references crawl_runs(id),
    fetched_at   timestamptz not null default now(),
    status       integer,
    body_ref     text                                   -- path under ./data/raw/ (gitignored)
);
create index raw_fetches_run_idx on raw_fetches (crawl_run_id);

create table robots_cache (
    domain     text primary key,
    body       text not null,
    fetched_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- observations — RANGE partitioned by month on observed_at.
-- The partition key must be in the PK, hence (offer_id, observed_at). That PK is
-- also the crawl's idempotency key: one observation per offer per run, so a task
-- retried after a lease expiry (M2) cannot double-write.
-- ---------------------------------------------------------------------------

create table price_observations (
    offer_id       bigint      not null references offers(id),
    observed_at    timestamptz not null,
    price_cents    integer     not null,
    shipping_cents integer     not null default 0,
    in_stock       boolean     not null default true,
    crawl_run_id   bigint      not null references crawl_runs(id),
    source         text        not null default 'observed' check (source in ('observed','synthetic')),
    primary key (offer_id, observed_at)
) partition by range (observed_at);

-- Declared on the parent, so Postgres creates the matching index on every current
-- and future partition. Used to scope a run's writes (the M1 integration test) and
-- by M2's per-cycle rollup recompute.
create index price_observations_run_idx on price_observations (crawl_run_id, observed_at);

-- Creates the monthly partition covering `target` if it does not exist, and returns
-- its name. Idempotent, so it is safe to call at the start of every crawl run —
-- which is what CrawlRunner does, for the current and the next month.
--
-- Bounds are UTC calendar months, computed by converting to a naive UTC timestamp and
-- doing the month arithmetic there. This matters: date_trunc()/+ interval on a
-- timestamptz resolve against the *session* TimeZone, so a client running in
-- America/New_York would otherwise cut partitions at 04:00Z and a later UTC client
-- creating the next month would be rejected for overlapping it. The %L literals carry
-- an explicit offset, so the stored bound is the same instant for every client.
--
-- There is deliberately no DEFAULT partition: a row whose observed_at falls outside
-- every partition should fail loudly rather than land in a catch-all that later
-- blocks ATTACH. M4 revisits this (pg_partman vs this function on a schedule) when
-- the historical backfill needs partitions created in bulk.
create or replace function ensure_price_partition(target timestamptz)
returns text
language plpgsql
as $$
declare
    month_start_utc timestamp   := date_trunc('month', target at time zone 'UTC');
    month_start     timestamptz := month_start_utc at time zone 'UTC';
    month_end       timestamptz := (month_start_utc + interval '1 month') at time zone 'UTC';
    part_name       text        := 'price_observations_' || to_char(month_start_utc, 'YYYY_MM');
begin
    if to_regclass(format('public.%I', part_name)) is null then
        execute format(
            'create table %I partition of price_observations for values from (%L) to (%L)',
            part_name, month_start, month_end);
    end if;
    return part_name;
end;
$$;

-- Bootstrap the current + next month so a fresh database can take writes immediately.
select ensure_price_partition(now());
select ensure_price_partition(now() + interval '1 month');
