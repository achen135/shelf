# M10 — the community consensus on the corpus

Measured 2026-09-18 on `m10/consensus`, against the local Postgres: the real corpus `shelf
ingest` read the day before (keyboards 3,206 rows from three channels; monitors 1,785 from two)
after the M9 mention pass. Re-derive with `shelf mentions --category <name>` (which ends with the
consensus pass) or `shelf consensus --category <name>`; the table is
`consensus-table.txt`, from `select … from consensus_scores join products … where
mention_count > 0`.

| | keyboards | monitors |
|---|---|---|
| raw mentions → linked mention rows | 3,206 → 13 (+ 4 proposed) | 1,785 → 9 (+ 1 proposed) |
| products with a mention in the 90-day window | **8 of 50** | **4 of 127** |
| leaning | 4 liked / 3 mixed / 1 disliked | 0 / 4 / 0 |
| the pass | 31 ms | 44 ms |
| the mention pass before it | 351 ms | 310 ms |

Twelve products with a consensus, none from more than one community, none from more than six
mentions — the Alienware AW2725Q's six are five neutral ("which should I get?") and one
positive. Every number on the page shows its count for exactly this reason: the largest count
in the corpus is six, and a score of 1.00 from two mentions (Rainy 75 Pro) must not read like a
verdict. Monitors have no mention labels yet; their nine links are unmeasured.

The API, single requests on the laptop, not k6: `/products/{id}` 13 ms warm with the consensus
join and the two-row quote lookup (0.05 ms by EXPLAIN ANALYZE), `/products?sort=consensus`
8–13 ms. M6's load numbers were not re-run.
