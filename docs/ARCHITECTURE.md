# Architecture

HL Candle RL is an Android-native virtual-only RL app. It now uses OHLCV candles plus public HyperLiquid perp context, not L2 order books.

Status: source-patched MVP. Android build/runtime verification still needs Android Studio or a configured JDK.

## Runtime flow

```text
Android foreground service
  → selected market label / HyperLiquid coin
  → HyperLiquid public /info fee + perp-context lookup
  → HyperLiquid candle WebSocket subscription
  → latest in-memory Candle
  → fresh-candle timestamp decision gate
  → OhlcvFeatureBuilder
  → action mask from VirtualPerpBroker
  → MaskedDoubleQLearner selects action
  → VirtualPerpBroker simulates fixed-$1000 virtual position
  → causal reward/transition appended to ReplayBuffer
  → learner trains from replay sample
  → optional offline trainer replays downloaded candles chronologically
  → UI state updated
```

## Modules

### `exchange/HyperLiquidCandleWsClient.kt`

Connects to `wss://api.hyperliquid.xyz/ws` and subscribes to public candles:

```json
{"type":"candle","coin":"<coin>","interval":"1m"}
```

It parses open/high/low/close/volume/trade-count fields and reconnects/resubscribes after socket failure.

### `exchange/HyperLiquidInfoClient.kt`

Loads public selected-market data from HyperLiquid `/info`:

- `userFees` for base cross/add fee rates using the zero-address baseline.
- `metaAndAssetCtxs` for funding, open interest, mark price, oracle price, premium, and daily volume fields.

Training refuses to start if selected-market costs/context cannot be resolved.

### `features/OhlcvFeatureBuilder.kt`

Builds a fixed-width observation vector from:

- 32-candle OHLCV window
- candle trade count, if present
- public perp context
- virtual position state

It does not encode technical-indicator strategy rules. Derived values are normalization of raw candle/context fields, not entry/exit rules.

### `broker/VirtualPerpBroker.kt`

Maintains one virtual perp position. Each entry uses fixed `$1000` notional:

```text
qty = 1000 / execution_price
```

Since the app no longer consumes L2 depth, it does not simulate book-walking. It uses the current candle/mark price proxy and applies loaded HyperLiquid fee/funding inputs.

### `rl/MaskedDoubleQLearner.kt`

Masked Double Q-learning with linear function approximation, online replay, and epsilon-greedy exploration.

### `engine/RlEngine.kt`

Owns market selection, per-market persistence, cost/context loading, candle stream, feature generation, virtual broker, replay, learner, offline candle download/training, downloaded-data deletion, and UI state.

Transitions are causal:

- entry/exit immediate execution cost is assigned to the selected entry/exit action
- between-candle movement is assigned to legal `HOLD`/`WAIT` interval actions
- the engine acts only once per fresh candle timestamp

### `engine/RlForegroundService.kt`

Keeps the learner running in the background/screen-off using an Android foreground service. Owns policy checkpointing and reset lifecycle handling.

### `MainActivity.kt`

Jetpack Compose dashboard with market selector, Start/Stop/Reset controls, offline Download/Train/Delete controls, candle/context/PnL/reward/replay/Q-value display.

## Important design decisions

- Default market is BTC.
- A curated high-liquidity market list can be selected before starting.
- The model learns from candles + public perp state, not L2.
- Fixed `$1000` notional is retained so the model learns trading technique first, not sizing.
- Invalid actions are masked before selection.
- Offline training runs chronologically on downloaded `candleSnapshot` data stored on the phone.
- Downloaded candle data can be deleted without resetting policy/replay.
- The system contains no real-order pathway.
