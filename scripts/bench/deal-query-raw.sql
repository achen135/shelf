-- The representative deal query, computed from raw observations — what the M6 API would have
-- to run if price_rollups did not exist. Kept identical across every stage of the M4 benchmark
-- so the plans compare like for like (scripts/explain.sh, data/benchmarks/m4/).
--
-- "Keyboards with an in-stock price between $100 and $200, cheapest-relative-to-its-year first":
-- a product's current price is the cheapest in-stock listing among its linked offers, and its
-- percentile is the share of the trailing year's (per-instant cheapest in-stock) prices that
-- were below today's.
with linked as (
  select id as offer_id, product_id
  from offers
  where product_id is not null and resolution_status in ('auto', 'reviewed')
),
latest as (
  select distinct on (po.offer_id) po.offer_id, l.product_id, po.price_cents, po.in_stock
  from price_observations po
  join linked l on l.offer_id = po.offer_id
  order by po.offer_id, po.observed_at desc
),
current as (
  select product_id, min(price_cents) as current_price_cents
  from latest
  where in_stock
  group by product_id
),
series as (
  select l.product_id, po.observed_at, min(po.price_cents) as price_cents
  from price_observations po
  join linked l on l.offer_id = po.offer_id
  where po.in_stock and po.observed_at > now() - interval '365 days'
  group by l.product_id, po.observed_at
),
ranked as (
  select s.product_id,
         count(*) filter (where s.price_cents < c.current_price_cents)::float / count(*) as percentile_365d,
         min(s.price_cents) as min_365d,
         percentile_cont(0.5) within group (order by s.price_cents) as median_365d
  from series s
  join current c on c.product_id = s.product_id
  group by s.product_id
)
select p.id, p.canonical_name, c.current_price_cents, r.percentile_365d, r.min_365d, r.median_365d
from products p
join current c on c.product_id = p.id
join ranked r on r.product_id = p.id
where p.category = 'keyboards'
  and c.current_price_cents between 10000 and 20000
order by r.percentile_365d, c.current_price_cents
limit 20;
