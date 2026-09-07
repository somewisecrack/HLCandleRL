# Persistence

HL Candle RL persists learning state in Android app-private, market-specific storage:

```text
filesDir/learning_state/<safe_coin>/
```

Examples:

```text
filesDir/learning_state/BTC/
filesDir/learning_state/ETH/
filesDir/learning_state/xyz_SP500/
```

## Files

```text
policy.json       # Double-Q weights, epsilon, update count for that market
replay.jsonl      # durable replay transitions for that market, one JSON object per line
candles_1m.jsonl  # downloaded offline-training candles for that market
```

## Guarantees

- Every transition is appended to the selected market's `replay.jsonl` immediately after it is produced.
- Policy weights are checkpointed every 100 learner updates.
- Replay is compacted every 500 updates and on clean Stop/service destruction.
- On service start, the selected market's policy and replay are restored before the learner resumes.
- Switching markets while stopped repoints policy/replay/candle files and does not reuse another market's state.
- Downloaded candle data can be deleted from the UI without deleting policy/replay.

## Reset behavior

Reset is a hard reset for the currently selected market:

- stops the learner
- closes the candle WebSocket
- archives active `policy.json` and `replay.jsonl` in the market directory
- clears in-memory broker position/PnL
- clears replay buffer
- resets learner weights, epsilon, and update count
- clears latest candle/context/equity/transition state
- resets feature-builder candle window
- clears legacy SharedPreferences policy fallback
- suppresses the foreground service's normal on-destroy save so reset files are not immediately recreated

The app remains stopped after reset. Press Start to begin a new run.

## Downloaded data deletion

The **Delete downloaded candle data** button removes only:

```text
candles_1m.jsonl
```

It does not delete:

```text
policy.json
replay.jsonl
```

Use Reset if you want to archive policy/replay too.

## What survives

Survives:

- pressing Stop
- backgrounding the app
- Android killing the app process after recent checkpoints
- phone reboot after saved checkpoints

Does not survive:

- uninstalling the app
- clearing app storage/data
- deleting `filesDir/learning_state`

## Known limitation

JSONL restore currently reads the active market file directly. Before very long unattended runs, this should be replaced with bounded tail loading or Room SQLite.
