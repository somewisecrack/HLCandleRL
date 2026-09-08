# HLCandleRL Fix Report

Commit(s): `fa66f6b` (offline training) and `7daeec6` (service/live-loop hardening) on top of `8090ccf`
Branch: `fix/offline-training-stability`
Build/tests: **pass** — `./gradlew clean testDebugUnitTest assembleDebug --stacktrace`, 54 unit tests, 0 failures
Device/emulator tested: **Medium_Phone_API_36.1 emulator, Android 16 / API 36.1, 1080x2400**, fresh
install (package uninstalled first, so no carried-over app data). The attached physical Pixel 9 Pro
stayed locked, so it was not driven.

## Reproduced issues

All reproduced on the pre-fix build before changing anything.

**1. Offline training wrote 152 MB for one market.** Download 7d (BTC, 5134 candles) then Offline
train (5 rounds):

```text
$ adb shell run-as com.example.hlcandlerl ls -l files/learning_state/BTC/
-rw-------  ...    901041  candles_1m.jsonl
-rw-------  ...     46810  policy.json
-rw-------  ... 152802266  replay.jsonl
$ adb shell run-as com.example.hlcandlerl du -sh files/learning_state
147M    files/learning_state
```

Each persisted transition is two 234-float arrays serialized as full-precision JSON, ~9.4 KB per row
(measured: 100 live rows = 960 KB).

**2. The same path used to die with OutOfMemoryError.** From the earlier session's crash buffer, the
run completed two rounds and then threw while checkpointing:

```text
java.lang.OutOfMemoryError: Failed to allocate a 92381112 byte allocation with 25100288 free bytes
    at kotlin.collections.CollectionsKt___CollectionsKt.joinToString(_Collections.kt:3507)
    at com.example.hlcandlerl.engine.RlEngine.compactReplayFile(RlEngine.kt:335)
    at com.example.hlcandlerl.engine.RlEngine$offlineTrain$1$2$1.invokeSuspend(RlEngine.kt:264)
I ActivityManager: Process com.example.hlcandlerl (pid 7566) has died: fg TOP
```

**3. UI dropped frames during training**, app pid 2024:

```text
I Choreographer: Skipped 49 frames!  The application may be doing too much work on its main thread.
I Choreographer: Skipped 42 frames!  ...
```

Heap during the run reached **~137 MB PSS** (`dumpsys meminfo`, TOTAL PSS 136931 KB).

**4. No way to stop a training run.** There was no cancel path at all: once `offlineTrain` started,
every button stayed disabled until it finished or the process died.

**5. Structural causes found by reading the code:** training fired after every generated transition;
progress was published every 250 candles regardless of wall time; `setMarket` restored the replay
file from disk; `attachPersistenceDir` re-ran on every Activity entry; policy restore threw on an
inputDim mismatch; the service read the policy and compacted replay on its main thread.

## Fixes made

`engine/RlEngine.kt`

- **Offline training no longer persists raw transitions.** `appendTransition` is suppressed in
  offline mode and the end-of-run compaction is gone; each round writes `policy.json` and a compact
  `offline_summary.json` (~300 bytes) instead. This is the fix for issue 1.
- **`stopOfflineTraining()`** cancels the run; `runOfflineEpoch` calls `coroutineContext.ensureActive()`
  once per candle, and the `CancellationException` handler checkpoints the policy under
  `NonCancellable` so a stopped run keeps what it learned. `finally` always clears `offlineJob`,
  `offlineMode`, `trainActive` and `phase`.
- **Batched updates**: train once every 8 candles on a batch of 32 instead of after every transition.
- **Throttled progress**: `publishTrainingProgress` publishes at most every 250 ms (always on the
  last candle) and carries phase, round, processed/total candles, action counts, elapsed and ETA.
- **Bounded persistence**: in-memory replay 20k -> `REPLAY_CAPACITY = 5_000`; persisted rows capped at
  `PERSISTED_REPLAY_ROWS = 2_000`; a replay log over `REPLAY_FILE_MAX_BYTES = 25 MB` is archived
  instead of parsed; the live log self-compacts while appending (checked every 500 appends) so
  stopping never has to compact on the main thread.
- **`restorePersistedPolicy()`** archives a policy whose `inputDim` no longer matches and resets the
  learner, instead of throwing on every start.
