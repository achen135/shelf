-- V1__core.sql — Shelf core schema (M0 first cut).
-- Tables the M1 single-threaded crawl needs. The work queue + leases come in M2.
-- See docs/Spec.md §6 and docs/Design Decisions.md ("Postgres for everything").

create table products (
    id             bigint generated always as identity primary key,
    category       text not null,
    brand          text not null,
    model          text not null,
    canonical_name text not null,
    spec           jsonb not null default '{}'::jsonb,   -- validated in-app vs the category spec_schema
    first_seen     timestamptz not null default now(),
    last_seen      timestamptz not null default now(),
    unique (category, brand, model)
);

create table offers (
    id                bigint generated always as identity primary key,
    product_id        bigint references products(id),     -- null until entity resolution (M3)
    retailer          text not null,
    url               text not null unique,
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

create table crawl_runs (
    id           bigint generated always as identity primary key,
    category     text not null,
    started_at   timestamptz not null default now(),
    finished_at  timestamptz,
    pages        integer not null default 0,
    errors       integer not null default 0,
    worker_count integer not null default 1
);

-- RANGE partitioned by month on observed_at. The partition key must be in the PK.
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

-- TODO(M0): bootstrap the current + next month partitions and a helper (function or CLI)
--           to pre-create the next month. M4 decides pg_partman vs a scheduled CLI.
-- example:
-- create table price_observations_2026_09 partition of price_observations
--     for values from ('2026-09-01') to ('2026-10-01');

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
