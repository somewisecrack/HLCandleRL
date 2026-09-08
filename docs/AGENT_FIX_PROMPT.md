# Prompt for Next Agent: Comprehensively Fix HLCandleRL Crashes and Slowness

You are taking over a broken Android app project. The user is frustrated because the app is slow and keeps crashing, especially around offline training and ticker switching. Do not assume the previous implementation is correct just because tests pass. Your job is to reproduce the real problems on emulator/device, simplify the architecture where needed, and make the app stable and usable.

## Project

```text
Local path: /Users/rahulgirishkumar/PROJECTS/HLCandleRL
GitHub: https://github.com/somewisecrack/HLCandleRL
App name: HL Candle RL
Package: com.example.hlcandlerl
Current main commit when handed off: 4e0fb82 or later
```

## User intent

Build an Android-native, phone-running, virtual-only HyperLiquid RL app that:

- uses OHLCV candle data, not L2 order book data;
- can download historical candles;
- can train offline on the phone itself;
- can resume live virtual learning afterward;
- supports a curated set of liquid HyperLiquid tickers;
- never places real trades;
- has no private keys/wallet signing/orders;
- currently has no cost/fee/funding model at all;
- remains responsive and does not crash.

## Critical constraints

### Virtual-only safety

The app must not contain or add:

```text
private keys
wallet signing
real order placement
HyperLiquid /exchange calls
private account data
cancel/modify/place order flows
```

Only public endpoints are allowed:

```text
https://api.hyperliquid.xyz/info
wss://api.hyperliquid.xyz/ws
```

### No cost model in Candle RL

The user explicitly asked to remove costs completely from the mobile Candle RL app.

Do not add:

```text
userFees
fee deduction
funding deduction
cost refusal gates
cost/funding model features
cost UI rows
```

It is okay to display/consume non-cost public context such as:

```text
markPx
oraclePx
openInterest
premium
dayNtlVlm
dayBaseVlm
```

But do not treat funding as a cost or feature unless the user later explicitly asks.

### Execution price

Virtual trade execution must use candle close proxy:

```kotlin
MarketFrame.price == candle.close
```

Do not let stale `markPx` override historical candle prices during offline replay.

### Fixed trade sizing

Keep fixed notional sizing:

```text
qty = 1000 / execution_price
```

Do not add dynamic sizing yet.

## Important warning

The current code may have many tests, but the app is reportedly still slow and crashing. Treat tests as useful but insufficient. Your first job is real runtime reproduction and profiling.

## Current suspected failure modes

Investigate these aggressively:

1. **Offline training is too heavy for phone CPU/memory.**
   - Current transition rows are huge because each row stores full state and nextState arrays as JSON.
   - Replay files can reach hundreds of MB per market.
   - Even if compaction streams output, JSON serialization/parsing may still be too slow/heavy.

2. **Too many training updates per offline run.**
   - The app may train after nearly every transition.
   - Five rounds over thousands of candles may trigger many replay samples and UI updates.
   - This can lock CPU, heat phone, or trigger ANR/OOM.

3. **Too many StateFlow/UI updates during offline training.**
   - Updating UI every 250 candles may still be too frequent depending training workload.
   - Plain `_state.value = _state.value.copy(...)` from multiple coroutines may race/lost-update.
   - Prefer `MutableStateFlow.update {}` consistently.

4. **Singleton engine lifecycle issues.**
   - `AppRuntime.engine` is a process-wide singleton.
   - `attachPersistenceDir()` is called from both Activity and Service.
   - Activity/service lifecycle may mutate engine state concurrently.
   - Starting/stopping service while offline training may leave jobs alive or inconsistent.

5. **Policy/replay incompatibility after feature-size changes.**
   - Old `policy.json` may have different inputDim.
   - Old `replay.jsonl` may have incompatible rows.
   - App must never crash on old persisted files; it should skip/archive/reset gracefully.

6. **Market switching may still touch disk or large files.**
   - Ensure switching is instant and never parses huge replay/candle files on the main thread.
   - Consider not restoring replay on market switch at all; only restore small policy metadata until Start/Train.

7. **Downloaded candle count smaller than expected.**
   - One `candleSnapshot` request may not return full 7 days.
   - Do not block/crash if fewer candles are returned.
   - Add clear UI: `Downloaded N candles`, not implied full 7d.

