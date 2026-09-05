# HL Phone RL

**HL Phone RL** is an Android-native prototype for a **fully on-phone**, **virtual-only** reinforcement-learning trading agent for HyperLiquid perps.

The initial target market is HyperLiquid `xyz:SP500`. The agent uses **only public L2 order-book data**. It does not use candles, technical indicators, momentum, volume bars, private keys, or real orders.

> Status: early MVP. It builds and runs, connects to HyperLiquid L2 WebSocket when Android networking is available, simulates virtual fills, and trains a lightweight masked Double-Q learner online from virtual PnL rewards.

---

## Goals

- Run entirely on an Android phone.
- Learn by virtual trading directly.
- Use only HyperLiquid public order-book data.
- Keep the system safe: no private keys, no real exchange orders.
- Make every action auditable: state → valid action mask → action → virtual fill → reward.

---

## Non-goals

This app intentionally does **not** do the following:

- No real trading.
- No private-key handling.
- No broker/exchange order placement.
- No candle-based strategy.
- No momentum/volume handcrafted strategy.
- No cloud backend.
- No server-side model training.

---

## Current MVP

The current app contains:

- HyperLiquid public WebSocket client for `l2Book`.
- Top-5 L2 order-book feature builder.
- Virtual perp broker.
- L2 book-walking virtual marketable fills.
- Masked discrete action space.
- Online replay buffer.
- Phone-friendly masked Double-Q learner with linear function approximation.
- Local policy checkpoint save/load via app-private `policy.json`.
- Durable replay persistence via app-private `replay.jsonl`; each transition is appended immediately and compacted on clean stop.
- Android foreground service for screen-off/background operation.
- Persistent notification with live PnL/action and a Stop action.
- Jetpack Compose UI showing:
  - Start / Stop
  - connection status
  - current market
  - current policy description
  - mid price
  - spread
  - virtual position
  - last action
  - reward
  - live equity
  - realized PnL
  - replay size
  - update count
  - epsilon
  - Q-values

---

## Architecture

```text
Android app
  ├── HyperLiquidWsClient
  │     └── public l2Book WebSocket
  ├── L2FeatureBuilder
  │     └── converts book snapshots into normalized L2-only features
  ├── VirtualPerpBroker
  │     └── simulates marketable long/short/exit fills through L2 depth
  ├── MaskedDoubleQLearner
  │     └── learns action values from virtual trading rewards
  ├── ReplayBuffer
  │     └── stores recent online transitions in memory
  └── Compose UI
        └── displays live state, policy, PnL, rewards, and Q-values
```

---

## Data source

The app subscribes to HyperLiquid public WebSocket:

```json
{
  "method": "subscribe",
  "subscription": {
    "type": "l2Book",
    "coin": "xyz:SP500"
  }
}
```

The app uses only the L2 book payload:

```text
bids: price, size, order count
asks: price, size, order count
```

---

## Observation features

The MVP uses top-5 book levels.

Feature groups:

- spread in basis points
- one-step mid-price return
- aggregate depth imbalance
- change in imbalance
- microprice premium
- total displayed depth
- per-level bid price distance from mid
- per-level bid size
- per-level bid order count
- per-level ask price distance from mid
- per-level ask size
- per-level ask order count
- current virtual position side
- position age
- unrealized PnL estimate
- fresh-book flag

No candle features are used.

---

## Action space

The RL action space is intentionally small and masked.

```text
WAIT
ENTER_LONG
ENTER_SHORT
HOLD
EXIT
```

Valid actions depend on virtual position state:

```text
Flat:
  valid:   WAIT, ENTER_LONG, ENTER_SHORT
  invalid: HOLD, EXIT

Holding:
  valid:   HOLD, EXIT
  invalid: WAIT, ENTER_LONG, ENTER_SHORT
```

This follows the same safety principle used in OptionScalper: operational constraints should be enforced by masks, not learned through punishment.

---

## Reward

Reward is based on change in executable virtual equity:

```text
reward_t = virtual_equity_t - virtual_equity_t-1
```

Virtual equity includes:

- realized cash PnL
- mark-to-executable liquidation value using current bid/ask
- taker fees
- simulated slippage from walking the visible L2 book

Marketable virtual orders are simulated as:

```text
ENTER_LONG  = buy through asks
ENTER_SHORT = sell through bids
EXIT_LONG   = sell through bids
EXIT_SHORT  = buy through asks
```

---

## RL method

The MVP uses a lightweight on-device learner:

```text
Masked Double Q-learning
linear function approximation
online replay
epsilon-greedy exploration
```

This is intentionally simpler than a deep neural DQN so it can run immediately on-device without PyTorch/TensorFlow dependencies.

The interface is designed so the learner can later be replaced with:

```text
small Dueling Double DQN
ONNX Runtime Mobile
TensorFlow Lite
custom Kotlin MLP
```

without changing the exchange, feature, broker, or UI layers.

---

## Project layout

```text
app/src/main/java/com/example/hlphonerl/
  MainActivity.kt
  data/Models.kt
  exchange/HyperLiquidWsClient.kt
  features/L2FeatureBuilder.kt
  broker/VirtualPerpBroker.kt
  rl/MaskedDoubleQLearner.kt
  engine/RlEngine.kt
```

---

## Build and run

### Android Studio

1. Open Android Studio.
2. File → Open.
3. Select this directory.
4. Let Gradle sync finish.
5. Select the `app` run configuration.
6. Select an emulator or physical Android phone.
7. Click Run.

### Command line

If Android Studio's bundled JBR is available:

```bash
cd /Users/rahulgirishkumar/PROJECTS/HLPhoneRL
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ~/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin/gradle :app:assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## Emulator networking note

If the UI shows:

```text
Unable to resolve host "api.hyperliquid.xyz"
```

then the Android emulator has a DNS/networking issue, not an app logic issue.

Try:

- open Chrome inside the emulator and visit `https://api.hyperliquid.xyz/info`
- cold boot the emulator
- switch emulator DNS/network
- run on a physical Android phone

---

## Safety

This app is virtual-only by design.

Current safety properties:

- No wallet/private key fields.
- No signing logic.
- No exchange order endpoint usage.
- No real-order code path.
- Only public HyperLiquid L2 WebSocket data.
- Virtual broker only mutates local in-memory state.

---

## Roadmap

Near-term:

- Add battery-aware pause controls.
- Add stale-book masks for entries.
- Replace JSONL replay with Room SQLite once schema stabilizes.
- Move policy checkpoints from SharedPreferences to versioned files/Room metadata.
- Add trade ledger screen.
- Add PnL chart.
- Add settings for coin, notional, max hold, fee, epsilon.
- Add daily loss/trade-count masks.

Research/learning:

- Add shadow actors: always-wait, random-valid, frozen policy, learner policy.
- Add policy promotion gates.
- Add offline replay from captured L2 snapshots.
- Replace linear approximator with small masked Dueling Double DQN.
- Add model sanity checks: action collapse, Q-value explosion, reward permutation control.

---

## Disclaimer

This is experimental research software for virtual trading only. It is not financial advice and does not place real trades.
