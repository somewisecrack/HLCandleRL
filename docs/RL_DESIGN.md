# RL Design

## Learning problem

Online virtual-only reinforcement learning on HyperLiquid perps using compact mobile-friendly market state.

The Android app now uses:

```text
OHLCV candle window
public perp context
virtual position/account state
```

It does not use L2 order books, private data, real orders, or hardcoded indicator entry/exit rules.

## State

The observation vector is built by `OhlcvFeatureBuilder` from a rolling 32-candle window.

Per-candle normalized fields include:

```text
open vs previous close
high vs close
low vs close
close vs previous close
log volume
log trade count
candle body
```

Additional public perp context:

```text
open interest
mark-vs-candle price
oracle-vs-candle price
premium
day notional volume
day base volume
```

Virtual position state:

```text
side: flat/long/short
position age
unrealized PnL estimate
fresh-frame flag
```

These are raw/normalized market-state inputs. They are not hardcoded buy/sell rules.

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

Current sizing is fixed at `$1000` notional per entry so the agent learns trading technique first.

## Current algorithm

The MVP uses masked Double Q-learning with linear function approximation.

Reasons:

- easy to run on phone CPU
- no native ML dependency needed
- off-policy replay reuses scarce live experience
- debuggable Q-values

## Transition semantics

Replay rows are causal:

```text
(state_t, action_t, reward_{t+1}, next_state_{t+1}, next_mask_{t+1}, done)
```

Between-candle mark-to-market movement is assigned to legal interval actions:

```text
HOLD while positioned
WAIT while flat
```

The engine acts only once per fresh candle timestamp.

## Reward

```text
reward_t = executable_virtual_equity_t - executable_virtual_equity_t_minus_1
```

Virtual equity is gross simulation PnL only:

```text
realized PnL
unrealized PnL at current candle/mark price
```

The Candle RL app intentionally applies no fee, funding, or cost model. Because it no longer consumes L2 depth, it also does not model book-walking slippage.

## Upgrade path

Before upgrading the model, add deterministic Android tests and an offline candle replay harness. Then consider replacing the linear approximator with:

```text
input → Dense(128) → ReLU → Dense(128) → ReLU → dueling Q head → 5 actions
```

The action-mask interface should remain identical.
