# RL Design

## Learning problem

Continuing online RL with a tiny action space and virtual-only execution.

## State

Only L2 order-book state and virtual position state are allowed.

No candles, indicators, momentum/volume rules, or off-phone features are used.

## Actions

```text
WAIT
ENTER_LONG
ENTER_SHORT
HOLD
EXIT
```

## Masks

Masks prevent nonsensical or unsafe actions:

| State | Valid actions |
|---|---|
| Flat | WAIT, ENTER_LONG, ENTER_SHORT |
| Holding | HOLD, EXIT |

Future masks should include stale feed, max loss, max trades, and forced max-hold exit.

## Current algorithm

The MVP uses masked Double Q-learning with linear function approximation.

Reasons:

- easy to run on phone CPU
- no native ML dependency needed
- off-policy replay reuses scarce live experience
- debuggable Q-values

## Upgrade path

Once replay persistence and audit logs are stable, replace the linear approximator with a small neural model:

```text
input → Dense(128) → ReLU → Dense(128) → ReLU → dueling Q head → 5 actions
```

The action-mask interface should remain identical.

## Reward

```text
reward_t = executable_virtual_equity_t - executable_virtual_equity_t_minus_1
```

Executable virtual equity uses bid/ask liquidation and fees.
