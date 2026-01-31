-- V6__raw_mentions.sql — the community-ingestion staging table (M8, the first v2 migration).
-- See docs/Spec v2.md §4 and docs/Architecture.md "Ingestion".

-- ---------------------------------------------------------------------------
-- raw_mentions — what a community said, verbatim, one row per post, video or comment.
--
-- This is the counterpart of raw_fetches for the community track: written by `shelf ingest`,
-- read by M9's mention resolution, never linked or scored here. The ingester stores the text
-- and where it came from and stops; a clean seam between "we have the words" and "we know
-- what they are about", the same seam the crawl keeps by never linking an offer.
--
-- (source, source_id) is the idempotency key, the way (offer_id, observed_at) is the crawl's:
-- a Reddit post is `t3_<id>`, a comment `t1_<id>`, a YouTube video its 11-character id, a
-- comment its own id — none ever changes, so a repeat ingest of the same window upserts the
-- same rows and the count stands still. The unique constraint carries the guarantee, not an
-- application-side check-then-insert, so two ingests racing cannot both insert.
--
-- fetched_at is refreshed on every re-sighting because YouTube's API terms (Developer Policies
-- §III.E.4) allow stored API data to be kept for 30 days, after which it must be refreshed
-- or deleted; the ingester prunes youtube_* rows whose fetched_at has gone stale. Reddit rows
-- keep the same column for symmetry.
--
-- parent_source_id ties a comment to its post or video (a source_id in this same table, when
-- the parent was ingested too). title is only ever set on a post or a video.
-- ---------------------------------------------------------------------------

create table raw_mentions (
    id               bigint generated always as identity primary key,
    category         text not null,
    source           text not null
                     check (source in ('reddit_post', 'reddit_comment', 'youtube_video', 'youtube_comment')),
    source_id        text not null,
    community        text not null,                    -- the config's community name
    parent_source_id text,
    title            text,
    text             text not null,
    author_ref       text,                             -- platform username / channel id, for attribution
    posted_at        timestamptz,
    fetched_at       timestamptz not null default now(),
    permalink        text not null,
    unique (source, source_id)
);
create index raw_mentions_category_idx on raw_mentions (category, community, posted_at);
