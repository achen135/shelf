# M12 — the deal classifier: measured against the rule, and it did not beat it

The question Spec v2 §6 asked, in two parts. **The gate:** can the M5 backtest's as-of grid be a
training set, and can a trained decision function swap for `DealRule` inside the same harness,
with no new infrastructure — no training pipeline, no model file format, no second runtime, no
dependency? **The milestone, if the gate held:** train an interpretable model on that grid with a
time-respecting split, judge it through the same `Backtest` against the same two baselines and
the rule, and report whichever wins.

## The gate held: no new infrastructure

`Backtest.Strategy` was already the decision interface — one method, `call(Rollup asOf, Series,
day)` — with the rule, "always buy" and "buy below median" as its three implementations. The
grid already held every product's as-of rollup row for every day, and `ahead()` already computed
the label (a buyable price ≥ 2% lower within the horizon). What it took to turn that into a
trained model and a fourth strategy (`diff-stat.txt`):

| | |
|---|---|
| `Backtest.java` | **+15 lines**: two grid-level accessors, `scored` and `ahead`, that expose the tally's own two tests. `tally`, `compare`, `follow`, `evaluate` untouched |
| `signal/DealClassifier` | the model: a logistic regression by full-batch gradient descent in plain Java (289 lines, ~40 of them the arithmetic). Deterministic; no library; no file — the model is its printed weights |
| `signal/ClassifierEval` | the split, the training rows, the held-out tallies through the unmodified `tally`, and the report (352 lines) |
| `cli/EvalCommand` | `shelf eval classifier -c <category> [--split 0.7]` |
| `build.gradle.kts`, the migrations, `db/`, every other package | **unchanged** |

Wall clock for the whole eval, grid included: keyboards 16 s, monitors 10 s. 353 tests.

## Fixed before the first run on real data

In the spirit of the rule's thresholds (M5) and the frozen labels (M3, M9) — none of this was
touched after a number came back:

- **Features**, read off the same as-of row the rule reads: price / list, price / year low, price
  / year median, percentile 365d, sale days / 365, sale windows in the year, days since the last
  sale ended / 365 (a year when none). Five of the seven — the list and low ratios, the
  percentile, the sale days and windows — are what the rule reads; the median ratio and the
  recency are the "one or two more" the plan allowed. Standardized by the training set's mean
  and deviation, so a weight is log-odds per standard deviation.
- **Label**: the backtest's own — a drop ≥ 2% within 30 days — at the standing settings (90-day
  warm-up). The same three gates as the rule (no price, out of stock, < 30 points → no call).
- **Training**: learning rate 0.5, 3,000 iterations, L2 0.001.
- **Two operating points**: *decisive* (buy at P(drop) ≤ 0.5, else wait — never abstains, like the
  baselines) and *abstaining* (buy ≤ 0.4, wait ≥ 0.6, else no call — abstains, like the rule).
- **The split, chronological with a gap.** The first 70% of the span's days are the training era;
  a scored day trains only if its 30-day horizon closes before the split day, so no training
  label reads a held-out price. Every strategy is then judged on the identical held-out days
  (day ≥ split) through `tally` with its `include` predicate. A random day-level split would
  leak: neighbouring days' horizon windows overlap almost entirely, so the same future prices
  would sit on both sides — the backtest's no-look-ahead rule, applied to training.

## The numbers — held out, 30 days, drop ≥ 2%

`keyboards-classifier.txt`, `monitors-classifier.txt` are the reports as written.

**Keyboards** — 51 products, split at day 257 of 368 (2026-05-26); 6,696 training rows (62.4%
with a drop ahead); **3,928 held-out product-days**, their features **100% synthetic**
(1,167,905 observations; 3.7% of held-out days are judged against at least one observed day):

| strategy | coverage | hit rate | buys | buy ok | waits | wait ok |
|---|---|---|---|---|---|---|
| rule | 0.828 | **0.809** | 762 | 0.749 | 2,489 | 0.828 |
| always buy | 1.000 | 0.327 | 3,928 | 0.327 | — | — |
| buy below median | 1.000 | 0.746 | 1,594 | 0.589 | 2,334 | 0.853 |
| model, decisive | 1.000 | 0.761 | 730 | 0.737 | 3,198 | 0.767 |
| model, abstaining | 0.818 | **0.811** | 457 | 0.856 | 2,758 | 0.803 |

