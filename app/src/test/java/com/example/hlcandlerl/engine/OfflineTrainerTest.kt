package com.example.hlcandlerl.engine

import com.example.hlcandlerl.data.Candle
import com.example.hlcandlerl.data.PerpContext
import com.example.hlcandlerl.exchange.HyperLiquidInfoClient
import com.example.hlcandlerl.syntheticSeries
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
    fun offlineTrainingKeepsThePersistedReplayFileBoundedToTheInMemoryBuffer() {
        val root = tmp.newFolder()
        writeCandles(root, "BTC", syntheticSeries(400))
        val engine = engineWith(root)
        engine.offlineTrain(rounds = 2)
        val s = awaitTrainingComplete(engine)
        val lines = File(root, "BTC/replay.jsonl").readLines().count { it.isNotBlank() }
        // Offline rounds must checkpoint the bounded buffer, not append every step of every round.
        assertEquals(s.replay, lines)
        assertTrue("replay file must not grow with rounds x candles: $lines", lines <= 20_000)
        // every checkpointed row must still be readable back
        val restored = File(root, "BTC/replay.jsonl").readLines().filter { it.isNotBlank() }
            .map { com.example.hlcandlerl.rl.Transition.fromJsonLine(it) }
        assertEquals(lines, restored.size)
        assertTrue(restored.all { it.state.size == 234 && it.nextState.size == 234 })
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
        assertTrue("training must persist replay", replay.exists())

        engine.deleteDownloadedData()
        assertFalse("candle data must be deleted", candles.exists())
        assertTrue("policy must survive", policy.exists())
        assertTrue("replay must survive", replay.exists())
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
        engine.setMarket("BTC") // triggers loadReplayOnce for the selected market
        runBlocking {
            withTimeout(20_000) { while (engine.state.value.marketSwitching) kotlinx.coroutines.delay(20) }
        }
        assertEquals("only feature-size-compatible rows may be restored", 2, engine.state.value.replay)
    }
}
