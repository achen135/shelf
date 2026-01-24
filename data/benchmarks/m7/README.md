# M7 — onboarding monitors, measured

The claim this directory exists to earn: *a second category was added as configuration plus
one spec extractor, with nothing in the crawl, resolution, rollup, signal or API code
changed.* Everything below is re-derivable from the repo and the commands named.

## What changed, by `git diff --stat`

`diff-stat.txt` is `git diff --stat cb0e016 <the M7 code commit>` — `main` before the branch
against the commit that onboarded the category — and reads, in full:

| path | lines | what |
|---|---|---|
| `categories/monitors.yaml` | +311 | the category: 9 spec fields, 6 retailers, a `resolution:` section, 127 seed products |
| `crawl/parse/MonitorSpecExtractor.java` | +312 | the one new class: titles and tags → spec values |
| `crawl/parse/ParserRegistry.java` | +4 −2 | one `case "monitors"` line, and its comment |
| `docker-compose.yml` | +2 −1 | the coordinator schedules both categories |
| `test/.../MonitorSpecExtractorTest.java`, `MonitorFixturesTest.java` | +457 | 19 tests |
| `test/.../CategoryConfigLoaderTest.java` | +23 | the shipped config is valid |
| `test/resources/fixtures/shopify/{pixio,focuscamera,innocn}-products.json` + README | +556 | three captured bodies, provenance |

**Zero lines changed under `crawl/` outside `crawl/parse/`, and zero under `resolve/`,
`rollup/`, `signal/`, `api/`, `db/`, `config/`, `cli/`, `backfill/` or `resources/db/`.** The
parsers themselves (`ShopifyProductsJsonParser`, `ShopifyCollectionHtmlParser`) are untouched:
the registry hands them a different extractor. No migration. The API and the page needed
nothing — `/categories` now lists two schemas and the page builds its second set of controls
from it.

