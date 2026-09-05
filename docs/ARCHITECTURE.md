# Architecture

HL Phone RL is intentionally split into small layers so the RL learner can evolve without changing the exchange or virtual-broker code.

## Runtime flow

The learner now runs inside `RlForegroundService`, so it can continue while the app is backgrounded or the screen is off, subject to normal Android foreground-service limits.

```text
Android foreground service
  → HyperLiquid L2 WebSocket
  → latest in-memory L2Book
  → 1-second decision tick
  → L2FeatureBuilder
  → action mask from VirtualPerpBroker
  → MaskedDoubleQLearner selects action
  → VirtualPerpBroker simulates fill/PnL
  → reward/transition appended to ReplayBuffer
  → learner trains from replay sample
  → UI state updated
```

## Modules

### `exchange/HyperLiquidWsClient.kt`

Connects to `wss://api.hyperliquid.xyz/ws` and subscribes to public `l2Book` for the configured coin.

### `features/L2FeatureBuilder.kt`

Builds a fixed-width observation vector from top-5 L2 levels and virtual position state. It does not use candles or handcrafted technical indicators.

### `broker/VirtualPerpBroker.kt`

Maintains one virtual perp position and simulates marketable fills by walking the current visible L2 book.

### `rl/MaskedDoubleQLearner.kt`

Implements the current MVP learner: masked Double Q-learning with linear function approximation, online replay, and epsilon-greedy exploration.

### `engine/RlEngine.kt`

Owns the runtime loop and ties WebSocket, features, broker, learner, replay, and UI state together.

### `engine/RlForegroundService.kt`

Android foreground service that owns long-running operation, keeps a persistent notification visible, and exposes a Stop action.

### `MainActivity.kt`

Jetpack Compose dashboard and Start/Stop controls for the foreground service.

## Important design decisions

- The policy acts at most once per second, not on every WebSocket update.
- Invalid actions are masked before selection.
- Reward is based on executable virtual equity, not mid-price fantasy.
- The system contains no real-order pathway.
