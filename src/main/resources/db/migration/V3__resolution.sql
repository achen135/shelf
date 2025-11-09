-- V3__resolution.sql — entity resolution (M3).
-- See docs/Design Decisions.md ("Resolution runs after the crawl, and the crawl never links")
-- and docs/Architecture.md "Resolution".

-- ---------------------------------------------------------------------------
-- offers: the facts resolution scores, and where its decision lands.
--
-- The crawl now records what a listing *says* — its brand, its title, the spec values
-- read from it — and nothing else. Linking a listing to a product is the resolver's job,
-- run after a cycle closes, and every link it makes carries the score that justified it.
-- ---------------------------------------------------------------------------

alter table offers
    -- the brand as the retailer states it (fixed per retailer, or the listing's vendor), and
    -- its normalized form: the blocking key, the same one products are unique on
    add column brand      text,
    add column brand_norm text,
    -- per-listing spec, validated against the category schema at crawl time. This is the
    -- variant-level truth; products.spec is a summary derived from the linked offers.
    add column spec       jsonb not null default '{}'::jsonb,
    -- the resolver's best guess for an offer it would not link on its own: the review queue is
    -- every pending offer that has one
    add column candidate_product_id bigint references products(id),
    -- when the resolver last scored this offer
    add column resolved_at timestamptz;

create index offers_brand_norm_idx on offers (brand_norm);
create index offers_review_idx on offers (resolution_score desc)
    where resolution_status = 'pending' and candidate_product_id is not null;

-- M1 linked at crawl time by exact model containment, which is a feature of the M3 scorer
-- rather than a rule of its own, and it recorded no score. Those links are returned to the
-- queue so the resolver — which is measured against labels — decides them like any other
-- listing; a link a human made ('reviewed') is not touched, now or ever.
update offers
set product_id = null, resolution_status = 'pending'
where resolution_status = 'auto' and resolution_score is null;

-- The invariants the four states mean:
--   pending   no link; candidate_product_id/resolution_score may hold the resolver's guess
--   auto      linked by the resolver, with the score that did it
--   reviewed  linked by a human
--   rejected  a human said "not a catalog product"; the resolver leaves it alone
alter table offers
    add constraint offers_link_matches_status
        check ((resolution_status in ('auto', 'reviewed')) = (product_id is not null)),
    add constraint offers_auto_has_score
        check (resolution_status <> 'auto' or resolution_score is not null);

-- The crawl no longer links, so a task has no "matched" count to report.
alter table crawl_tasks drop column matched;

-- ---------------------------------------------------------------------------
-- resolution_labels — the hand-labeled evaluation set: does this offer belong to this product?
--
-- Rows are loaded from a committed file (data/labels/<category>-resolution.tsv) by
-- `shelf eval resolution`, which keys offers by URL and products by brand + model so the
-- labels survive a rebuilt database. The table is the copy the eval joins against.
-- ---------------------------------------------------------------------------

create table resolution_labels (
    id         bigint generated always as identity primary key,
    offer_id   bigint not null references offers(id),
    product_id bigint not null references products(id),
    match      boolean not null,
    note       text,
    labeled_at timestamptz not null default now(),
    unique (offer_id, product_id)
);