8. **Replay persistence design is probably wrong for phone.**
   - JSONL with full float arrays is likely too large.
   - Consider bounded, compressed, or disabled offline replay persistence.
   - Simpler short-term fix: offline training should not persist every generated transition; persist only final policy and a compact summary.

## Your mission

### Phase 1: Reproduce and observe

Use Android Studio/emulator or adb. Test on emulator and, if available, a physical phone.

Build:

```bash
cd /Users/rahulgirishkumar/PROJECTS/HLCandleRL
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean assembleDebug --stacktrace
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Collect logs:

```bash
adb logcat -c
adb logcat | grep -iE "hlcandlerl|AndroidRuntime|OutOfMemory|ANR|FATAL|StrictMode|Choreographer"
```

Manual reproduction matrix:

1. Fresh install / clear app data.
2. Launch app.
3. Switch tickers rapidly while stopped.
4. Select BTC.
5. Tap Download 7d.
6. Watch progress and logs.
7. Tap Offline train.
8. Let it run to completion if possible.
9. Watch memory/CPU responsiveness.
10. Tap Delete downloaded data.
11. Tap Reset.
12. Relaunch.
13. Repeat for ETH, SOL, SP500, WTI, NVDA.
14. Start live learner for BTC and let it run for 5–10 minutes.
15. Stop and relaunch.

Use these commands if helpful:

```bash
adb shell dumpsys meminfo com.example.hlcandlerl
adb shell top -o PID,CPU,RES,ARGS | grep hlcandlerl
adb shell run-as com.example.hlcandlerl ls -R files/learning_state
adb shell run-as com.example.hlcandlerl du -h files/learning_state
```

Document exact crash stack traces before fixing.

### Phase 2: Stabilize before optimizing features

Prioritize stability over sophistication.

The app should never crash because of offline training. If needed, make offline training smaller/slower but safe.

Recommended fixes to consider:

#### A. Add cancellation/stop for offline training

Add a visible button:

```text
Stop offline training
```

Training must check cancellation frequently:

```kotlin
ensureActive()
```

and always clear busy state in `finally`.

#### B. Reduce default offline workload

Current 5 rounds may be too much. Change default to safer values:

```text
Download recent candles: 1d or 3d default, not 7d
Offline train: 1 round default, with option to run more
Batch training every N candles, not every candle
UI update every 1000 candles or 250ms throttled, whichever later
```

Possible UI buttons:

```text
Download 1d
Download 3d
Download 7d
Train 1 round
Train +1 round
Stop training
Delete downloaded data
```

#### C. Do not persist huge offline replay JSONL

Strongly consider this simpler policy:

- During offline training, keep replay in memory only.
- Persist final `policy.json`.
- Persist compact `offline_summary.json`.
- Do not append offline transitions to `replay.jsonl`.
- Live learning can still append replay if needed, but bounded.

If replay must persist, store only a small bounded sample, e.g. 2,000 rows, not 20,000, and avoid full-precision JSON.

#### D. Bound disk usage per market

Add hard disk caps:

```text
candles_1m.jsonl <= reasonable size
replay.jsonl <= maybe 25 MB or disabled for offline
policy.json only small
```

Show storage usage in UI:

```text
Stored candles: N
Storage: X MB
```

#### E. Atomic state updates

Replace all multi-threaded read-modify-write state mutations:

```kotlin
_state.value = _state.value.copy(...)
```

with:

```kotlin
_state.update { it.copy(...) }
```

where concurrent updates are possible.

#### F. Avoid Activity/Service fighting over engine lifecycle

Audit:

```text
MainActivity.kt
RlForegroundService.kt
AppRuntime.kt
RlEngine.kt
```

Make sure:

- opening UI does not reset active training;
- service start does not attach/restore huge state on main thread;
- Activity and Service do not concurrently start/stop mutate the same fields unsafely;
- market switching is disabled while service/training jobs are active;
- no disk reads happen on the UI thread.

#### G. Make offline trainer deterministic and observable

Add progress fields:

```text
phase
round
processed candles
total candles
updates
replay size
last action
action counts
last reward
elapsed time
estimated remaining time
```

Throttle UI updates.

#### H. Handle old persisted data robustly

On app startup and market switch:

- if `policy.json` inputDim mismatches current feature size, archive it and reset policy;
- if replay rows mismatch, skip them;
- if replay file is too large, archive or truncate safely;
- never crash on malformed JSON.

### Phase 3: Add/adjust tests after runtime fix

Existing tests are in:

```text
app/src/test/java/com/example/hlcandlerl/
```

Run:

```bash
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