On the 3,251 days the rule spoke, the decisive model scores 0.775 to the rule's 0.809. On the
3,215 days the abstaining model spoke, the rule scores 0.829 (at 0.824 coverage) to the model's
0.811. Base rate: a drop followed 67.3% of held-out days.

**Monitors** — 127 products, split at day 256 of 366 (2026-05-30); 16,006 training rows (40.7%
with a drop ahead); **9,410 held-out product-days**, **100% synthetic** (2,678,330
observations; 1.3% reach an observed day):

| strategy | coverage | hit rate | buys | buy ok | waits | wait ok |
|---|---|---|---|---|---|---|
| rule | 0.970 | **0.603** | 1,594 | 0.821 | 7,530 | 0.557 |
| always buy | 1.000 | 0.507 | 9,410 | 0.507 | — | — |
| buy below median | 1.000 | 0.600 | 2,385 | 0.710 | 7,025 | 0.563 |
| model, decisive | 1.000 | 0.558 | 4,616 | 0.566 | 4,794 | 0.551 |
| model, abstaining | 0.523 | 0.620 | 2,480 | 0.664 | 2,437 | 0.575 |

On the 9,124 days the rule spoke, the decisive model scores 0.559 to the rule's 0.603. On the
4,917 days the abstaining model spoke, **the rule scores 0.663** to the model's 0.620 — the
model's higher headline is the easier half of the days, not a better call. Base rate 49.3%.

## The finding

**A logistic regression trained on the rule's own features does not improve on the rule.** On
keyboards it ties (0.811 vs 0.809 at the same coverage; below it at full coverage); on monitors
it loses at every operating point, and by more on the days the model chose to speak on. The
hand-set rule already captures what this feature set carries about the next thirty days.

Two things the report shows that a headline would hide:

- **The in-sample edge does not survive the era change.** On monitors the model beats everything
  in its training era (0.636 to the rule's 0.498 and always-buy's 0.593) and loses out of
  sample; the synthetic year's base rate moves from 40.7% in the training era to 49.3% in the
  held-out one, and a model fitted to the first calls the second wrong. The rule, having been
  fitted to nothing, moves less. The chronological split is what exposed this; a random split
  would have reported the in-sample number as the result.
- **The weights are readable and they agree with the rule** (keyboards): price / year median
  +0.63, percentile +0.57 and sale windows +0.61 raise the odds of a drop — a price high for its
  year on a product that discounts often will drop; price / year low −0.18 lowers them. On
  monitors the median ratio (+0.86) and the windows (+0.66) carry nearly everything and the
  percentile is flat (−0.07). The model found the rule's features, weighted; it did not find a
  better line through them.

And the caveat every number in this repo carries: **the year behind these features is
synthetic** — the backfill's generator (M4), with its list-to-the-cent prices, 3–9 sales a year
and joined Black Friday / July windows — so what was measured is whether a model can beat a rule
at predicting *that generator*. On real history the answer could differ either way, and the
first real year's report will say so. Serving the model (a scoring job feeding `deal_signals`)
was a follow-up decision contingent on a win; there is no win, so nothing is served and
`DealRule` stays the signal.

## Re-deriving

```
shelf eval classifier --category keyboards --out data/benchmarks/m12/keyboards-classifier.txt
shelf eval classifier --category monitors  --out data/benchmarks/m12/monitors-classifier.txt
git diff --stat main -- build.gradle.kts src/main/resources/db/migration src/main/java/com/achen/shelf/{backfill,config,consensus,crawl,db,ingest,mention,resolve,rollup,api}
```

The corpus at the time: keyboards 51 products (the Wooting 80HE from M11 included), observed
2026-09-11 → 2026-09-13; monitors 127 products, observed 2026-09-16 only. Training is
deterministic, so the same corpus gives the same weights to the last digit.