**Effort:** **under an hour of wall clock** — 2026-09-16, 13:46 (the first retailer probe) →
14:15 (this file's commit), the merge minutes later — including the survey, the labels, every
run below and this write-up; **627 lines of non-test source** (311 YAML + 316 Java), 11 files
touched in the code commit, 16 on the branch. The file timestamps and the crawl log
(`14:04:26 crawl run 10 started`) are the record.

## The retailer survey (what the config is built on)

Every candidate was probed on 2026-09-16 with the crawler's own conditions: the public
storefront JSON, then `robots.txt`. Kept, all Shopify `mode: api`: **Focus Camera**
(multi-brand — Dell, LG, Samsung, BenQ, Asus, Sony; 246 listings), **Pixio** (140 colourway
variants of 34 products, spec-rich tags), **Dough** (24), **KOORUI** (19), **INNOCN** (37, a
localized `/en-us/` storefront), **Cooler Master** (6). Rejected: **sceptre.com** — the one
server-rendered HTML store found (OpenCart, robots-open) lists MSRPs beside "buy at your
e-tailers" links and has no cart, so its price is a list price, not a sold one; **us.aoc.com**
(redirect loop); antonline, monoprice, msi (403); abt (Cloudflare challenge); lg, dell,
samsung, viewsonic (client-rendered prices); ktcgaming, titanarmy, store.viewsonic
(unreachable). Portable-only brands (Arzopa, Uperfect, Mobile Pixels) were left out on purpose.

**The `html` fetch mode was, for the second category running, not needed** — same outcome as
M0's keyboard survey. It stays implemented and golden-file tested because the config format
promises it.

## The crawl (`shelf crawl --category monitors --once`)

One cycle through the unchanged chain, 2026-09-16 14:04 local:

| | |
|---|---|
| pages / offers / observations / errors | **8 / 472 / 472 / 0** in 15 s (politeness-bound: 0.2 rps per domain) |
| per retailer | focuscamera 246 · pixio 140 · innocn 37 · dough 24 · koorui 19 · coolermaster 6 |
| in stock | 213 of 472 — KOORUI (19) and Cooler Master (6) list every monitor `available: false` and sell through Amazon |
| resolution, first pass | 193 auto-linked / 112 for review / 167 unmatched, 116 products with a derived spec, in 0.2 s |
| rollups | 472 offer + 123 product rows recomputed in **51 ms** |
| signals | 123 products decided in 10 ms (all neutral: one observation each) |
| spec coverage across the 472 listings | size 427 · resolution 413 · panel 345 · refresh 289 · curved 247 · aspect ratio 126 · USB-C 105 · HDR 62 · speakers 33 |

## Entity resolution against the frozen labels (`shelf eval resolution`)

`data/labels/monitors-resolution.tsv`: **206 hand-labeled pairs (120 match / 86 not)**,
written from titles before the resolver had scored a monitor listing; the policy is in the
file's header. Report: `data/labels/monitors-resolution.eval.txt`.

| pass | precision | recall | F1 | in the review band |
|---|---|---|---|---|
| first (config as written before the crawl) | 0.984 | 1.000 | 0.992 | 40 |
| **second (+ `"mouse"`, `"expansion hub"` to `non_product_phrases`)** | **1.000** | **1.000** | **1.000** | 42 |

The two first-pass false positives were both a Dell P2425H sold "with Gaming Mouse and
Extended Mouse Pad" and "with 3-Port USB-C Portable Expansion Hub" — bundles that never say
"bundle", scored 1.00 on a whole-model match. The same lesson as M3's eight bundle FPs, fixed
the same way, in config. **`identity_fields: [size_in]` changes nothing on this set** (the same
120 / 0 / 0 / 86 with it on or off) and is kept on for the domain reasoning, not a measured gain.
The threshold sweep shows recall dropping to 0.750 at 0.95: INNOCN's model-last titles score
exactly 0.90 — a whole-model match minus the late-mention penalty — and sit on the auto
threshold; a labeled set is what makes that visible before a threshold change breaks it.

Corpus after `shelf resolve --category monitors --rescore` on the final config: **191
auto-linked / 113 in review / 168 unmatched**; 123 of 127 products have at least one listing.

**What the multi-brand retailer exposed.** 154 of Focus Camera's 246 listings (63%) carry a
distributor ("Nine Trading LLC", "New Age Electronics (Synnex)", "Joy Systems"…) or "Used
Department" as their Shopify `vendor`, and `brand_source` offers only `vendor` or `fixed`.
Those listings are crawled and priced but block under the distributor's name and never reach a
product. The fix — a `brand_source` that reads the brand off the title — is a config-format
change and was deliberately not made in a pass whose point is to prove none was needed. It is
the first thing the format would need for a third category. Consequence: **the category has no
cross-retailer pair today** — the one candidate, a Cooler Master GM27-FQS at both stores, is a
keyboard bundle at Focus Camera.

## The synthetic year, rollups, signals

`shelf backfill --category monitors --days 365 --seed 20260124` wrote **172,280 synthetic
observations** for the 472 offers (2025-09-16 → 2026-09-15) in **1.3 s**, by the same generator
and with the same `source = 'synthetic'` label as keyboards. The full rollup pass then took
**567 ms** (472 offer + 127 product rows), the signal pass 52 ms: **0 buy / 61 wait / 66
neutral** — every product at its list price by construction, as with keyboards; the 66 neutrals
are 62 `OUT_OF_STOCK` (no in-stock listing: the two storefronts that list everything
unavailable, and Focus Camera's 196 out-of-stock rows) and 4 `NO_PRICE` (no listing at all).

`price_observations` after M7: 2,983,572 rows, of which 53,352 observed and 2,930,220
synthetic (the split is the number that must travel with every figure).

## The backtest (`shelf eval backtest --category monitors`)

`monitors-backtest.txt`, 8.6 s: 127 products (123 scored) × 366 days, **28,939 scored
product-days, features 100% synthetic** (the only observed day is the last one).

| 30 days, drop ≥ 2% | coverage | hit rate | paid ÷ today |
|---|---|---|---|
| rule | 0.944 | **0.532** | 0.9252 |
| always buy | 1.000 | **0.569** | 1.0000 |
| buy below median | 1.000 | 0.534 (0.538 on the rule's days) | 0.9250 |

**On the monitors' synthetic year the rule loses to always-buy at every horizon.** Its buy
calls are right 84.9% of the time; its waits — 85% of its calls — only 47.7%. The base rate of
a ≥ 2% drop within 30 days is **43.1% here against 64.0% for keyboards**, and the reason is
structural, not a property of monitors: a product's price is its cheapest live listing, a
keyboard product has up to 156 listings each with its own independent synthetic sale calendar,
and the minimum of many such series drops far more often than the minimum of one to three. The
same fixed rule that beat the baselines on the busy keyboard year does not on a calm one. The
sale-window sweep repeats M5's shape (≥ 9 windows → 0.865 hit rate at 18% coverage). The
rule and its thresholds are not changed by this — M5 fixed them before any backtest and the
number to carry is that the wait branch is the weak half of the rule on a calm year.

## Résumé-safe phrasing

- *Added monitors as a second category in **under an hour / 627 lines of config and Java**,
  with no change to the crawl, resolution, rollup, signal or API code* — `git diff --stat` in
  this directory.
- *Entity resolution on the new category: precision 1.000 / recall 1.000 on 206 labeled pairs*
  — after one config pass; the first pass was 0.984 precision.
- Not claimed: a cross-retailer comparison for monitors (none exists yet, see above); the deal
  signal's performance on monitors (it loses to always-buy on a synthetic year).
