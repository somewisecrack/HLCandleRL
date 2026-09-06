# Persistence

HL Phone RL persists learning state in Android app-private storage under market-specific directories:

```text
filesDir/learning_state/<safe_coin>/
```

Examples:

```text
filesDir/learning_state/xyz_SP500/
filesDir/learning_state/BTC/
filesDir/learning_state/ETH/
```

## Files

```text
policy.json   # current Double-Q weights, epsilon, update count for that market
replay.jsonl  # durable replay transitions for that market, one JSON object per line
```

## Guarantees

- Every transition is appended to the active market's `replay.jsonl` immediately after it is produced.
- Policy weights are checkpointed every 100 learner updates.
- Replay is compacted every 500 updates and on clean Stop/service destruction.
- On service start, the selected market's policy and replay are restored before the learner resumes.
- Switching markets while stopped repoints policy/replay files and does not reuse another market's replay.

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

## Reset behavior

The Reset button/service action is intended to be a hard learning reset for the currently selected market:

- stops the learner
- closes the WebSocket
- archives active `policy.json` and `replay.jsonl` in the market directory
- clears in-memory broker position/PnL
- clears replay buffer
- resets learner weights, epsilon, and update count
- clears latest book/equity/transition state
- resets feature-builder temporal history
- clears legacy SharedPreferences policy fallback
- suppresses the foreground service's normal on-destroy save so reset files are not immediately recreated

The app remains stopped after reset. Press Start to begin a new run.

## Why JSONL first

JSONL is simple and crash-tolerant: if the phone dies mid-write, previous lines remain readable and malformed tail lines are ignored on restore. Once the data model stabilizes, this should move to Room SQLite or another bounded storage layer; current JSONL restore still reads the file directly and should be capped before very long unattended runs.
