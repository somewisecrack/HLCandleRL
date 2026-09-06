# Architecture

HL Phone RL is intentionally split into small layers so the RL learner can evolve without changing the exchange or virtual-broker code.

Status: source-patched MVP. It is virtual-only and uses public HyperLiquid L2 data, but Android builds must be verified in Android Studio/JDK because the current shell has no Java runtime.

## Runtime flow

The learner now runs inside `RlForegroundService`, so it can continue while the app is backgrounded or the screen is off, subject to normal Android foreground-service limits.

```text
Android foreground service
  → selected market label / HyperLiquid coin
  → HyperLiquid public /info fee + funding lookup
  → HyperLiquid L2 WebSocket
  → latest in-memory L2Book
  → fresh-exchange-timestamp decision gate
  → L2FeatureBuilder
  → action mask from VirtualPerpBroker
  → MaskedDoubleQLearner selects action
  → VirtualPerpBroker simulates fill/PnL
  → causal reward/transition appended to ReplayBuffer
  → learner trains from replay sample
  → UI state updated
```

## Modules

### `exchange/HyperLiquidWsClient.kt`

Connects to `wss://api.hyperliquid.xyz/ws` and subscribes to public `l2Book` for the configured coin. It rejects wrong-coin, missing-timestamp, malformed, empty, non-positive, or crossed books, and reconnects/resubscribes after socket failure.

### `exchange/HyperLiquidInfoClient.kt`

Loads public cost inputs from HyperLiquid `/info`:

- `type: userFees` for base cross/add fee rates using the zero address baseline.
- `type: metaAndAssetCtxs` for selected-market funding and asset metadata.

Training refuses to start if selected-market cost data cannot be resolved.

### `features/L2FeatureBuilder.kt`

Builds a fixed-width observation vector from top-5 L2 levels and virtual position state. It does not use candles or handcrafted technical indicators. It has reset/patch helpers so temporal fields are not destroyed by rebuilding features twice on the same book.

### `broker/VirtualPerpBroker.kt`

Maintains one virtual perp position and simulates marketable fills by walking the current visible L2 book.

### `rl/MaskedDoubleQLearner.kt`

Implements the current MVP learner: masked Double Q-learning with linear function approximation, online replay, and epsilon-greedy exploration.

### `engine/RlEngine.kt`

Owns the runtime loop and ties market selection, cost loading, WebSocket, features, broker, learner, replay, and UI state together. It stores one delayed legal interval transition per fresh book update and uses market-specific persistence.

### `engine/RlForegroundService.kt`

Android foreground service that owns long-running operation, keeps a persistent notification visible, and exposes Stop/Reset actions. Reset suppresses service-destroy checkpointing and clears legacy SharedPreferences policy fallback.

### `MainActivity.kt`

Jetpack Compose dashboard with market selector, Start/Stop/Reset controls, L2 update count, book age, cost source, PnL/reward, and Q-values.

## Important design decisions

- Default market is `SP500 -> xyz:SP500`; user can select another market before starting.
- The policy acts only on fresh exchange L2 timestamps; the 1-second loop is just a polling cadence.
- Invalid actions are masked before selection.
- Reward is based on executable virtual equity, not mid-price fantasy.
- Entry/exit immediate cost is assigned to the selected action; between-book movement is assigned to legal `HOLD`/`WAIT` interval actions.
- Fixed trade size remains `$1000` notional per entry; quantity is derived from the book-walked fill price.
- The system contains no real-order pathway.
