# HLCandleRL Comprehensive App Test Handoff

## Mission

Comprehensively test the Android app **HL Candle RL / HLCandleRL** after recent changes:

- OHLCV/perp-context RL app, not L2.
- Virtual-only: no private keys, no wallet signing, no real orders.
- No cost/fee/funding model in Candle RL.
- Offline candle download/training on phone.
- Async market switching.
- Curated liquid market selector.

Repo/path:

```text
/Users/rahulgirishkumar/PROJECTS/HLCandleRL
https://github.com/somewisecrack/HLCandleRL
```

Latest important commits:

```text
5a6b7c4 Remove cost model from Candle RL app
bda49ce Prevent UI hang during market switching
9528b81 Fix offline training candle execution price
```

## Build setup

Shell may not see Java unless Android Studio JBR is exported:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

Build command:

```bash
cd /Users/rahulgirishkumar/PROJECTS/HLCandleRL
./gradlew clean assembleDebug --stacktrace
```

Expected:

```text
BUILD SUCCESSFUL
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Core files to inspect

```text
app/src/main/java/com/example/hlcandlerl/MainActivity.kt
app/src/main/java/com/example/hlcandlerl/engine/AppRuntime.kt
app/src/main/java/com/example/hlcandlerl/engine/RlEngine.kt
app/src/main/java/com/example/hlcandlerl/engine/RlForegroundService.kt
app/src/main/java/com/example/hlcandlerl/exchange/HyperLiquidInfoClient.kt
app/src/main/java/com/example/hlcandlerl/exchange/HyperLiquidCandleWsClient.kt
app/src/main/java/com/example/hlcandlerl/features/OhlcvFeatureBuilder.kt
app/src/main/java/com/example/hlcandlerl/broker/VirtualPerpBroker.kt
app/src/main/java/com/example/hlcandlerl/rl/MaskedDoubleQLearner.kt
app/src/main/java/com/example/hlcandlerl/data/Models.kt
```

## Non-negotiable invariants

### Safety

Must remain virtual-only:

- No private key input.
- No wallet signing.
- No HyperLiquid `/exchange` calls.
- No order placement endpoints.
- No real account data dependency.

Check with:

```bash
rg "privateKey|wallet|sign|/exchange|order|place|cancel|secret|mnemonic" app/src/main/java
```

Some harmless words like `order` in docs/comments may exist, but no real trading pathway should exist.

### Cost removal

Candle RL must not apply costs:

- No `userFees` call.
- No fee deduction on entry/exit.
- No funding cost application.
- No cost/funding UI row.
- No funding feature input.

Check with:

```bash
rg "userFees|fee|Fee|funding|Funding|cost|Cost|crossFee|addFee" app/src/main/java
```

Expected app source result: none, except possible unrelated words in dependency-generated/build output must be ignored.

### Execution pricing

Virtual broker execution must use:

```text
MarketFrame.price = candle.close
```

Mark/oracle/OI/premium are context/display only and must not replace historical candle close during offline replay.

### Fixed sizing

Entry quantity must remain:

```text
qty = 1000 / execution_price
```

No dynamic sizing yet.

### Causal transitions

Replay rows must remain causal:

```text
(state_t, action_t, reward_{t+1}, next_state_{t+1}, next_mask_{t+1}, done)
```

Immediate entry/exit PnL/reward is assigned to the selected action. Mark-to-market movement across candles is assigned to HOLD/WAIT interval action.

## Manual emulator/device test plan

Install app:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch app and verify:

1. App name/title is **HL Candle RL**.
2. Default market is **BTC**.
3. Market dropdown opens quickly.
4. Selecting another ticker does not freeze the UI.
5. During market switch, dropdown/button shows busy/disabled state.
6. Selected ticker updates in Market card.
7. Start learner:
   - status moves through context/history loading -> starting -> websocket status/running.
   - candle updates increment.
   - candle age updates.
   - close/volume fields become non-zero.
   - replay/updates eventually change after decisions.
8. Stop learner:
   - no crash.
   - state says stopped.
   - policy/replay persisted.
9. Reset:
   - app stops.
   - position/PnL/replay/update UI resets.
   - policy/replay archived for selected market.
10. Relaunch app:
   - no crash.
   - selected/persisted market state behavior is sane.

## Offline download/training test plan

For BTC first:

1. Tap **Download 7d**.
2. Verify download progress appears immediately:

```text
Requesting HyperLiquid candleSnapshot...
Received N candles; writing phone storage...
Saved N candles
```

3. Stored candles should be around 10,000 for 7d of 1m candles, depending API availability.
4. Tap **Offline train**.
5. Verify training progress updates through rounds/candles:

```text
Round 1/5: 250/10080 candles
Round 1/5: 500/10080 candles
...
```

6. Offline report should not be all zeros once actions/trades happen. It should include:

```text
frames <nonzero>
trainEq <possibly nonzero>
reward <possibly nonzero>
+/- reward counts
trades entries/exits
maxDD
actions W=.../E=... etc.
```

Important nuance: trade count could be zero if the random/greedy policy selects WAIT only for a short run, but action counts and frames must expose that. Across 5 rounds with epsilon exploration, entry counts should usually become non-zero.

7. Tap **Delete downloaded candle data**.
8. Verify:
   - stored candle count goes to 0.
   - no crash.
   - policy/replay remain.
9. Tap Offline train after deletion.
10. Expected:

```text
need downloaded candles first
```

## Multi-market offline test

Repeat download/train/delete for at least:

```text
BTC
ETH
SOL
SP500
WTI
NVDA
```

Acceptance:

- No UI freeze on switching.
- Each market uses separate storage folder:

```text
filesDir/learning_state/<safe_coin>/
```

Examples:

```text
BTC
ETH
xyz_SP500
xyz_CL
xyz_NVDA
```

- Downloaded `candles_1m.jsonl` is per-market.
- Deleting data for one market does not delete another market's candle data/policy/replay.

## Suggested automated tests to add

Create Android/JVM unit tests if test infra exists or add it.

### Broker tests

File suggestion:

```text
app/src/test/java/com/example/hlcandlerl/broker/VirtualPerpBrokerTest.kt
```

Test cases:

1. Long PnL:
   - enter long at 100.
   - exit at 110.
   - qty = 1000/100 = 10.
   - realized = +100.

2. Short PnL:
   - enter short at 100.
   - exit at 90.
   - realized = +100.

3. No fees:
   - enter and exit same price.
   - realized/equity = 0 exactly.
   - no negative fee drag.

4. Valid masks:
   - flat: WAIT/LONG/SHORT true, HOLD/EXIT false.
   - holding: HOLD/EXIT true, WAIT/LONG/SHORT false.

5. Forced max hold:
   - after maxHoldSteps, HOLD becomes forced exit.

### MarketFrame test

Verify:

```kotlin
MarketFrame(candle.close=100.0, context.markPx=999.0).price == 100.0
```

### Feature builder tests

1. Feature size matches learner input dimension.
2. Funding/cost fields are absent.
3. Feature vector builds after enough candles.
4. `patchPosition` updates side/age/unrealized fields correctly.

### HyperLiquid parser tests

1. `candleSnapshot` parsing handles string OHLCV fields.
2. `metaAndAssetCtxs` parses context without fee/funding dependencies.
3. `xyz:` dex request uses `dex` field correctly.

### Replay tests

1. Transition JSON roundtrip.
2. Old/incompatible feature-size transitions are skipped on restore.
3. Replay restore is bounded to latest 20,000 rows.

### Offline trainer tests

1. Chronological candles are replayed oldest -> newest.
2. Execution uses candle close, not markPx.
3. Progress state reaches 1.0 after training.
4. Download deletion deletes only `candles_1m.jsonl`, not `policy.json` or `replay.jsonl`.

## Known risks / likely bugs to look for

1. Compose state updates from background threads are probably okay via StateFlow, but inspect for thread-safety race when switching market while service starts.
2. `attachPersistenceDir()` is called from Activity and Service; ensure it does not reset active state unexpectedly.
3. Policy restore may fail after feature-size change due to old policy input dimension. Verify app handles that without crash.
4. Offline train currently uses current public context for all historical candles. Since execution uses candle close, this should not zero out PnL, but context is not historical.
5. Download 7d is one API request; if HyperLiquid limits response size for some markets, stored candle count may be lower than expected.
6. App has no train/validation split yet, so offline report is training-only, not proof of generalization.

## Acceptance criteria

The app is acceptable only if:

- Clean Gradle build passes.
- App launches on emulator/device.
- Market selection does not hang across at least 10 rapid ticker switches while stopped.
- Start/Stop/Reset work without crash.
- Download progress is visible immediately.
- Offline training progress is visible and reaches completion.
- Offline report shows nonzero frames and action counts.
- On synthetic/controlled data, broker PnL is nonzero when price moves and actions enter/exit.
- No cost/fee/funding code remains in app source.
- No real-trading pathway exists.

## Report format requested from testing agent

Produce a concise test report:

```text
# HLCandleRL Test Report

Build: pass/fail
Device/emulator: <name/api>
Commit tested: <sha>

Manual tests:
- Market switch: pass/fail + notes
- Live learner: pass/fail + notes
- Offline download: pass/fail + notes
- Offline training: pass/fail + notes
- Delete downloaded data: pass/fail + notes
- Reset/persistence: pass/fail + notes

Automated tests added:
- list files/tests

Bugs found:
- severity, reproduction, suspected file/function

Fixes made:
- commits/files

Remaining risks:
- bullets
```