- **`setMarket` never touches the replay file**; restore happens in `start()` on `Dispatchers.IO`.
- **`attachPersistenceDir` returns early** when the root is unchanged, so the Activity cannot disturb
  a run the Service already started.
- **Every `_state.value = _state.value.copy(...)` became `_state.update { ... }`** (atomic CAS), and
  the download path streams both the candle file and its failure/`finally` state transitions.
- Download detail now reads `Downloaded N candles (requested Xd)`; `deleteDownloadedData` refuses
  with a message while a run is active; storage size is published with the candle count.

`engine/RlForegroundService.kt`

- Policy restore moved off the service main thread (it happens inside `engine.start()` on IO); the
  SharedPreferences policy is migrated in the background; periodic checkpoints run on IO. The
  main-thread stop/destroy paths write only the small policy file.

`MainActivity.kt`

- Buttons: `Download 1d / 3d / 7d`, `Train 1 round`, `Train 3 rounds`, `Stop offline training`,
  `Delete downloaded candle data`. New metric tiles: Storage, Phase, Round, Candles done,
  Elapsed / ETA, Actions.
- A LazyColumn market menu was tried and reverted: `DropdownMenu` measures its content's intrinsics
  and it crashed with "Asking for intrinsic measurements of SubcomposeLayout". The eager menu is
  kept, with a comment recording why.

`rl/MaskedDoubleQLearner.kt` — `ReplayBuffer` capacity is now injected by the engine.

## Runtime verification

Fresh install, then, on the emulator:

- **Market switching: pass.** 21 switches (ETH, SOL, XRP, LINK, TAO, ARB, HYPE, ZEC, PUMP, BNB, DOGE,
  LTC, SUI, CRV, UNI, XMR, ENA, JUP, INJ, BTC...) with no freeze, no crash and no ANR. Each switch
  reported `market changed to X`. Unit test also asserts a switch over a 5k-row replay file stays
  under 1.5 s and restores nothing.
- **Download: pass.** `Download 1d` -> `Downloaded 1441 candles (requested 1d)` in ~12 s;
  `Download 7d` -> 5150 candles. Progress text appears immediately.
- **Offline train: pass.** 3 rounds over 1441 candles completed in **1 s**; 1 round over 5150 candles
  completed with `frames 5149 trainEq +41.54 reward +41.54 +1600/-1462 trades 397/396 maxDD -13.90
  actions W=1274/E=182/E=215/H=3082/E=396`. No crash, no OOM.
- **Stop training: pass.** Tapping Stop during a run produced `Stopped after 0/5150 candles; policy
  kept.`; the state was observed already stopped on the first UI read after the tap (~0.4 s of real
  time, the rest is uiautomator dump latency). The engine accepted a new run immediately afterwards.
  A unit test asserts stop lands in under 2 s on a 60k-candle run.
- **Delete data: pass.** Stored candles 5150 -> 0, storage 951.4 KB -> 47.6 KB, only `policy.json` and
  `offline_summary.json` left on disk, no crash. Training afterwards says
  `Download candles before offline training.`
- **Reset: pass.** `policy.json` and `offline_summary.json` were archived as
  `*.archive-<ts>.json`.
- **Relaunch: pass.** After force-stop and restart the app came back with `Market: BTC` and a sane
  state; stored candle count and storage size are now shown without needing a download first.
- **Live start/stop: pass for BTC and ETH.** BTC ran ~2 minutes: status `running`, close 78,4xx,
  positions cycling LONG -> flat -> SHORT -> flat, policy written on stop (51 KB), pill back to
  PAUSED. ETH started to `running` with close 2473.40 and stopped cleanly.
- **Storage/memory: pass.** Whole app data across BTC + ETH after all of the above: **1.4 MB**
  (previously 147 MB for BTC alone). PSS during and after training **~103-104 MB**, and 83 MB after
  the live session, versus ~137 MB before. Live `replay.jsonl` was 982 KB after two minutes.
- **Jank: none observed.** `grep "Skipped .* frames"` for the app pid over the training and live
  session returned **0** events. (The pre-fix run showed repeated 42-49 frame skips.)
- **Logcat: clean.** No `FATAL EXCEPTION`, no `OutOfMemory`, no ANR for the package.

Safety/no-cost greps both return no hits:

