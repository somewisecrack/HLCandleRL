package com.example.hlcandlerl.engine

import com.example.hlcandlerl.broker.VirtualPerpBroker
import com.example.hlcandlerl.data.*
import com.example.hlcandlerl.exchange.HyperLiquidCandleWsClient
import com.example.hlcandlerl.exchange.HyperLiquidInfoClient
import com.example.hlcandlerl.features.OhlcvFeatureBuilder
import com.example.hlcandlerl.rl.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

data class EngineUiState(
    val status: String = "idle",
    val phase: String = "idle",
    val running: Boolean = false,
    val market: String = "BTC",
    val coin: String = "BTC",
    val markets: Map<String, String> = emptyMap(),
    val policy: String = "Masked Double Q-learning from OHLCV + public perp state",
    val learningRule: String = "epsilon-greedy actions + replay + executable-equity reward",
    val interval: String = "1m",
    val close: Double = 0.0,
    val candleVolume: Double = 0.0,
    val action: String = "WAIT",
    val reason: String = "",
    val reward: Double = 0.0,
    val equity: Double = 0.0,
    val realizedPnl: Double = 0.0,
    val position: String = "flat",
    val replay: Int = 0,
    val updates: Int = 0,
    val epsilon: Double = 0.0,
    val qValues: String = "",
    val candleUpdates: Long = 0,
    val candleAgeMs: Long = 0,
    val openInterest: Double = 0.0,
    val premiumBps: Double = 0.0,
    val markPx: Double = 0.0,
    val oraclePx: Double = 0.0,
    val offlineCandles: Int = 0,
    val offlineRound: Int = 0,
    val offlineRounds: Int = 0,
    val offlineReport: String = "",
    val processedCandles: Int = 0,
    val totalCandles: Int = 0,
    val actionCounts: String = "",
    val elapsedMs: Long = 0,
    val etaMs: Long = 0,
    val storageBytes: Long = 0,
    val downloadActive: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadDetail: String = "",
    val trainActive: Boolean = false,
    val trainProgress: Float = 0f,
    val trainDetail: String = "",
    val marketSwitching: Boolean = false
)

