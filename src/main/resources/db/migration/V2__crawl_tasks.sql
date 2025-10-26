-- V2__crawl_tasks.sql — the work queue and the shared politeness state (M2).
-- See docs/Design Decisions.md ("Work queue in Postgres", "Leader election via advisory
-- locks") and docs/Architecture.md "Crawl lifecycle (M2)".

-- ---------------------------------------------------------------------------
-- crawl_tasks — one row per unit of work: one page of one list path of one retailer,
-- inside one crawl run.
--
-- State machine (workers move rows left to right, the coordinator moves them back):
--
--   queued --claim--> leased --commit--> done
--                       |                        (success: results written + successor page
--                       |                         enqueued in the same transaction)
--                       +--fail--> error --reaper, not_before passed--> queued
--                       +--fail, attempts = max--> dead
--                       +--lease expired, reaper--> queued  (or dead at max_attempts)
--
-- `attempts` counts claims, incremented when a worker takes the lease, so a worker that
-- dies mid-task has already been charged for the attempt and the reaper never has to
-- guess whether it ran. `max_attempts` claims and the row is dead-lettered with the last
-- error kept for a human.
-- ---------------------------------------------------------------------------

create table crawl_tasks (
    id                   bigint generated always as identity primary key,
    crawl_run_id         bigint not null references crawl_runs(id),
    category             text not null,
    retailer             text not null,
    list_path            text not null,
    page                 integer not null check (page >= 1),
    -- the registrable domain the fetch will hit; the claim query orders by the domain
    -- whose next slot is soonest, so N workers spread across retailers instead of
    -- queueing up behind one domain's rate limit
    domain               text not null,
    state                text not null default 'queued'
                         check (state in ('queued','leased','done','error','dead')),
    leased_by            text,                       -- worker id; kept after done for the record
    lease_expires_at     timestamptz,
    attempts             integer not null default 0,
    max_attempts         integer not null default 3,
    not_before           timestamptz not null default now(),   -- retry backoff gate
    last_error           text,
    enqueued_at          timestamptz not null default now(),
    started_at           timestamptz,               -- most recent claim
    finished_at          timestamptz,
    -- what the page produced, filled on done (the per-retailer numbers CrawlSummary reports)
    offers_seen          integer not null default 0,
    offers_written       integer not null default 0,
    observations_written integer not null default 0,
    matched              integer not null default 0,
    skipped_by_robots    boolean not null default false,
    -- enqueueing the successor page is idempotent: a page exists once per run
    unique (crawl_run_id, retailer, list_path, page)
);

-- The claim query: queued rows whose backoff has passed, cheapest first.
create index crawl_tasks_claim_idx on crawl_tasks (not_before, id) where state = 'queued';
-- The reaper: leased rows past their lease.
create index crawl_tasks_lease_idx on crawl_tasks (lease_expires_at) where state = 'leased';
-- Retry promotion, and "is this run finished?" (no row in a live state).
create index crawl_tasks_run_state_idx on crawl_tasks (crawl_run_id, state);

-- ---------------------------------------------------------------------------
-- domain_rate_limits — the one bucket per registrable domain that every worker draws on.
--
-- Only the next-allowed instant lives here. The interval (1/max_rps, or robots.txt's
-- Crawl-delay if slower) is configuration, which every worker computes identically from
-- the same category file, so storing it would only create a second source of truth.
-- Reserving a slot is one upsert: `next_allowed_at = greatest(next_allowed_at, now()) +
-- interval`, which the row lock serialises across workers, and the caller sleeps until
-- the slot it was handed. All arithmetic is in database time, so worker clocks need not
-- agree with each other.
-- ---------------------------------------------------------------------------

create table domain_rate_limits (
    domain          text primary key,
    next_allowed_at timestamptz not null
);
