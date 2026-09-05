# Persistence

HL Phone RL persists learning state in Android app-private storage under:

```text
filesDir/learning_state/
```

## Files

```text
policy.json   # current Double-Q weights, epsilon, update count
replay.jsonl  # durable replay transitions, one JSON object per line
```

## Guarantees

- Every transition is appended to `replay.jsonl` immediately after it is produced.
- Policy weights are checkpointed every 100 learner updates.
- Replay is compacted every 500 updates and on clean Stop/service destruction.
- On service start, policy and replay are restored before the learner resumes.

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

## Why JSONL first

JSONL is simple and crash-tolerant: if the phone dies mid-write, previous lines remain readable and malformed tail lines are ignored on restore. Once the data model stabilizes, this can move to Room SQLite.
