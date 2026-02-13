# M11 — the generalization proof: one seed line, the whole chain, no code

The claim Spec v2 §5 asked M11 to earn: a product added to the catalog **by config alone** is
picked up by ingestion, mention resolution and aggregation — and, because it is also sold by a
configured retailer, by listing resolution, rollups and the signal — with nothing in the pipeline
changed. Same evidence shape as M7's monitors onboarding: the diff stat is the proof.

## What was done, in order (2026-09-18, wall clock)

| when (UTC) | step |
|---|---|
| 17:55:30 | clock starts; branch from `main`; before-state: 50 products, 5 pending listings titled "Wooting 80HE…", 17 mention rows, 8 products with a consensus |
| 17:55:51 | **labels first**: 12 (comment, Wooting 80HE) pairs written from the corpus — 11 match, 1 not — and committed *before the product existed* |
| 17:56:01 | **the change**: one seed line in `categories/keyboards.yaml` — `{ brand: "Wooting", model: "80HE", canonical_name: "Wooting 80HE", aliases: ["80 HE"] }` — plus its comment |
| 17:56:24 | the chain, first pass (`chain.txt`): `resolve` → `rollup` → `signal` → `mentions` (which ends in `consensus`) |
| 17:56:55 | the price side again — see the finding below |
| 17:57:06 | `shelf eval mention-resolution` on the extended labels; `shelf eval resolution` unchanged |
| 17:57:21 | the API, untouched, serves the product |
| 17:57:32 | `diff-stat.txt` recorded |

**Two minutes of wall clock from the first command to the last; 5 lines of config in 1 file
(1 seed line, 4 of comment); 0 lines of Java; 0 files under `crawl/`, `resolve/`, `rollup/`,
`signal/`, `ingest/`, `mention/`, `consensus/`, `api/`, `db/`, `config/`, `cli/`, the migrations
or the page** (`diff-stat.txt`). The branch's other file is the label file, 17 lines.

## What the one line produced

| | |
|---|---|
| listing resolution | the 5 pending mechanicalkeyboards listings ("Wooting 80HE Magnetic 75% … ABS / Black", "… Zinc Alloy / White", "… TenZ Takeover") **auto-linked at 1.00**; a spec derived from them (`{hot_swap, mount_type: plate, layout_size: 75, switch_type: magnetic, keycap_material: pbt}`); the M3 eval unchanged at 1.000 / 0.976 |
| rollup | a product row from the listings' year: current **$209.99**, list $182.99, **73rd percentile** of its year, 357 observations, all synthetic (the backfill wrote the listings' year in M4 as it did every offer's) |
| signal | **neutral — `OUT_OF_STOCK`**: the cheapest listing's current observation is out of stock; the call is what the rule says of the row |
| mention resolution | **12 comments linked** (13 → 25 linked rows); on the frozen labels **recall 1.000, precision 0.960** (99 pairs, 24 match): the one false positive is the one the label file predicted — "the wooting 80he+ once it comes out", a hoped-for sibling whose `+` normalization folds away. Not tuned |
| consensus | **0.17 from 12 mentions** (2 positive, 10 neutral — mostly "he has to buy me a wooting 80he" wagers and review requests), one community; the largest count in the corpus by a factor of two, and the first row whose count is worth a score |
| the API | `/products/1088`: name, price, list, the call, 5 offers, 357 history days, the consensus with its count and two quotes ("just get a wooting 80HE at this point" / "When are going to apologize to the wooting 80he picky?") — no code touched. `sort=consensus` puts it after the four liked products, by design: 0.17 is mixed |

## The finding the proof exposed

`shelf resolve` reads the catalog **from the database** and does not bootstrap the seeds; a crawl
(`CrawlContext.bootstrap`) and the mention pass (M9) do. So the first `resolve` after a config
edit saw 50 products and linked nothing; the mention pass then created the product, and a second
`resolve` linked the five listings. The proof holds — the chain needs no code — but the honest
command order for a config-added product is **`shelf mentions` (or a crawl) before `shelf
resolve`**, and that is a trap. The fix is a `SeedCatalog.bootstrap` call at the top of
`ResolutionRun.run`, three lines; **deliberately not made in the proof pass**, for M7's reason: a
milestone whose deliverable is an empty diff stat cannot also carry the fix. Recorded as the
first thing to do next.

## Re-deriving

```
git checkout main; git checkout -b try; # add the seed line
shelf mentions --category keyboards     # bootstraps the seed, links the comments, scores the consensus
shelf resolve  --category keyboards     # links the listings (after the product exists — see above)
shelf rollup   --category keyboards; shelf signal --category keyboards
shelf eval mention-resolution --category keyboards --labels data/labels/keyboards-mention-resolution.tsv
git diff --stat main
```
