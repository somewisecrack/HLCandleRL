package com.example.hlcandlerl.engine

import com.example.hlcandlerl.data.Candle
import com.example.hlcandlerl.data.PerpContext
import com.example.hlcandlerl.exchange.HyperLiquidInfoClient
import com.example.hlcandlerl.syntheticSeries
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Drives the real offline pipeline (stored candles -> broker -> learner -> report) with a fake
 * info client so no network or Android framework class is involved.
 */
class OfflineTrainerTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeInfoClient(private val ctx: PerpContext) : HyperLiquidInfoClient() {
        override fun loadContext(coin: String): PerpContext = ctx
        override fun loadRecentCandles(coin: String, interval: String, lookbackMillis: Long): List<Candle> = emptyList()
    }

    private fun engineWith(root: File, ctx: PerpContext = PerpContext(markPx = 999_999.0)): RlEngine =
        RlEngine("BTC", mapOf("BTC" to "BTC", "NVDA" to "xyz:NVDA"), FakeInfoClient(ctx))
            .also { it.attachPersistenceDir(root) }

    private fun writeCandles(root: File, coin: String, candles: List<Candle>) {
        val dir = File(root, coin).also { it.mkdirs() }
        File(dir, "candles_1m.jsonl").writeText(
            candles.joinToString("\n", postfix = "\n") { c ->
                JSONObject()
                    .put("coin", c.coin).put("interval", c.interval)
                    .put("openTimeMillis", c.openTimeMillis).put("closeTimeMillis", c.closeTimeMillis)
                    .put("open", c.open).put("high", c.high).put("low", c.low).put("close", c.close)
                    .put("volume", c.volume).put("trades", c.trades)
                    .toString()
            }
        )
    }

    private fun awaitTrainingComplete(engine: RlEngine, timeoutMs: Long = 120_000) = runBlocking {
        withTimeout(timeoutMs) {
            while (engine.state.value.trainProgress < 1f || engine.state.value.trainActive) {
                kotlinx.coroutines.delay(25)
            }
            // let the trainer coroutine finish releasing its job handle before the next call
            kotlinx.coroutines.delay(150)
        }
        engine.state.value
    }

    @Test
    fun storedCandlesAreReplayedOldestToNewestRegardlessOfFileOrder() {
        val root = tmp.newFolder()
        val series = syntheticSeries(60)
        writeCandles(root, "BTC", series.shuffled())
        val loaded = engineWith(root).loadStoredCandles()
        assertEquals(series.size, loaded.size)
        assertEquals(series.map { it.openTimeMillis }, loaded.map { it.openTimeMillis })
        assertTrue(loaded.zipWithNext().all { (a, b) -> a.openTimeMillis < b.openTimeMillis })
    }

    @Test
    fun corruptLinesAreSkippedInsteadOfFailingTheWholeFile() {
        val root = tmp.newFolder()
        writeCandles(root, "BTC", syntheticSeries(5))
        File(root, "BTC/candles_1m.jsonl").appendText("{not json}\n\n")
        assertEquals(5, engineWith(root).loadStoredCandles().size)
    }

    @Test
    fun offlineTrainingRefusesWithoutEnoughDownloadedCandles() {
        val root = tmp.newFolder()
        writeCandles(root, "BTC", syntheticSeries(10))
        val engine = engineWith(root)
        engine.offlineTrain(rounds = 1)
        runBlocking {
            withTimeout(20_000) {
                while (!engine.state.value.status.contains("need downloaded candles")) kotlinx.coroutines.delay(20)
            }
        }
        val s = engine.state.value
        assertFalse(s.trainActive)
        assertEquals(0f, s.trainProgress)
        assertTrue(s.trainDetail.contains("Download candles"))
    }

    @Test
    fun offlineTrainingReachesFullProgressAndReportsNonZeroFramesAndActions() {
        val root = tmp.newFolder()
        val series = syntheticSeries(400)
        writeCandles(root, "BTC", series)
        val engine = engineWith(root)
        engine.offlineTrain(rounds = 2)
        val s = awaitTrainingComplete(engine)

        assertEquals(1f, s.trainProgress)
        assertFalse(s.trainActive)
        assertEquals(2, s.offlineRound)
        assertEquals(series.size, s.offlineCandles)

        val report = s.offlineReport
        val frames = Regex("frames (\\d+)").find(report)!!.groupValues[1].toInt()
        assertTrue("report should show processed frames: $report", frames > 300)
        val actionCounts = Regex("actions (.+)$").find(report)!!.groupValues[1]
            .split("/").map { it.substringAfter("=").toInt() }
        assertEquals(5, actionCounts.size)
        assertEquals("every frame must pick exactly one action", frames, actionCounts.sum())
        assertTrue("epsilon exploration should produce entries: $report", actionCounts[1] + actionCounts[2] > 0)
        assertTrue("learner must have trained", s.updates > 0)
        assertTrue("replay must have filled", s.replay > 0)
    }

    @Test
    fun offlineTrainingExecutesOnCandleCloseNotOnTheStaleMarkPrice() {
        val root = tmp.newFolder()
        // Prices live near 100 while the (stale, non-historical) mark price is ~1e6.
        val series = syntheticSeries(300)
        writeCandles(root, "BTC", series)
        val engine = engineWith(root, PerpContext(markPx = 999_999.0, oraclePx = 999_999.0))
        engine.offlineTrain(rounds = 1)
        val s = awaitTrainingComplete(engine)
        val trainEq = Regex("trainEq ([+-][0-9.]+)").find(s.offlineReport)!!.groupValues[1].toDouble()
        val reward = Regex("reward ([+-][0-9.]+)").find(s.offlineReport)!!.groupValues[1].toDouble()
        // With qty = 1000/close on a ~100 price series, equity can only move by a few hundred USD.
        // Executing at markPx would make qty ~0.001 and collapse PnL to ~0, or blow it up if inverted.
        assertTrue("trainEq must be finite and bounded: $trainEq", trainEq.isFinite() && kotlin.math.abs(trainEq) < 5_000.0)
        assertTrue("reward must be finite: $reward", reward.isFinite())
        assertTrue("a moving price series must produce non-zero PnL: ${s.offlineReport}", kotlin.math.abs(reward) > 1e-9)
    }

    @Test
    fun offlineTrainingPersistsPolicyAndSummaryButNotRawTransitions() {
        val root = tmp.newFolder()
        writeCandles(root, "BTC", syntheticSeries(5_000))
        val engine = engineWith(root)
        engine.offlineTrain(rounds = 2)
        val s = awaitTrainingComplete(engine)

        val dir = File(root, "BTC")
        assertTrue("policy must be checkpointed", File(dir, "policy.json").exists())
        val summary = File(dir, "offline_summary.json")
        assertTrue("a compact summary must be written", summary.exists())
        assertTrue("summary must stay small", summary.length() < 4_096)
        val json = JSONObject(summary.readText())
        assertEquals(5_000, json.getInt("candles"))
        assertEquals(2, json.getInt("rounds"))
        assertTrue(json.getString("report").startsWith("frames "))

        // 5k candles x 2 rounds used to append ~10k rows of ~9 KB per round to replay.jsonl.
        val replayFile = File(dir, "replay.jsonl")
        assertFalse("offline training must not persist raw transitions", replayFile.exists())
        val bytes = dir.listFiles()!!.sumOf { it.length() }
        assertTrue("offline run wrote $bytes bytes; expected well under 5 MB", bytes < 5_000_000)
        assertTrue(s.replay in 1..com.example.hlcandlerl.engine.RlEngine.REPLAY_CAPACITY)
    }

    @Test
    fun stoppingOfflineTrainingIsPromptAndLeavesTheAppUsable() {
        val root = tmp.newFolder()
        writeCandles(root, "BTC", syntheticSeries(60_000))
        val engine = engineWith(root)
        engine.offlineTrain(rounds = 20)
        runBlocking {
            withTimeout(30_000) { while (!engine.state.value.trainActive || engine.state.value.processedCandles < 100) delay(10) }
        }
        val stopRequestedAt = System.currentTimeMillis()
        engine.stopOfflineTraining()
        runBlocking {
            withTimeout(10_000) { while (engine.state.value.trainActive) delay(10) }
        }
        val stopTookMs = System.currentTimeMillis() - stopRequestedAt
        val s = engine.state.value
        assertTrue("stop took ${stopTookMs}ms; must be well under 2s", stopTookMs < 2_000)
        assertFalse(s.trainActive)
        assertEquals("offline training stopped", s.status)
        assertTrue("a stopped run must keep what it learned", File(root, "BTC/policy.json").exists())

        // The engine must not be wedged: another run has to start.
        runBlocking { delay(200) }
        engine.offlineTrain(rounds = 1)
        runBlocking { withTimeout(20_000) { while (!engine.state.value.trainActive) delay(10) } }
        engine.stopOfflineTraining()
        runBlocking { withTimeout(10_000) { while (engine.state.value.trainActive) delay(10) } }
    }

    @Test
    fun anIncompatiblePolicyIsArchivedAndResetInsteadOfCrashing() {
        val root = tmp.newFolder()
        val dir = File(root, "BTC").also { it.mkdirs() }
        val stalePolicy = com.example.hlcandlerl.rl.MaskedDoubleQLearner(inputDim = 99).snapshotJson()
        File(dir, "policy.json").writeText(stalePolicy)
        val engine = engineWith(root)

        engine.restorePersistedPolicy()

        assertEquals("incompatible policy archived; starting fresh", engine.state.value.status)
        assertEquals(0, engine.state.value.updates)
        assertFalse("the stale policy must be moved aside", File(dir, "policy.json").exists())
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("policy.archive-") })
    }

    @Test
    fun garbageInPolicyFileDoesNotCrashTheRestore() {
        val root = tmp.newFolder()
        val dir = File(root, "BTC").also { it.mkdirs() }
        File(dir, "policy.json").writeText("{ this is not json")
        val engine = engineWith(root)
        engine.restorePersistedPolicy()
        assertEquals(0, engine.state.value.updates)
        assertFalse(File(dir, "policy.json").exists())
    }

    @Test
    fun anOversizedReplayLogIsArchivedRatherThanParsed() {
        val root = tmp.newFolder()
        val dir = File(root, "BTC").also { it.mkdirs() }
        val replayFile = File(dir, "replay.jsonl")
        val row = com.example.hlcandlerl.rl.Transition(
            state = FloatArray(234) { 0.1f }, action = 0, reward = 1.0,
            nextState = FloatArray(234) { 0.2f },
            nextMask = booleanArrayOf(true, true, true, false, false), done = false
        ).toJsonLine()
        replayFile.bufferedWriter().use { w ->
            var written = 0L
            while (written <= com.example.hlcandlerl.engine.RlEngine.REPLAY_FILE_MAX_BYTES) {
                w.write(row); w.newLine(); written += row.length + 1
            }
        }
        val engine = engineWith(root)
        engine.loadReplayOnce()
        assertEquals("an oversized log must not be parsed into memory", 0, engine.state.value.replay)
        assertFalse(replayFile.exists())
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("replay.archive-") })
    }

    @Test
    fun restoredReplayIsCappedToThePersistedRowLimit() {
        val root = tmp.newFolder()
        val dir = File(root, "BTC").also { it.mkdirs() }
        val row = com.example.hlcandlerl.rl.Transition(
            state = FloatArray(234) { 0.1f }, action = 0, reward = 1.0,
            nextState = FloatArray(234) { 0.2f },
            nextMask = booleanArrayOf(true, true, true, false, false), done = false
        ).toJsonLine()
        File(dir, "replay.jsonl").bufferedWriter().use { w ->
            repeat(com.example.hlcandlerl.engine.RlEngine.PERSISTED_REPLAY_ROWS + 500) { w.write(row); w.newLine() }
        }
        val engine = engineWith(root)
        engine.loadReplayOnce()
        assertEquals(com.example.hlcandlerl.engine.RlEngine.PERSISTED_REPLAY_ROWS, engine.state.value.replay)
    }

    @Test
    fun switchingMarketsNeverParsesThePersistedReplay() {
        val root = tmp.newFolder()
        val dir = File(root, "xyz_NVDA").also { it.mkdirs() }
        val row = com.example.hlcandlerl.rl.Transition(
            state = FloatArray(234) { 0.1f }, action = 0, reward = 1.0,
            nextState = FloatArray(234) { 0.2f },
            nextMask = booleanArrayOf(true, true, true, false, false), done = false
        ).toJsonLine()
        File(dir, "replay.jsonl").bufferedWriter().use { w -> repeat(5_000) { w.write(row); w.newLine() } }
        val engine = engineWith(root)
        val startedAt = System.currentTimeMillis()
        engine.setMarket("NVDA")
        runBlocking { withTimeout(20_000) { while (engine.state.value.marketSwitching) delay(5) } }
        val tookMs = System.currentTimeMillis() - startedAt
        assertEquals("a market switch must not restore replay from disk", 0, engine.state.value.replay)
        assertTrue("switch took ${tookMs}ms; it must not depend on file size", tookMs < 1_500)
    }

    @Test
    fun deletingDownloadedDataIsRefusedWhileTrainingIsActive() {
        val root = tmp.newFolder()
        writeCandles(root, "BTC", syntheticSeries(60_000))
        val engine = engineWith(root)
        engine.offlineTrain(rounds = 20)
        runBlocking { withTimeout(30_000) { while (!engine.state.value.trainActive) delay(10) } }
        engine.deleteDownloadedData()
        assertEquals("stop the learner/training before deleting data", engine.state.value.status)
        assertTrue("candle data must survive a refused delete", File(root, "BTC/candles_1m.jsonl").exists())
        engine.stopOfflineTraining()
        runBlocking { withTimeout(10_000) { while (engine.state.value.trainActive) delay(10) } }
    }

    @Test
    fun theTrainerReleasesItsJobSoTheAppIsNotWedgedAfterARun() {
        val root = tmp.newFolder()
        writeCandles(root, "BTC", syntheticSeries(120))
        val engine = engineWith(root)
        engine.offlineTrain(rounds = 1)
        awaitTrainingComplete(engine)
        // A second run must be accepted; if the job handle leaked, every later action is a silent no-op.
        engine.offlineTrain(rounds = 1)
        runBlocking { withTimeout(20_000) { while (!engine.state.value.trainActive) kotlinx.coroutines.delay(10) } }
        val s = awaitTrainingComplete(engine)
        assertEquals(1f, s.trainProgress)
    }

    @Test
    fun aFailedDownloadClearsTheBusyFlagInsteadOfDisablingEveryButton() {
        val root = tmp.newFolder()
        val failing = object : HyperLiquidInfoClient() {
            override fun loadContext(coin: String) = PerpContext()
            override fun loadRecentCandles(coin: String, interval: String, lookbackMillis: Long): List<Candle> =
                throw java.io.IOException("simulated network failure")
        }
        val engine = RlEngine("BTC", mapOf("BTC" to "BTC"), failing).also { it.attachPersistenceDir(root) }
        engine.downloadOfflineCandles(days = 1)
        runBlocking {
            withTimeout(20_000) { while (!engine.state.value.status.startsWith("download failed")) kotlinx.coroutines.delay(20) }
            kotlinx.coroutines.delay(150)
        }
        val s = engine.state.value
        assertFalse("a failed download must not leave the UI permanently busy", s.downloadActive)
        assertEquals(0f, s.downloadProgress)
        assertTrue(s.downloadDetail.contains("failed", ignoreCase = true))
    }

    @Test
    fun deletingDownloadedDataRemovesOnlyTheCandleFile() {
        val root = tmp.newFolder()
        writeCandles(root, "BTC", syntheticSeries(200))
        val engine = engineWith(root)
        engine.offlineTrain(rounds = 1)
        awaitTrainingComplete(engine)

        val dir = File(root, "BTC")
        val candles = File(dir, "candles_1m.jsonl")
        val policy = File(dir, "policy.json")
        val replay = File(dir, "replay.jsonl")
        assertTrue("training must checkpoint the policy", policy.exists())
        assertFalse("offline training must not write raw replay rows", replay.exists())
        val summary = File(dir, "offline_summary.json")
        assertTrue("training must write a compact summary", summary.exists())

        engine.deleteDownloadedData()
        assertFalse("candle data must be deleted", candles.exists())
        assertTrue("policy must survive", policy.exists())
        assertTrue("summary must survive", summary.exists())
        assertEquals(0, engine.state.value.offlineCandles)
        assertEquals(0, engine.loadStoredCandles().size)
        assertTrue(engine.state.value.status.contains("deleted"))
    }

    @Test
    fun eachMarketKeepsItsOwnStorageFolderAndDeletionIsScoped() {
        val root = tmp.newFolder()
        val engine = engineWith(root)
        writeCandles(root, "BTC", syntheticSeries(50))
        writeCandles(root, "xyz_NVDA", syntheticSeries(50, base = 180.0, coin = "xyz:NVDA"))

        engine.setMarket("NVDA")
        runBlocking {
            withTimeout(20_000) { while (engine.state.value.coin != "xyz:NVDA") kotlinx.coroutines.delay(20) }
            withTimeout(20_000) { while (engine.state.value.marketSwitching) kotlinx.coroutines.delay(20) }
        }
        assertEquals(50, engine.loadStoredCandles().size)

        engine.deleteDownloadedData()
        assertFalse(File(root, "xyz_NVDA/candles_1m.jsonl").exists())
        assertTrue("switching markets must not touch another market's data", File(root, "BTC/candles_1m.jsonl").exists())
    }

    @Test
    fun replayRestoreSkipsTransitionsWithAnIncompatibleFeatureSize() {
        val root = tmp.newFolder()
        val dir = File(root, "BTC").also { it.mkdirs() }
        val good = com.example.hlcandlerl.rl.Transition(
            state = FloatArray(234) { 0.1f },
            action = 0,
            reward = 1.0,
            nextState = FloatArray(234) { 0.2f },
            nextMask = booleanArrayOf(true, true, true, false, false),
            done = false
        )
        val stale = good.copy(state = FloatArray(99), nextState = FloatArray(99))
        File(dir, "replay.jsonl").writeText(
            listOf(good.toJsonLine(), stale.toJsonLine(), "garbage", good.toJsonLine()).joinToString("\n", postfix = "\n")
        )
        val engine = engineWith(root)
        engine.loadReplayOnce()
        assertEquals("only feature-size-compatible rows may be restored", 2, engine.state.value.replay)
    }
}
