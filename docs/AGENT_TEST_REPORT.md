# HLCandleRL Test Report

Build: **pass** (`./gradlew clean assembleDebug`, BUILD SUCCESSFUL)
Device/emulator: **Medium_Phone_API_36.1 emulator (Android 16, API 36.1, 1080x2400)**. A physical
Pixel 9 Pro (Android 17 / SDK 37) was attached and the APK installed successfully, but the device
stayed locked, so no UI was driven on it.
Commit tested: `26d22de` (handoff brief), i.e. the tree at `9528b81` + docs
Fixes committed on branch: `test/offline-training-robustness` (`5a4f235`)

## Invariants

| Invariant | Result |
|---|---|
| Virtual-only (no key/wallet/signing/`/exchange`/orders) | **pass** — grep hits are only the disclaimer string, `Side.LONG` sign math, `Job.cancel()`, and a `Regex` `replace`. No exchange endpoint other than `/info` and `wss://api.hyperliquid.xyz/ws`. Manifest requests only INTERNET / FOREGROUND_SERVICE / POST_NOTIFICATIONS. |
| No cost/fee/funding model | **pass** — grep for `userFees\|fee\|funding\|cost\|crossFee\|addFee` over `app/src/main/java` returns nothing. Verified behaviourally: a round trip at an unchanged price realizes exactly `0.0`. |
| Execution price = `candle.close` | **pass** — `MarketFrame.price` ignores `markPx`/`oraclePx`; covered by `MarketFrameTest` and an offline-trainer test that trains with `markPx = 999_999` against a ~100 price series. |
| Fixed sizing `qty = 1000 / price` | **pass** — asserted directly (entry at 100 -> qty 10.0). |
| Causal transitions | **pass by inspection + tests** — interval reward goes to the pending WAIT/HOLD action, entry/exit rows carry the effective action. Note that with no cost model the entry/exit reward is structurally exactly `0.0`; all PnL flows through the interval rows. |

## Manual tests (emulator)

