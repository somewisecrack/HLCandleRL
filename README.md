# HL Candle RL

**HL Candle RL** is an Android-native, phone-running, **virtual-only** reinforcement-learning trading agent for HyperLiquid perps.

Default market: `xyz:SP500`. Selectable markets include BTC, ETH, SOL, HYPE, XRP, DOGE, LINK, WTI, and Gold.

The app now learns from:

- HyperLiquid public OHLCV candles from the WebSocket `candle` subscription
- HyperLiquid public perp market context from `/info` / `metaAndAssetCtxs`
- virtual position/account state

It does **not** use L2 order books, technical-indicator rules, hardcoded entry/exit logic, private keys, or real orders.

> Status: source-patched MVP. It needs Android Studio/JDK build verification on a device/emulator.

---

## Goals

- Run entirely on an Android phone.
- Learn by virtual trading directly.
- Use compact public market-state data suitable for mobile.
- Default to `xyz:SP500`, while allowing market selection before starting.
- Keep policy/replay isolated per selected coin.
- Keep the system safe: no private keys, no wallet signing, no real exchange orders.
- Make every action auditable: state → valid action mask → action → virtual fill → reward.

---

## Data used

### 1. OHLCV candles

The app subscribes to HyperLiquid public WebSocket candles:

```json
{
  "method": "subscribe",
  "subscription": {
    "type": "candle",
    "coin": "xyz:SP500",
    "interval": "1m"
  }
}
```

Parsed candle fields:

```text
open time
close time
open
high
low
close
volume
trade count, if present
```

### 2. Public perp context

The app also loads public selected-market context from HyperLiquid `/info`:

```text
funding rate
open interest
mark price
oracle price
premium
day notional volume
day base volume
```

These are market-state inputs, not strategy rules.

### 3. Virtual position state

The model also sees its own virtual state:

```text
flat/long/short
position age
unrealized PnL estimate
fresh frame flag
```

---

## Data not used

- No L2 order book
- No bid/ask depth
- No candle strategy rules
- No RSI/MACD/Bollinger/etc.
- No handcrafted momentum/volume strategy
- No private account data
- No private keys
- No real orders

---

## Current MVP

The app contains:

- HyperLiquid public WebSocket client for `candle` data with reconnect/resubscribe handling.
- HyperLiquid public `/info` loader for fees, funding, and perp context.
- OHLCV-window feature builder using a 32-candle rolling window.
- Virtual perp broker using fixed `$1000` notional per entry.
- Masked action space.
- Causal replay transitions aligned to future candle frames.
- Phone-friendly masked Double-Q learner with linear function approximation.
- Market-specific policy/replay persistence.
- Android foreground service for background/screen-off operation.
- Reset button to archive active replay/policy and clear runtime state.
- Compose UI showing market, candle, context, PnL, reward, replay, epsilon, and Q-values.

---

## Action space

```text
WAIT
ENTER_LONG
ENTER_SHORT
HOLD
EXIT
```

Valid actions:

```text
Flat:    WAIT, ENTER_LONG, ENTER_SHORT
Holding: HOLD, EXIT
```

Trade size is intentionally fixed for now:

```text
notional_usd = 1000
qty = 1000 / execution_price
```

The agent is currently learning direction/timing/holding technique, not position sizing.

---

## Execution and reward

Because this app no longer consumes L2 depth, it does not pretend to book-walk depth. Virtual entries/exits execute at the current candle/mark price proxy and apply actual public HyperLiquid fee/funding inputs loaded from `/info`.

Reward is executable virtual equity change:

```text
reward = current_virtual_equity - previous_virtual_equity
```

The transition logic is causal:

- immediate entry/exit fee cost is assigned to the selected entry/exit action
- between-candle mark-to-market movement is assigned to legal interval actions:
  - `HOLD` while positioned
  - `WAIT` while flat
- no replay row should train an action that is illegal in its stored state

---

## Persistence

Market-specific app-private paths:

```text
filesDir/learning_state/<safe_coin>/policy.json
filesDir/learning_state/<safe_coin>/replay.jsonl
```

Examples:

```text
learning_state/xyz_SP500/policy.json
learning_state/BTC/replay.jsonl
```

Replay appends immediately. Policy checkpoints periodically and on service stop. Reset archives active market files and clears in-memory state.

---

## Safety

- Virtual broker only.
- No private key handling.
- No wallet connection.
- No signing.
- No `/exchange` endpoint usage.
- No real orders.

---

## Build and run

Open this project in Android Studio and run the `app` configuration on an emulator or device.

Command-line Gradle builds require a configured JDK. This shell previously could not run Gradle because Java was unavailable.

---

## Roadmap

- Add Android unit tests for candle parsing, reward alignment, reset, and broker accounting.
- Add bounded replay loading instead of reading full JSONL into memory.
- Add offline candle replay/backtest mode.
- Add baseline policies: always-wait, random-valid, frozen-policy.
- Upgrade from linear Double-Q to a small neural DQN/TFLite model if offline validation justifies it.
- Add optional position sizing only after entry/exit technique works.
