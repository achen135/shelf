# M5 — the deal signal, backtested

`keyboards-backtest.txt` is the output of

    shelf eval backtest --category keyboards --out data/benchmarks/m5/keyboards-backtest.txt

on the local corpus on 2026-09-12: 50 products, 367 days of history (2025-09-11 → 2026-09-12),
of which 2025-09-11 → 2026-09-10 is the labeled synthetic year `shelf backfill` wrote (M4) and
2026-09-11/12 the six live crawl cycles so far. The rule and its thresholds
(`signal/DealRule`) were committed before this was run; the report states them.

## How to read it

For every day, every product's `price_rollups` row is **recomputed as of the end of that day**
by the rollup statement itself (`RollupDao.computeProducts` — the same SQL that writes the
table, with a `select` for a sink), so the rule sees nothing after the day. A call is judged
by what the price then did over the next 30 days: a `buy` is right if no buyable price at
least 2% lower came, a `wait` is right if one did, `neutral` abstains. Beside it, two baselines
that never abstain — *always buy*, and *buy when below the trailing-year median of the daily
series* — each also tallied on exactly the days the rule spoke. A second measure follows each
strategy from every scored day and records what it paid relative to that day's price.

**Every scored day rests on synthetic history** — the report says "2,518,062 (100.0%)
synthetic" — because the last day with a full 30-day window ahead of it is 2026-08-13, before
any real observation. The headline is a statement about the harness and the rule's behaviour
on a plausible year (`backfill/SyntheticSeries`, committed), not about keyboard prices:

| 30 days, drop ≥ 2%, 11,798 product-days | coverage | hit rate | paid ÷ today |
|---|---|---|---|
| rule | 0.845 | **0.744** | 0.9179 |
| always buy | 1.000 | 0.360 | 1.0000 |
| buy below median | 1.000 | 0.712 (0.738 on the rule's days) | 0.9174 |

The tables that follow the headline are the ones to argue with: the horizon (the rule loses
to always-buy at 7 days), the drop tolerance, a sweep of each threshold, hit rate by reason
code, and the per-product rows. The sale-window sweep shows the hit rate can be pushed to
0.829 at the cost of most of the coverage and most of the saving; the rule was not tuned to
it. Narrative and the reasoning behind every number: `docs/benchmarks/m5-deal-signal-backtest.md`.
