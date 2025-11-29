-- The same deal query as deal-query-raw.sql, read from price_rollups (M4): "keyboards with an
-- in-stock price between $100 and $200, cheapest-relative-to-its-year first". One row per
-- product, already holding its current price and where that sits in the trailing year.
select p.id, p.canonical_name, r.current_price_cents, r.percentile_365d, r.min_365d, r.median_365d
from products p
join price_rollups r on r.product_id = p.id
where p.category = 'keyboards'
  and r.current_in_stock
  and r.current_price_cents between 10000 and 20000
order by r.percentile_365d, r.current_price_cents
limit 20;