class RlEngine(
    defaultMarketLabel: String = "BTC",
    private val markets: Map<String, String> = mapOf("BTC" to "BTC"),
    private val infoClient: HyperLiquidInfoClient = HyperLiquidInfoClient()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var decisionJob: Job? = null
    private var offlineJob: Job? = null
    private var marketLabel: String = defaultMarketLabel
    private var coin: String = markets.getValue(defaultMarketLabel)
    private val interval = "1m"
    private val featureBuilder = OhlcvFeatureBuilder(window = 32)
    private val broker = VirtualPerpBroker()
    private val learner = MaskedDoubleQLearner(inputDim = featureBuilder.featureSize)
    private val replay = ReplayBuffer(capacity = REPLAY_CAPACITY)
    private var ws: HyperLiquidCandleWsClient? = null
    @Volatile private var latestCandle: Candle? = null
    private var context: PerpContext = PerpContext()
    private var lastEquity: Double? = null
    private var pendingState: FloatArray? = null
    private var pendingAction: Int? = null
    private var pendingDone: Boolean = false
    private var lastDecisionFrameKey: String = ""
    @Volatile private var lastSeenFrameKey: String = ""
    private var stepNo: Long = 0
    private var persistenceDir: File? = null
    private var replayAppendFile: File? = null
    private var candleDataFile: File? = null
    private var loadedReplay = false
    private var persistenceRoot: File? = null
    private var offlineMode: Boolean = false
    @Volatile private var candleUpdates: Long = 0L
    @Volatile private var lastCandleWallMillis: Long = 0L
    private var lastUiPublishMs: Long = 0L
    private var appendsSinceSizeCheck: Int = 0

    private val _state = MutableStateFlow(EngineUiState(market = marketLabel, coin = coin, markets = markets, interval = interval))
    val state: StateFlow<EngineUiState> = _state

    fun attachPersistenceDir(dir: File) {
        val root = dir.also { it.mkdirs() }
        // Attaching is idempotent and must never disturb work already in flight: the Activity calls
        // it on entry while the Service may already be training or streaming.
        if (persistenceRoot == root && persistenceDir != null) return
        persistenceRoot = root
        configureMarketPersistence()
    }

    fun setMarket(label: String) {
        if (busy()) return
        val newCoin = markets[label] ?: return
        _state.update { it.copy(status = "switching to $label", marketSwitching = true) }
        scope.launch {
            try {
            marketLabel = label
            coin = newCoin
            // Switching stays off the disk entirely. The persisted replay is only restored when the
            // learner actually starts, so a market change can never block on a large file.
            loadedReplay = false
            replay.clear()
            broker.reset()
            learner.reset()
            clearRuntime()
            configureMarketPersistence()
            _state.update {
                EngineUiState(
                    status = "market changed to $label",
                    market = marketLabel,
                    coin = coin,
                    markets = markets,
                    interval = interval,
                    epsilon = learner.epsilon,
                    marketSwitching = false
                )
            }
            publishStorageStats()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = "market switch failed: ${e.javaClass.simpleName}", marketSwitching = false) }
            }
        }
    }

    /**
     * Guard for every user action. It asks whether a job is still *running*, not merely whether a
     * handle was left behind: a completed-but-unnulled handle used to make Start, Download, Train,
     * Delete and market switching permanent no-ops with no way back except killing the app.
     */
    private fun busy(): Boolean =
        _state.value.running ||
            decisionJob?.isActive == true ||
            offlineJob?.isActive == true ||
            _state.value.marketSwitching

    private fun configureMarketPersistence() {
        val root = persistenceRoot ?: return
        val safe = coin.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        persistenceDir = File(root, safe).also { it.mkdirs() }
        replayAppendFile = File(persistenceDir, "replay.jsonl")
        candleDataFile = File(persistenceDir, "candles_${interval}.jsonl")
        publishStorageStats()
    }

    /** Reads stored candle count and disk usage off the main thread so a relaunch reflects reality. */
    private fun publishStorageStats() {
        val dir = persistenceDir ?: return
        val file = candleDataFile ?: return
        val coinAtRequest = coin
        scope.launch(Dispatchers.IO) {
            val count = try {
                if (file.exists()) file.useLines { lines -> lines.count { it.isNotBlank() } } else 0
            } catch (_: Exception) { 0 }
            val bytes = try { dir.listFiles()?.sumOf { it.length() } ?: 0L } catch (_: Exception) { 0L }
            // Atomic CAS update: a plain read-modify-write here can clobber a concurrent
            // market-switch/reset state assignment and silently drop its fields.
            _state.update { current ->
                if (coinAtRequest == coin && !current.downloadActive && !current.trainActive) {
                    current.copy(offlineCandles = count, storageBytes = bytes)
                } else current
            }
        }
    }

    fun policyFile(): File? = persistenceDir?.let { File(it, "policy.json") }
    fun summaryFile(): File? = persistenceDir?.let { File(it, "offline_summary.json") }
    fun exportPolicyJson(): String = learner.snapshotJson()

    fun downloadOfflineCandles(days: Int = 1) {
        if (busy()) return
        offlineJob = scope.launch {
            _state.update {
                it.copy(
                    status = "downloading ${days}d candles",
                    phase = "downloading",
                    downloadActive = true,
                    downloadProgress = 0.05f,
                    downloadDetail = "Requesting HyperLiquid candleSnapshot for $coin..."
                )
            }
            try {
                val candles = withContext(Dispatchers.IO) { infoClient.loadRecentCandles(coin, interval, days * 24L * 3_600_000L) }
                _state.update {
                    it.copy(
                        status = "download received; saving",
                        downloadProgress = 0.75f,
                        downloadDetail = "Received ${candles.size} candles; writing phone storage..."
                    )
                }
                val file = candleDataFile ?: error("no storage directory attached")
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    // Streamed so a large snapshot never becomes one giant String in memory.
                    file.bufferedWriter().use { w ->
                        candles.forEach { c -> w.write(candleToJson(c).toString()); w.newLine() }
                    }
                }
                _state.update {
                    it.copy(
                        status = "downloaded ${candles.size} candles",
                        offlineCandles = candles.size,
                        downloadProgress = 1f,
                        // Deliberately not phrased as a full window: one candleSnapshot request
                        // usually returns fewer candles than the days requested.
                        downloadDetail = "Downloaded ${candles.size} candles (requested ${days}d)."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        status = "download failed: ${e.javaClass.simpleName}",
                        downloadProgress = 0f,
                        downloadDetail = "Download failed: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            } finally {
                offlineJob = null
                _state.update { it.copy(downloadActive = false, phase = "idle") }
                publishStorageStats()
            }
        }
    }

    fun deleteDownloadedData() {
        if (busy()) {
            _state.update { it.copy(status = "stop the learner/training before deleting data") }
            return
        }
        val deleted = candleDataFile?.takeIf { it.exists() }?.delete() == true
        _state.update {
            it.copy(
                status = if (deleted) "downloaded candle data deleted" else "no downloaded candle data",
                phase = "idle",
                offlineCandles = 0,
                offlineRound = 0,
                offlineRounds = 0,
                offlineReport = "",
                processedCandles = 0,
                totalCandles = 0,
                downloadActive = false,
                downloadProgress = 0f,
                downloadDetail = "",
                trainActive = false,
                trainProgress = 0f,
                trainDetail = ""
            )
        }
        publishStorageStats()
    }

    /** Cancels an in-flight offline training run. The run clears its own busy state in `finally`. */
    fun stopOfflineTraining() {
        val job = offlineJob ?: return
        _state.update { it.copy(status = "stopping offline training", trainDetail = "Stopping...") }
        job.cancel()
    }

    fun offlineTrain(rounds: Int = 1) {
        if (busy()) return
        offlineJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            _state.update {
                it.copy(
                    status = "loading offline candle file",
                    phase = "loading",
                    trainActive = true,
                    trainProgress = 0.01f,
                    trainDetail = "Reading downloaded candles from phone storage..."
                )
            }
            try {
                val candles = withContext(Dispatchers.IO) { loadStoredCandles() }
                if (candles.size < 40) {
                    _state.update {
                        it.copy(
                            status = "need downloaded candles first",
                            offlineCandles = candles.size,
                            trainActive = false,
                            trainProgress = 0f,
                            trainDetail = "Download candles before offline training."
                        )
                    }
                    return@launch
                }
                withContext(Dispatchers.IO) { restorePersistedPolicy() }
                context = try {
                    withContext(Dispatchers.IO) { infoClient.loadContext(coin) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    PerpContext()
                }
                offlineMode = true
                repeat(rounds) { idx ->
                    _state.update {
                        it.copy(
                            status = "offline training round ${idx + 1}/$rounds",
                            phase = "training",
                            offlineCandles = candles.size,
                            offlineRound = idx + 1,
                            offlineRounds = rounds,
                            totalCandles = candles.size,
                            processedCandles = 0,
                            trainActive = true,
                            trainProgress = idx.toFloat() / rounds.toFloat(),
                            trainDetail = "Round ${idx + 1}/$rounds: 0/${candles.size} candles"
                        )
                    }
                    val report = runOfflineEpoch(candles, idx, rounds, startedAt)
                    _state.update {
                        it.copy(
                            status = "offline round ${idx + 1}/$rounds complete",
                            offlineReport = report,
                            replay = replay.size(),
                            updates = learner.updates,
                            epsilon = learner.epsilon,
                            trainProgress = (idx + 1).toFloat() / rounds.toFloat(),
                            trainDetail = "Round ${idx + 1}/$rounds complete"
                        )
                    }
                    withContext(Dispatchers.IO) {
                        savePolicyBestEffort()
                        saveOfflineSummary(report, rounds, candles.size, startedAt)
                    }
                }
                _state.update {
                    it.copy(
                        trainActive = false,
                        trainProgress = 1f,
                        processedCandles = it.totalCandles,
                        trainDetail = "Offline training complete",
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        etaMs = 0
                    )
                }
            } catch (e: CancellationException) {
                // Cooperative stop: keep whatever the run already learned and checkpoint it.
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { savePolicyBestEffort() }
                    _state.update {
                        it.copy(
                            status = "offline training stopped",
                            trainActive = false,
                            trainDetail = "Stopped after ${it.processedCandles}/${it.totalCandles} candles; policy kept."
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        status = "offline training failed: ${e.javaClass.simpleName}",
                        trainActive = false,
                        trainProgress = 0f,
                        trainDetail = "Offline training failed: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            } finally {
                offlineMode = false
                offlineJob = null
                _state.update { it.copy(phase = "idle", trainActive = false) }
                publishStorageStats()
            }
        }
    }

    fun importPolicyJson(json: String) {
        learner.restoreJson(json)
        _state.update { it.copy(updates = learner.updates, epsilon = learner.epsilon, status = "policy restored") }
    }

    /**
     * Restores policy.json when it matches the current feature size. A policy saved before a feature
     * change carries a different inputDim; it is archived and the learner starts fresh instead of
     * throwing on every start.
     */
    fun restorePersistedPolicy() {
        val file = policyFile() ?: return
        if (!file.exists()) return
        try {
            learner.restoreJson(file.readText())
            _state.update { it.copy(updates = learner.updates, epsilon = learner.epsilon, status = "policy restored") }
        } catch (_: Exception) {
            archive(file)
            learner.reset()
            _state.update {
                it.copy(
                    updates = learner.updates,
                    epsilon = learner.epsilon,
                    status = "incompatible policy archived; starting fresh"
                )
            }
        }
    }

    private fun archive(file: File): Boolean = try {
        val ts = System.currentTimeMillis()
        file.renameTo(File(file.parentFile, "${file.nameWithoutExtension}.archive-$ts.${file.extension}"))
    } catch (_: Exception) { false }

    fun resetLearning() {
        val wasRunning = _state.value.running
        stop()
        try {
            persistenceDir?.let { dir ->
                listOf("policy.json", "replay.jsonl", "offline_summary.json").forEach { name ->
                    val f = File(dir, name)
                    if (f.exists()) archive(f)
                }
            }
        } catch (_: Exception) { }
        broker.reset()
        learner.reset()
        replay.clear()
        clearRuntime()
        loadedReplay = true
        _state.update {
            EngineUiState(
                status = if (wasRunning) "learning reset; press Start" else "learning reset",
                running = false,
                market = marketLabel,
                coin = coin,
                markets = markets,
                interval = interval
            )
        }
        publishStorageStats()
    }

    private fun clearRuntime(clearContext: Boolean = true) {
        latestCandle = null
        if (clearContext) context = PerpContext()
        lastEquity = null
        pendingState = null
        pendingAction = null
        pendingDone = false
        lastDecisionFrameKey = ""
        lastSeenFrameKey = ""
        stepNo = 0L
        candleUpdates = 0L
        lastCandleWallMillis = 0L
        featureBuilder.reset()
    }

    /**
     * Rewrites the persisted replay to the newest [PERSISTED_REPLAY_ROWS] rows. Rows are ~9 KB, so
     * the file is deliberately capped: rewriting the whole buffer as one joined String used to
     * allocate >100 MB and throw OutOfMemoryError on a phone.
     */
    fun compactReplayFile() {
        val file = replayAppendFile ?: return
        try {
            val rows = replay.snapshot().takeLast(PERSISTED_REPLAY_ROWS)
            val tmp = File(file.parentFile, "replay.jsonl.tmp")
            tmp.bufferedWriter().use { w ->
                rows.forEach { t ->
                    w.write(t.toJsonLine())
                    w.newLine()
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            _state.update { it.copy(status = "replay checkpoint failed") }
        }
    }

    fun start() {
        if (busy()) return
        _state.update { it.copy(status = "loading HyperLiquid context", phase = "starting", running = false) }
        decisionJob = scope.launch {
            withContext(Dispatchers.IO) {
                restorePersistedPolicy()
                loadReplayOnce()
            }
            lateinit var history: List<Candle>
            try {
                context = withContext(Dispatchers.IO) { infoClient.loadContext(coin) }
                history = withContext(Dispatchers.IO) { infoClient.loadRecentCandles(coin, interval) }
            } catch (e: CancellationException) {
                decisionJob = null
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(status = "context/history load failed: ${e.javaClass.simpleName}; refusing to train", phase = "idle", running = false)
                }
                decisionJob = null
                return@launch
            }
            clearRuntime(clearContext = false)
            featureBuilder.seed(history)
            _state.update { it.copy(status = "starting", phase = "live", running = true) }
            val socket = HyperLiquidCandleWsClient(
                coin = coin,
                interval = interval,
                onCandle = {
                    if (it.coin == coin) {
                        val key = candleKey(it)
                        if (key != lastSeenFrameKey) {
                            latestCandle = it
                            lastSeenFrameKey = key
                            candleUpdates += 1
                            lastCandleWallMillis = System.currentTimeMillis()
                        }
                    }
                },
                onStatus = { s -> _state.update { st -> st.copy(status = s) } }
            )
            // The socket is created after the last suspension point, so a job cancelled during the
            // context/history load could previously open one and then exit the loop without closing
            // it. An orphan reconnects forever on its own thread. Tie its lifetime to this job.
            ws?.close()
            ws = socket
            try {
                if (!isActive) return@launch
                socket.connect()
                runDecisionLoop()
            } finally {
                socket.close()
                if (ws === socket) ws = null
                // The loop can end by itself after repeated failures, not only via stop().
                if (decisionJob === coroutineContext[Job]) decisionJob = null
                _state.update { if (it.running) it.copy(running = false, phase = "idle") else it }
            }
        }
    }

    private suspend fun runDecisionLoop() {
            var consecutiveFailures = 0
            while (coroutineContext.isActive) {
                // An exception escaping a coroutine launched on this scope would reach the default
                // handler and kill the process. A decision step must never do that.
                try {
                    tick()
                    consecutiveFailures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveFailures++
                    _state.update { it.copy(status = "decision step failed: ${e.javaClass.simpleName} (x$consecutiveFailures)") }
                    if (consecutiveFailures >= MAX_CONSECUTIVE_TICK_FAILURES) {
                        _state.update { it.copy(status = "learner stopped after repeated errors", phase = "idle", running = false) }
                        break
                    }
                }
                delay(1000)
            }
    }

    fun stop() {
        ws?.close()
        ws = null
        decisionJob?.cancel()
        decisionJob = null
        _state.update { it.copy(status = "stopped", phase = "idle", running = false) }
    }

    private suspend fun tick() {
        val candle = latestCandle ?: return
        val key = candleKey(candle)
        if (key == lastDecisionFrameKey) return
        lastDecisionFrameKey = key
        stepNo++
        if (stepNo % 60L == 0L) {
            try { context = withContext(Dispatchers.IO) { infoClient.loadContext(coin) } } catch (_: Exception) { }
        }
        val frame = MarketFrame(candle, context)
        val stateBeforeAction = featureBuilder.build(frame, broker.position, stepNo) ?: return
        val firstDecision = lastEquity == null
        val previousEquity = lastEquity ?: broker.equity(frame)

        pendingState?.let { ps ->
            pendingAction?.let { pa ->
                val intervalReward = broker.equity(frame) - previousEquity
                val transition = Transition(ps, pa, intervalReward, stateBeforeAction, broker.validMask(), pendingDone)
                replay.add(transition)
                appendTransition(transition)
                if (replay.size() >= 64) learner.train(replay.sample(TRAIN_BATCH))
            }
        }

        val mask = broker.validMask()
        val actionIdx = learner.select(stateBeforeAction, mask, explore = true)
        val action = Action.entries[actionIdx]
        val result = broker.step(action, frame, stepNo)
        lastEquity = result.equity
        val stateAfterAction = featureBuilder.patchPosition(stateBeforeAction, broker.position, stepNo)

        if (isTradeReason(result.reason)) {
            val transition = Transition(stateBeforeAction, result.action.ordinal, result.reward, stateAfterAction, broker.validMask(), isExitReason(result.reason))
            replay.add(transition)
            appendTransition(transition)
            if (replay.size() >= 64) learner.train(replay.sample(TRAIN_BATCH))
        }

        if (firstDecision && broker.position == null && result.reason == "wait_flat") {
            pendingState = null
            pendingAction = null
            pendingDone = false
        } else {
            pendingState = stateAfterAction
            pendingAction = if (broker.position == null) Action.WAIT.ordinal else Action.HOLD.ordinal
            pendingDone = false
        }

        val q = learner.qValues(stateBeforeAction).joinToString(prefix = "[", postfix = "]") { "%.3f".format(it) }
        _state.update {
            it.copy(
                status = "running",
                phase = "live",
                running = true,
                market = marketLabel,
                coin = coin,
                markets = markets,
                interval = interval,
                close = candle.close,
                candleVolume = candle.volume,
                action = action.name,
                reason = result.reason,
                reward = result.reward,
                equity = result.equity,
                realizedPnl = broker.cashPnl,
                position = broker.position?.let { p -> "${p.side} @ ${"%.2f".format(p.entryPx)}" } ?: "flat",
                replay = replay.size(),
                updates = learner.updates,
                epsilon = learner.epsilon,
                qValues = q,
                candleUpdates = candleUpdates,
                candleAgeMs = if (lastCandleWallMillis == 0L) 0L else System.currentTimeMillis() - lastCandleWallMillis,
                openInterest = context.openInterest,
                premiumBps = context.premium * 10_000.0,
                markPx = context.markPx,
                oraclePx = context.oraclePx
            )
        }
    }

    private suspend fun runOfflineEpoch(candles: List<Candle>, roundIndex: Int, totalRounds: Int, startedAtMs: Long): String {
        broker.reset()
        featureBuilder.reset()
        lastEquity = null
        pendingState = null
        pendingAction = null
        pendingDone = false
        stepNo = 0L
        var rewardSum = 0.0
        var positive = 0
        var negative = 0
        var entries = 0
        var exits = 0
        var peak = 0.0
        var maxDrawdown = 0.0
        var frames = 0
        var sinceTrain = 0
        val actionCounts = IntArray(Action.entries.size)
        for ((idx, c) in candles.withIndex()) {
            // Cooperative cancellation: "Stop training" must take effect within a candle, not a round.
            coroutineContext.ensureActive()
            publishTrainingProgress(idx, candles.size, roundIndex, totalRounds, startedAtMs, actionCounts, idx == candles.lastIndex)
            stepNo++
            val frame = MarketFrame(c, context)
            val stateBefore = featureBuilder.build(frame, broker.position, stepNo) ?: continue
            frames++
            val first = lastEquity == null
            val prevEq = lastEquity ?: broker.equity(frame)
            pendingState?.let { ps ->
                pendingAction?.let { pa ->
                    val r = broker.equity(frame) - prevEq
                    rewardSum += r
                    if (r > 0) positive++ else if (r < 0) negative++
                    replay.add(Transition(ps, pa, r, stateBefore, broker.validMask(), pendingDone))
                }
            }
            val actionIdx = learner.select(stateBefore, broker.validMask(), explore = true)
            actionCounts[actionIdx]++
            val result = broker.step(Action.entries[actionIdx], frame, stepNo)
            lastEquity = result.equity
            val stateAfter = featureBuilder.patchPosition(stateBefore, broker.position, stepNo)
            if (isTradeReason(result.reason)) {
                if (result.reason.startsWith("enter")) entries++ else exits++
                rewardSum += result.reward
                if (result.reward > 0) positive++ else if (result.reward < 0) negative++
                replay.add(Transition(stateBefore, result.action.ordinal, result.reward, stateAfter, broker.validMask(), isExitReason(result.reason)))
            }
            // Batched updates: training on every generated transition pinned the CPU and produced
            // tens of thousands of gradient steps per round.
            sinceTrain++
            if (sinceTrain >= TRAIN_EVERY_CANDLES && replay.size() >= 64) {
                learner.train(replay.sample(TRAIN_BATCH))
                sinceTrain = 0
            }
            if (first && broker.position == null && result.reason == "wait_flat") {
                pendingState = null; pendingAction = null; pendingDone = false
            } else {
                pendingState = stateAfter
                pendingAction = if (broker.position == null) Action.WAIT.ordinal else Action.HOLD.ordinal
                pendingDone = false
            }
            val eq = broker.equity(frame)
            if (eq > peak) peak = eq
            val dd = eq - peak
            if (dd < maxDrawdown) maxDrawdown = dd
        }
        val finalEq = candles.lastOrNull()?.let { broker.equity(MarketFrame(it, context)) } ?: 0.0
        val actions = actionSummary(actionCounts)
        return "frames $frames trainEq ${"%+.2f".format(finalEq)} reward ${"%+.2f".format(rewardSum)} " +
            "+$positive/-$negative trades $entries/$exits maxDD ${"%.2f".format(maxDrawdown)} actions $actions"
    }

    private fun actionSummary(counts: IntArray): String =
        Action.entries.joinToString("/") { "${it.name.first()}=${counts[it.ordinal]}" }

    /** Time-throttled progress publishing; per-candle StateFlow writes made the UI drop frames. */
    private fun publishTrainingProgress(
        idx: Int,
        total: Int,
        roundIndex: Int,
        totalRounds: Int,
        startedAtMs: Long,
        actionCounts: IntArray,
        force: Boolean
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUiPublishMs < UI_THROTTLE_MS) return
        lastUiPublishMs = now
        val done = roundIndex.toFloat() + (idx + 1).toFloat() / total.toFloat()
        val progress = (done / totalRounds.toFloat()).coerceIn(0f, 1f)
        val elapsed = now - startedAtMs
        val eta = if (progress > 0.01f) ((elapsed / progress) - elapsed).toLong().coerceAtLeast(0L) else 0L
        _state.update {
            it.copy(
                trainProgress = progress,
                trainDetail = "Round ${roundIndex + 1}/$totalRounds: ${idx + 1}/$total candles",
                processedCandles = idx + 1,
                totalCandles = total,
                replay = replay.size(),
                updates = learner.updates,
                epsilon = learner.epsilon,
                actionCounts = actionSummary(actionCounts),
                elapsedMs = elapsed,
                etaMs = eta
            )
        }
    }

    private fun isTradeReason(reason: String) = reason.startsWith("enter") || isExitReason(reason)
    private fun isExitReason(reason: String) = reason == "exit" || reason == "forced_exit_max_hold"

    private fun savePolicyBestEffort() {
        try {
            val target = policyFile() ?: return
            val parent = target.parentFile ?: return
            parent.mkdirs()
            val tmp = parent.resolve("policy.json.tmp")
            tmp.writeText(exportPolicyJson())
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        } catch (_: Exception) { }
    }

    /** Compact, readable record of an offline run; replaces persisting the raw transitions. */
    private fun saveOfflineSummary(report: String, rounds: Int, candles: Int, startedAtMs: Long) {
        try {
            val target = summaryFile() ?: return
            val json = JSONObject()
                .put("coin", coin)
                .put("interval", interval)
                .put("candles", candles)
                .put("rounds", rounds)
                .put("report", report)
                .put("updates", learner.updates)
                .put("epsilon", learner.epsilon)
                .put("replayInMemory", replay.size())
                .put("elapsedMs", System.currentTimeMillis() - startedAtMs)
                .put("finishedAtMillis", System.currentTimeMillis())
            target.writeText(json.toString())
        } catch (_: Exception) { }
    }

    internal fun loadStoredCandles(): List<Candle> {
        val file = candleDataFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            file.useLines { lines ->
                lines.mapNotNull { line ->
                    if (line.isBlank()) null else try { candleFromJson(JSONObject(line)) } catch (_: Exception) { null }
                }.toList()
            }.sortedBy { it.openTimeMillis }
        } catch (_: Exception) { emptyList() }
    }

    private fun candleToJson(c: Candle): JSONObject = JSONObject()
        .put("coin", c.coin).put("interval", c.interval)
        .put("openTimeMillis", c.openTimeMillis).put("closeTimeMillis", c.closeTimeMillis)
        .put("open", c.open).put("high", c.high).put("low", c.low).put("close", c.close)
        .put("volume", c.volume).put("trades", c.trades)

    private fun candleFromJson(o: JSONObject): Candle = Candle(
        coin = o.getString("coin"), interval = o.getString("interval"),
        openTimeMillis = o.getLong("openTimeMillis"), closeTimeMillis = o.getLong("closeTimeMillis"),
        open = o.getDouble("open"), high = o.getDouble("high"), low = o.getDouble("low"), close = o.getDouble("close"),
        volume = o.getDouble("volume"), trades = o.optInt("trades", 0)
    )

    private fun candleKey(c: Candle): String = "${c.openTimeMillis}:${c.close}:${c.high}:${c.low}:${c.volume}:${c.trades}"

    private fun appendTransition(t: Transition) {
        // Offline epochs generate rounds x candles transitions of ~9 KB each; persisting them wrote
        // >150 MB per market per run. Offline training keeps its replay in memory and persists only
        // the policy plus a compact summary.
        if (offlineMode) return
        try {
            val file = replayAppendFile ?: return
            file.appendText(t.toJsonLine() + "\n")
            // Self-bound the live log from the engine's own background coroutine, so stopping the
            // service never has to compact a multi-MB file on the main thread.
            if (++appendsSinceSizeCheck >= APPEND_SIZE_CHECK_EVERY) {
                appendsSinceSizeCheck = 0
                if (file.length() > REPLAY_FILE_MAX_BYTES) compactReplayFile()
            }
        } catch (_: Exception) { _state.update { it.copy(status = "replay append failed") } }
    }

    internal fun loadReplayOnce() {
        if (loadedReplay) return
        loadedReplay = true
        val file = replayAppendFile ?: return
        if (!file.exists()) return
        try {
            if (file.length() > REPLAY_FILE_MAX_BYTES) {
                // Never parse an oversized replay log; archive it and start the buffer clean.
                archive(file)
                _state.update { it.copy(status = "oversized replay archived; starting clean") }
                return
            }
            var kept = 0
            var skipped = 0
            val tail = ArrayDeque<String>(PERSISTED_REPLAY_ROWS)
            file.useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        if (tail.size == PERSISTED_REPLAY_ROWS) tail.removeFirst()
                        tail.addLast(line)
                    }
                }
            }
            tail.forEach { line ->
                val t = try { Transition.fromJsonLine(line) } catch (_: Exception) { null }
                if (t != null && t.state.size == featureBuilder.featureSize && t.nextState.size == featureBuilder.featureSize && t.nextMask.size == Action.entries.size) {
                    replay.add(t)
                    kept++
                } else {
                    skipped++
                }
            }
            _state.update {
                it.copy(replay = replay.size(), status = "replay restored: $kept${if (skipped > 0) ", skipped $skipped old rows" else ""}")
            }
        } catch (_: Exception) {
            _state.update { it.copy(status = "replay restore failed") }
        }
    }

    companion object {
        /** In-memory transitions. 5k rows of ~1.9 KB is ~10 MB; 20k used to dominate the app heap. */
        const val REPLAY_CAPACITY = 5_000
        /** Rows kept on disk for live learning. Rows are ~9 KB, so this caps the file near 18 MB. */
        const val PERSISTED_REPLAY_ROWS = 2_000
        const val REPLAY_FILE_MAX_BYTES = 25_000_000L
        private const val UI_THROTTLE_MS = 250L
        private const val TRAIN_EVERY_CANDLES = 8
        private const val TRAIN_BATCH = 32
        private const val APPEND_SIZE_CHECK_EVERY = 500
        private const val MAX_CONSECUTIVE_TICK_FAILURES = 5
    }
}