Keep tests, but update them to reflect real fixes.

Add tests for:

1. Offline training cancellation clears `trainActive` and `offlineJob`.
2. Offline training with 5k candles does not write huge replay files.
3. Policy persists after offline training even when replay persistence is disabled.
4. Old policy inputDim mismatch archives/resets without crash.
5. Replay file over size cap is truncated/archived/skipped.
6. Download failure always clears busy flag.
7. Market switching does not call large replay restore synchronously.
8. `deleteDownloadedData` works while no training is active and refuses safely while active.

### Phase 4: Acceptance criteria

Do not call the work done until all of these pass:

#### Build/tests

```text
./gradlew clean testDebugUnitTest assembleDebug
```

must pass.

#### Runtime

On emulator/device:

- app launches cleanly;
- ticker dropdown opens quickly;
- 20 rapid market switches while stopped do not freeze/crash;
- Download 1d/3d/7d shows progress and completes/fails gracefully;
- Offline train completes at least 1 full round on BTC without crash;
- Stop training works within 2 seconds;
- UI remains responsive during training;
- Delete downloaded data works;
- Reset works;
- Relaunch after training/reset works;
- Live learner start/stop works for at least BTC and ETH.

#### Storage/memory

- app data should not balloon uncontrollably;
- offline training should not create hundreds of MB of replay JSON;
- memory should not climb until OOM;
- no ANR;
- no fatal exceptions in logcat.

#### Safety/no-cost

Verify:

```bash
rg "userFees|fee|Fee|funding|Funding|cost|Cost|crossFee|addFee|/exchange|privateKey|mnemonic|secret|wallet" app/src/main/java
```

Any hits must be inspected. There should be no real trading path and no cost model.

## Likely files to change

```text
app/src/main/java/com/example/hlcandlerl/engine/RlEngine.kt
app/src/main/java/com/example/hlcandlerl/engine/RlForegroundService.kt
app/src/main/java/com/example/hlcandlerl/MainActivity.kt
app/src/main/java/com/example/hlcandlerl/rl/MaskedDoubleQLearner.kt
app/src/main/java/com/example/hlcandlerl/exchange/HyperLiquidInfoClient.kt
app/src/main/java/com/example/hlcandlerl/data/Models.kt
app/src/test/java/com/example/hlcandlerl/...
```

## Suggested simpler design if current architecture remains unstable

If the current code is still crash-prone, simplify aggressively:

### Offline mode

```text
Read candles
Train in memory only
Save policy.json
Save offline_summary.json
Do not write replay.jsonl during offline training
Limit rounds to user-triggered +1 round
Add Stop Training
Throttle progress updates
```

### Live mode

```text
Use WebSocket candle stream
Use small replay buffer
Persist policy periodically
Optionally persist only last small replay sample
```

### Persistence

```text
policy.json            small, always persisted
candles_1m.jsonl       downloaded data, deletable
replay_live.jsonl      optional, capped small
summary.json           compact metrics
```

Avoid storing massive full-state transition JSON from offline training.

## Report back format

Write a final report to:

```text
docs/AGENT_FIX_REPORT.md
```

Use this format:

```text
# HLCandleRL Fix Report

Commit(s): <sha>
Branch: <branch>
Build/tests: pass/fail
Device/emulator tested: <details>

## Reproduced issues

- issue, steps, stack trace/log excerpt

## Fixes made

- file/function, change, why

## Runtime verification

- market switching
- download
- offline train
- stop training
- delete data
- reset
- live start/stop
- storage/memory observations

## Remaining risks

- bullets

## User instructions

- exactly how to install/run/test
```

## Tone/priority

The user is angry because the app feels unusable. Do not defend prior work. Do not only add tests. Reproduce the crashes, fix the real runtime behavior, and make the phone app boringly stable.
