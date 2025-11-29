-- An offer's full price history — the product-detail page's sparkline (M6). Keyed by URL so the
-- query survives a rebuilt database; this variant is the Keychron Q2 the M3 eval leans on.
select observed_at, price_cents, in_stock, source
from price_observations
where offer_id = (select id from offers
                  where url = 'https://www.keychron.com/products/keychron-q2-qmk-custom-mechanical-keyboard?variant=39610695647321')
order by observed_at;