- **Market switch: pass.** 16 switches driven through the dropdown (ETH, SOL, XRP, LINK, DOGE, SUI,
  CRV, SP500, WTI, NVDA, TAO, ARB, BTC...), including scrolled entries. Dropdown opened in <1 s every
  time, the button showed `Market: X — busy` during the switch, the Market card updated, and status
  went `switching to X` -> `market changed to X`. No ANR, no freeze, no app entry in the crash buffer
  (the only crash logged was the emulator's own `com.google.android.tts`).
- **Live learner: pass.** Status moved `idle` -> `connected` -> `running`. Close/volume became
  non-zero (BTC ~79,175), positions opened and closed (`LONG @ 79177` -> `flat` -> ...), replay grew
  69 -> 96, updates 192 -> 1056, epsilon decayed 0.199 -> 0.197, Virtual PnL reached +0.4042 USDC.
- **Stop: pass.** Status `stopped`, pill `PAUSED`, no crash, `policy.json` (50 KB) and `replay.jsonl`
  written under `files/learning_state/BTC/`.
- **Per-market storage: pass.** Confirmed on device: `BTC`, `ETH`, `SOL`, `XRP`, `LINK`, `DOGE`,
  `SUI`, `CRV`, `xyz_CL`, `xyz_NVDA`, `xyz_SP500` — exactly the expected `<safe_coin>` layout.
- **Offline download: pass, with a caveat.** "Download 7d" for BTC showed progress immediately and
  finished in ~12 s with `Saved 5222 candles`. **5222, not ~10080** — one `candleSnapshot` request
  does not return a full 7 days of 1m candles (known risk 5, now confirmed).
- **Offline training: fail on the shipped build, fixed.** Tapping "Offline train" over those 5222
  candles ran rounds 1-2 fast (~14 s for both, 5222 candles each) and then killed the app with
  `OutOfMemoryError` on the round-2 replay checkpoint (see Bug 1). After the fix the
  suite covers it, but **the on-device re-run was not completed** — the session was stopped at the
  user's request before re-verifying training/delete/reset on the device.
- **Delete downloaded data: not run on device** (unit-tested instead).
- **Reset/persistence: partially verified.** Relaunch after the OOM crash was clean: app started,
  market/state sane, no crash loop. Reset button was not exercised on device.

## Automated tests added

New JVM unit-test source set (none existed). **47 tests, 0 failures, green on 3 consecutive runs.**

- `app/src/test/java/com/example/hlcandlerl/TestData.kt` — deterministic synthetic candles/series.
- `broker/VirtualPerpBrokerTest.kt` (9) — long/short PnL at fixed 1000 notional, exact-zero round trip
  (no fee drag), masks flat vs holding, forced exit at `maxHoldSteps`, invalid actions are no-ops,
  non-positive price refused, reward is mark-to-market only, reset.
- `data/MarketFrameTest.kt` (2) — execution price is candle close, never mark/oracle.
- `features/OhlcvFeatureBuilderTest.kt` (8) — feature size (234) matches learner input dim, no
  fee/funding/cost field on any observation model (reflection), needs >=2 candles, in-progress candle
  replaces rather than appends, `seed` takes the newest window chronologically, `patchPosition`
  side/age/unrealized, finite features with empty context.
- `rl/ReplayAndLearnerTest.kt` (10) — transition JSON round trip and single-line invariant, bounded
  FIFO replay, sampling bounds, policy snapshot/restore preserves Q-values, restore of a different
  feature size is rejected, `select` always respects the mask, epsilon decay floor, training moves Q
  toward reward, empty batch is a no-op.
- `exchange/HyperLiquidParsingTest.kt` (7) — `candleSnapshot` string OHLCV fields and fallbacks,
  `dex` field present only for `xyz:` markets, `metaAndAssetCtxs` context parsing with no fee/funding
  dependency, dex-qualified lookup by short name, unknown asset fails loudly, WS parser accepts
  matching candles and rejects other channels/coins/intervals/malformed/invalid payloads.
- `engine/OfflineTrainerTest.kt` (11) — chronological replay regardless of file order, corrupt lines
  skipped, refusal without enough candles, progress reaches 1.0 with non-zero frames and action
  counts summing to frames, execution on candle close under a stale 1e6 mark price, replay file stays
  bounded and re-readable, trainer releases its job, failed download clears the busy flag, delete
  removes only `candles_1m.jsonl`, per-market storage isolation, feature-size-incompatible replay
  rows skipped on restore.

## Bugs found

1. **Critical — OOM crash during offline training.** `RlEngine.compactReplayFile` built the entire
   replay buffer as one `joinToString` String. Rows are ~9.4 KB each (measured on device: 100 rows =
   960 KB), so a few thousand rows is a >90 MB allocation. Repro: download BTC 7d, tap Offline train
   -> rounds 1-2 complete in ~14 s, then `java.lang.OutOfMemoryError ... at
   RlEngine.compactReplayFile` and the process dies. This is
   pre-existing code, also reachable from live Stop / the every-500-updates checkpoint once the
   buffer is large.
2. **High — offline training could wedge the whole app.** `offlineTrain` set `offlineJob` and
   `trainActive` with no `try/finally`. Any exception (e.g. bug 1) left `offlineJob != null` forever,
   which makes `start`, `downloadOfflineCandles`, `deleteDownloadedData`, `offlineTrain` and
   `setMarket` silent no-ops, with every button disabled, until the process restarts.
3. **Medium — a failed download left the UI permanently busy.** The `catch` cleared `downloadActive`
   but the `candleDataFile == null` path returned early with it still true.
4. **Medium — offline training wrote hundreds of MB.** Every offline transition was appended to
   `replay.jsonl`: rounds x candles x ~9.4 KB, i.e. ~250 MB for 5 rounds over 5222 candles (and
   ~470 MB at the intended 10080).
5. **Low — stored candle count showed 0 until a download/train.** Relaunching with candles already on
   disk reported "No downloaded candles yet".
6. **Low — O(n) replay eviction.** `ReplayBuffer.add` used `ArrayList.removeAt(0)`, shifting up to
   20k elements on every insert.

## Fixes made

All in commit `5a4f235` on `test/offline-training-robustness`:

- `engine/RlEngine.kt` — stream `compactReplayFile` through a buffered writer (bug 1); wrap the
  offline round loop in `try/catch/finally` that always clears `offlineJob`/`trainActive` and
  rethrows `CancellationException` (bug 2); clear `downloadActive` in the download `finally` and turn
  the storage-less path into a reported error (bug 3); suppress per-transition disk appends during
  offline rounds and checkpoint the bounded buffer once per run (bug 4); publish the stored candle
  count off the main thread via an atomic `StateFlow.update` (bug 5).
- `rl/MaskedDoubleQLearner.kt` — `ReplayBuffer` backed by `ArrayDeque` (bug 6).
- `exchange/HyperLiquidInfoClient.kt`, `exchange/HyperLiquidCandleWsClient.kt` — test seams only:
  `internal` parse functions, `buildContextRequest`/`parseContext` split out of the HTTP call, and an
  `open`/injectable info client. No behaviour change.
- `app/build.gradle.kts` — JUnit, `org.json` and coroutines-test on the unit-test classpath.

## Remaining risks

- The on-device offline training / delete / reset / relaunch sequence was **not re-verified after the
  OOM fix**, and the multi-market sweep (ETH, SOL, SP500, WTI, NVDA) was not run at all. Both are
  covered by unit tests, not by the device.
- `replay.jsonl` is still heavy: 20k rows x ~9.4 KB is ~190 MB per market on disk. Transitions are
  serialized as full-precision doubles (234 floats x2 per row). Reducing precision or storing floats
  compactly would cut this several-fold.
- Offline training still uses one current public context for every historical candle. Execution uses
  candle close so PnL is unaffected, but the 6 context features are anachronistic.
- 7d of 1m candles is not actually available from a single `candleSnapshot` request (5222 for BTC);
  other markets may return even less. Paginating the download would be needed for a true 7d set.
- No train/validation split, so the offline report is in-sample only and says nothing about
  generalization.
- `attachPersistenceDir()` is still called from both the Activity and the Service; it is idempotent
  for the current coin, but the engine is a process-wide singleton and its `_state` is updated by
  plain read-modify-write assignments from several threads. I hit exactly this class of lost-update
  race while testing and fixed the one instance I introduced; the pre-existing assignments in
  `tick()`, `setMarket()` and `resetLearning()` have the same shape and should move to
  `MutableStateFlow.update {}`.
- The launcher still shows a stale "HL Phone RL" label on an upgraded install; the manifest label is
  correct ("HL Candle RL") and a clean install shows it.
