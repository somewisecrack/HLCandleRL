# Roadmap

## Phase 1 — MVP hardening

- Add foreground service.
- Add reconnect/backoff logic.
- Add stale-book entry mask.
- Add Room SQLite trade ledger.
- Add replay persistence.
- Add model checkpoint save/load.
- Add PnL chart.

## Phase 2 — Evaluation discipline

- Add always-wait baseline.
- Add random-valid baseline.
- Add frozen-policy baseline.
- Track action distribution and Q-value ranges.
- Add reward permutation control.

## Phase 3 — Better learner

- Replace linear approximator with small Dueling Double DQN.
- Add target network update cadence.
- Add n-step returns.
- Add stratified replay sampling.

## Phase 4 — Multi-market support

- Configurable HyperLiquid coin.
- Separate policy/replay/checkpoint per market.
- Dashboard for multiple virtual policies.
