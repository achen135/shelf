-- Table and index sizes: the parent's partitions one by one, then the rest of the schema.
select c.relname as relation,
       pg_size_pretty(pg_table_size(c.oid))   as heap,
       pg_size_pretty(pg_indexes_size(c.oid)) as indexes,
       pg_size_pretty(pg_total_relation_size(c.oid)) as total,
       coalesce(s.n_live_tup, 0) as rows_est
from pg_class c
left join pg_stat_user_tables s on s.relid = c.oid
where c.relkind in ('r', 'p')
  and c.relnamespace = 'public'::regnamespace
  and (c.relname like 'price_observations%' or c.relname in ('offers', 'products', 'price_rollups', 'crawl_runs', 'crawl_tasks'))
order by c.relname;
