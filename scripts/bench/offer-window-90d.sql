-- One offer's trailing 90-day window — the shape every rollup aggregate takes, and the query
-- that shows partition pruning: only the partitions the window can touch should appear in the
-- plan, the rest are removed at plan time ("Subplans Removed") or never listed.
select count(*), min(price_cents), max(price_cents)
from price_observations
where offer_id = (select id from offers
                  where url = 'https://www.keychron.com/products/keychron-q2-qmk-custom-mechanical-keyboard?variant=39610695647321')
  and observed_at > now() - interval '90 days';