```bash
rg -i "userFees|/exchange|privateKey|mnemonic|secret|wallet|placeOrder|cancelOrder" app/src/main/java
rg -i "\bfee\b|funding|\bcost\b|crossFee|addFee" app/src/main/java
```

Execution price is still `candle.close`, sizing is still `1000 / price`, and both are covered by tests.

## Second pass: remaining crash paths closed (`7daeec6`)

A static audit after the training work found four ways the app could still die or wedge, none of
them on the paths already exercised above:

1. **Sticky restart crashed the app.** The service returned `START_STICKY`, so after any process
   death Android re-delivered a null intent while the app was in the background and `startLearner()`
   called `startForeground()` from there — `ForegroundServiceStartNotAllowedException` on Android
   12+. A null intent now stops the service, every path returns `START_NOT_STICKY`, and the
   `startForeground()` call is guarded.
2. **Android 15+ foreground-service timeout.** A long-running `dataSync` service that ignores its
   timeout is force-crashed by the platform. `Service.onTimeout` is now overridden (both the API 35
   and API 36 signatures; `compileSdk` moved to 36 for the latter, `targetSdk` unchanged) and stops
   the learner cleanly. This one matters on the user's own phone, which runs Android 17.
3. **An exception in `tick()` killed the process.** It escaped the coroutine to the default handler.
   Decision steps are now caught and surfaced in the status line, and the learner stops itself after
   five consecutive failures instead of taking the app down.
4. **A failed notification refresh or a throwing market switch** could do the same; both are
   contained now.

Abuse test on a fresh install, all with the process id watched: 12 back-to-back Start/Stop taps with
no settling time, 36 rapid taps across Download 1d/3d/7d, Train 1/3, Stop training and Delete,
home-and-resume during a training run, and a relaunch while the live learner was running. The
process never restarted, Stop always settled to PAUSED with Start re-enabled, and the crash buffer,
FATAL, ANR and foreground-service checks were all empty.

## Remaining risks

- One `candleSnapshot` request does not return the requested window: 7d yields ~5150 candles, 1d
  yields 1441. The UI now states what was actually downloaded, but paginating the download is still
  unimplemented.
- Offline training still uses one current public context for every historical candle. Execution uses
  candle close so PnL is unaffected, but the six context features are anachronistic.
- No train/validation split: the offline report is in-sample only.
- Live `replay.jsonl` is still full-precision JSON at ~9 KB per row. It is now capped (25 MB, then
  compacted to 2k rows), but a compact binary or reduced-precision format would be far smaller.
- The multi-market sweep was run for BTC and ETH only; SOL, SP500, WTI and NVDA were exercised through
  market switching but not through a full download/train/delete cycle.
- Opening the 46-item market dropdown was the one place that showed frame skips on the pre-fix build.
  The obvious fix (a LazyColumn) is not usable inside `DropdownMenu`; if it still feels slow, the menu
  needs a different container (e.g. a modal bottom sheet with a lazy list).
- The learner is no longer resurrected after a process death (`START_NOT_STICKY`); a long run that
  the system kills must be restarted by hand. That is deliberate — the alternative crashed.
- `AppRuntime.engine` remains a process-wide singleton shared by Activity and Service. All state
  writes are atomic now and attach is idempotent, but two components still drive one engine.

## User instructions

Build and install:

```bash
cd /Users/rahulgirishkumar/PROJECTS/HLCandleRL
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The branch is `fix/offline-training-stability`; merge it with:

```bash
git checkout main && git merge fix/offline-training-stability
```

To try it on the phone: pick a market, tap **Download 1d** (fast, ~1400 candles) or **Download 7d**,
then **Train 1 round**. Progress shows phase, round, candles done, elapsed and ETA; **Stop offline
training** interrupts it within a candle and keeps the policy learned so far. **Delete downloaded
candle data** frees the candle file and leaves the policy and summary. **Start learner** runs live
virtual learning on the WebSocket candle stream; **Stop** checkpoints the policy.

To watch it from a terminal:

```bash
adb logcat -c && adb logcat | grep -iE "hlcandlerl|AndroidRuntime|OutOfMemory|ANR|Choreographer"
```

To check what it stores:

```bash
adb shell run-as com.example.hlcandlerl du -sh files/learning_state
adb shell run-as com.example.hlcandlerl ls -l files/learning_state/BTC
```
