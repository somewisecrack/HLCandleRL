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
import org.json.JSONObject
import java.io.File

 data class EngineUiState(
    val status: String = "idle",
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
    val offlineReport: String = "",
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
    private val markets: Map<String, String> = mapOf("BTC" to "BTC")
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var decisionJob: Job? = null
    private var offlineJob: Job? = null
    private var marketLabel: String = defaultMarketLabel
    private var coin: String = markets.getValue(defaultMarketLabel)
    private val interval = "1m"
    private val featureBuilder = OhlcvFeatureBuilder(window = 32)
    private val broker = VirtualPerpBroker()
    private val infoClient = HyperLiquidInfoClient()
    private val learner = MaskedDoubleQLearner(inputDim = featureBuilder.featureSize)
    private val replay = ReplayBuffer()
    private var ws: HyperLiquidCandleWsClient? = null
    private var latestCandle: Candle? = null
    private var context: PerpContext = PerpContext()
    private var lastEquity: Double? = null
    private var pendingState: FloatArray? = null
    private var pendingAction: Int? = null
    private var pendingDone: Boolean = false
    private var lastDecisionCandleTimeMillis: Long = 0L
    private var lastDecisionFrameKey: String = ""
    private var lastSeenFrameKey: String = ""
    private var stepNo: Long = 0
    private var persistenceDir: File? = null
    private var replayAppendFile: File? = null
    private var candleDataFile: File? = null
    private var loadedReplay = false
    private var persistenceRoot: File? = null
    private var candleUpdates: Long = 0L
    private var lastCandleWallMillis: Long = 0L

    private val _state = MutableStateFlow(EngineUiState(market = marketLabel, coin = coin, markets = markets, interval = interval))
    val state: StateFlow<EngineUiState> = _state

    fun attachPersistenceDir(dir: File) {
        persistenceRoot = dir.also { it.mkdirs() }
        configureMarketPersistence()
    }

    fun setMarket(label: String) {
        if (_state.value.running || decisionJob != null || offlineJob != null || _state.value.marketSwitching) return
        val newCoin = markets[label] ?: return
        _state.value = _state.value.copy(status = "switching to $label", marketSwitching = true)
        scope.launch {
            marketLabel = label
            coin = newCoin
            loadedReplay = false
            replay.clear()
            broker.reset()
            learner.reset()
            clearRuntime()
            configureMarketPersistence()
            withContext(Dispatchers.IO) { loadReplayOnce() }
            _state.value = EngineUiState(
                status = "market changed to $label",
                market = marketLabel,
                coin = coin,
                markets = markets,
                interval = interval,
                replay = replay.size(),
                epsilon = learner.epsilon,
                marketSwitching = false
            )
        }
    }

    private fun configureMarketPersistence() {
        val root = persistenceRoot ?: return
        val safe = coin.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        persistenceDir = File(root, safe).also { it.mkdirs() }
        replayAppendFile = File(persistenceDir, "replay.jsonl")
        candleDataFile = File(persistenceDir, "candles_${interval}.jsonl")
    }

    fun policyFile(): File? = persistenceDir?.let { File(it, "policy.json") }
    fun exportPolicyJson(): String = learner.snapshotJson()

    fun downloadOfflineCandles(days: Int = 7) {
        if (_state.value.running || decisionJob != null || offlineJob != null) return
        offlineJob = scope.launch {
            _state.value = _state.value.copy(
                status = "downloading ${days}d candles",
                downloadActive = true,
                downloadProgress = 0.05f,
                downloadDetail = "Requesting HyperLiquid candleSnapshot for $coin..."
            )
            try {
                val candles = withContext(Dispatchers.IO) { infoClient.loadRecentCandles(coin, interval, days * 24L * 3_600_000L) }
                _state.value = _state.value.copy(
                    status = "download received; saving",
                    downloadProgress = 0.75f,
                    downloadDetail = "Received ${candles.size} candles; writing phone storage..."
                )
                val file = candleDataFile ?: return@launch
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    file.writeText(candles.joinToString("\n", postfix = "\n") { candleToJson(it).toString() })
                }
                _state.value = _state.value.copy(
                    status = "downloaded ${candles.size} candles",
                    offlineCandles = candles.size,
                    downloadActive = false,
                    downloadProgress = 1f,
                    downloadDetail = "Saved ${candles.size} candles for offline training."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    status = "download failed: ${e.javaClass.simpleName}",
                    downloadActive = false,
                    downloadProgress = 0f,
                    downloadDetail = "Download failed: ${e.message ?: e.javaClass.simpleName}"
                )
            } finally { offlineJob = null }
        }
    }

    fun deleteDownloadedData() {
        if (_state.value.running || decisionJob != null || offlineJob != null) return
        val deleted = candleDataFile?.takeIf { it.exists() }?.delete() == true
        _state.value = _state.value.copy(
            status = if (deleted) "downloaded candle data deleted" else "no downloaded candle data",
            offlineCandles = 0,
            offlineRound = 0,
            offlineReport = "",
            downloadActive = false,
            downloadProgress = 0f,
            downloadDetail = "",
            trainActive = false,
            trainProgress = 0f,
            trainDetail = ""
        )
    }

    fun offlineTrain(rounds: Int = 5) {
        if (_state.value.running || decisionJob != null || offlineJob != null) return
        offlineJob = scope.launch {
            _state.value = _state.value.copy(
                status = "loading offline candle file",
                trainActive = true,
                trainProgress = 0.01f,
                trainDetail = "Reading downloaded candles from phone storage..."
            )
            val candles = withContext(Dispatchers.IO) { loadStoredCandles() }
            if (candles.size < 40) {
                _state.value = _state.value.copy(
                    status = "need downloaded candles first",
                    offlineCandles = candles.size,
                    trainActive = false,
                    trainProgress = 0f,
                    trainDetail = "Download candles before offline training."
                )
                offlineJob = null
                return@launch
            }
            try {
                context = withContext(Dispatchers.IO) { infoClient.loadContext(coin) }
            } catch (_: Exception) {
                context = PerpContext()
            }
            repeat(rounds) { idx ->
                _state.value = _state.value.copy(
                    status = "offline training round ${idx + 1}/$rounds",
                    offlineCandles = candles.size,
                    offlineRound = idx + 1,
                    trainActive = true,
                    trainProgress = idx.toFloat() / rounds.toFloat(),
                    trainDetail = "Round ${idx + 1}/$rounds: 0/${candles.size} candles"
                )
                val report = runOfflineEpoch(candles, idx, rounds)
                _state.value = _state.value.copy(
                    status = "offline round ${idx + 1}/$rounds complete",
                    offlineCandles = candles.size,
                    offlineRound = idx + 1,
                    offlineReport = report,
                    replay = replay.size(),
                    updates = learner.updates,
                    epsilon = learner.epsilon,
                    trainActive = idx + 1 < rounds,
                    trainProgress = (idx + 1).toFloat() / rounds.toFloat(),
                    trainDetail = "Round ${idx + 1}/$rounds complete"
                )
                savePolicyBestEffort()
            }
            _state.value = _state.value.copy(trainActive = false, trainProgress = 1f, trainDetail = "Offline training complete")
            offlineJob = null
        }
    }
    fun importPolicyJson(json: String) {
        learner.restoreJson(json)
        _state.value = _state.value.copy(updates = learner.updates, epsilon = learner.epsilon, status = "policy restored")
    }

    fun resetLearning() {
        val wasRunning = _state.value.running
        stop()
        val ts = System.currentTimeMillis()
        try {
            persistenceDir?.let { dir ->
                listOf("policy.json", "replay.jsonl").forEach { name ->
                    val f = File(dir, name)
                    if (f.exists()) f.renameTo(File(dir, "${f.nameWithoutExtension}.archive-$ts.${f.extension}"))
                }
            }
        } catch (_: Exception) { }
        broker.reset()
        learner.reset()
        replay.clear()
        clearRuntime()
        loadedReplay = true
        _state.value = EngineUiState(
            status = if (wasRunning) "learning reset; press Start" else "learning reset",
            running = false,
            market = marketLabel,
            coin = coin,
            markets = markets,
            interval = interval
        )
    }

    private fun clearRuntime(clearContext: Boolean = true) {
        latestCandle = null
        if (clearContext) context = PerpContext()
        lastEquity = null
        pendingState = null
        pendingAction = null
        pendingDone = false
        lastDecisionCandleTimeMillis = 0L
        lastDecisionFrameKey = ""
        lastSeenFrameKey = ""
        stepNo = 0L
        candleUpdates = 0L
        lastCandleWallMillis = 0L
        featureBuilder.reset()
    }

    fun compactReplayFile() {
        val file = replayAppendFile ?: return
        try {
            val tmp = File(file.parentFile, "replay.jsonl.tmp")
            tmp.writeText(replay.snapshot().joinToString(separator = "\n", postfix = "\n") { it.toJsonLine() })
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            _state.value = _state.value.copy(status = "replay checkpoint failed")
        }
    }

    fun start() {
        if (_state.value.running || decisionJob != null || offlineJob != null || _state.value.marketSwitching) return
        _state.value = _state.value.copy(status = "loading HyperLiquid context", running = false)
        decisionJob = scope.launch {
            withContext(Dispatchers.IO) { loadReplayOnce() }
            lateinit var history: List<Candle>
            try {
                context = withContext(Dispatchers.IO) { infoClient.loadContext(coin) }
                history = withContext(Dispatchers.IO) { infoClient.loadRecentCandles(coin, interval) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = "context/history load failed: ${e.javaClass.simpleName}; refusing to train", running = false)
                decisionJob = null
                return@launch
            }
            clearRuntime(clearContext = false)
            featureBuilder.seed(history)
            _state.value = _state.value.copy(
                status = "starting",
                running = true
            )
            ws = HyperLiquidCandleWsClient(
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
                onStatus = { s -> _state.value = _state.value.copy(status = s) }
            ).also { it.connect() }
            while (isActive) {
                tick()
                delay(1000)
            }
        }
    }

    fun stop() {
        ws?.close()
        ws = null
        decisionJob?.cancel()
        decisionJob = null
        _state.value = _state.value.copy(status = "stopped", running = false)
    }

    private suspend fun tick() {
        val candle = latestCandle ?: return
        val key = candleKey(candle)
        if (key == lastDecisionFrameKey) return
        lastDecisionFrameKey = key
        lastDecisionCandleTimeMillis = candle.openTimeMillis
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
                if (replay.size() >= 64) learner.train(replay.sample(32))
            }
        }

        val mask = broker.validMask()
        val actionIdx = learner.select(stateBeforeAction, mask, explore = true)
        val action = Action.entries[actionIdx]
        val result = broker.step(action, frame, stepNo)
        lastEquity = result.equity
        val stateAfterAction = featureBuilder.patchPosition(stateBeforeAction, broker.position, stepNo)

        if (result.reason.startsWith("enter") || result.reason == "exit" || result.reason == "forced_exit_max_hold") {
            val effectiveIdx = result.action.ordinal
            val transition = Transition(stateBeforeAction, effectiveIdx, result.reward, stateAfterAction, broker.validMask(), result.reason == "exit" || result.reason == "forced_exit_max_hold")
            replay.add(transition)
            appendTransition(transition)
            if (replay.size() >= 64) learner.train(replay.sample(32))
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
        _state.value = EngineUiState(
            status = "running",
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
            position = broker.position?.let { "${it.side} @ ${"%.2f".format(it.entryPx)}" } ?: "flat",
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

    private fun runOfflineEpoch(candles: List<Candle>, roundIndex: Int, totalRounds: Int): String {
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
        val actionCounts = IntArray(Action.entries.size)
        for ((idx, c) in candles.withIndex()) {
            if (idx % 250 == 0 || idx == candles.lastIndex) {
                val completed = roundIndex.toFloat() + (idx + 1).toFloat() / candles.size.toFloat()
                _state.value = _state.value.copy(
                    trainProgress = (completed / totalRounds.toFloat()).coerceIn(0f, 1f),
                    trainDetail = "Round ${roundIndex + 1}/$totalRounds: ${idx + 1}/${candles.size} candles",
                    replay = replay.size(),
                    updates = learner.updates,
                    epsilon = learner.epsilon
                )
            }
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
                    val t = Transition(ps, pa, r, stateBefore, broker.validMask(), pendingDone)
                    replay.add(t); appendTransition(t)
                    if (replay.size() >= 64) learner.train(replay.sample(32))
                }
            }
            val actionIdx = learner.select(stateBefore, broker.validMask(), explore = true)
            actionCounts[actionIdx]++
            val result = broker.step(Action.entries[actionIdx], frame, stepNo)
            lastEquity = result.equity
            val stateAfter = featureBuilder.patchPosition(stateBefore, broker.position, stepNo)
            if (result.reason.startsWith("enter") || result.reason == "exit" || result.reason == "forced_exit_max_hold") {
                if (result.reason.startsWith("enter")) entries++ else exits++
                rewardSum += result.reward
                if (result.reward > 0) positive++ else if (result.reward < 0) negative++
                val t = Transition(stateBefore, result.action.ordinal, result.reward, stateAfter, broker.validMask(), result.reason == "exit" || result.reason == "forced_exit_max_hold")
                replay.add(t); appendTransition(t)
                if (replay.size() >= 64) learner.train(replay.sample(32))
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
        val actions = Action.entries.joinToString("/") { "${it.name.first()}=${actionCounts[it.ordinal]}" }
        return "frames $frames trainEq ${"%+.2f".format(finalEq)} reward ${"%+.2f".format(rewardSum)} +$positive/-$negative trades $entries/$exits maxDD ${"%.2f".format(maxDrawdown)} actions $actions"
    }

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

    private fun loadStoredCandles(): List<Candle> {
        val file = candleDataFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            if (line.isBlank()) null else try { candleFromJson(JSONObject(line)) } catch (_: Exception) { null }
        }.sortedBy { it.openTimeMillis }
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
        try { replayAppendFile?.appendText(t.toJsonLine() + "\n") }
        catch (_: Exception) { _state.value = _state.value.copy(status = "replay append failed") }
    }

    private fun loadReplayOnce() {
        if (loadedReplay) return
        loadedReplay = true
        val file = replayAppendFile ?: return
        if (!file.exists()) return
        try {
            var kept = 0
            var skipped = 0
            val tail = ArrayDeque<String>(20_000)
            file.useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        if (tail.size == 20_000) tail.removeFirst()
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
            _state.value = _state.value.copy(replay = replay.size(), status = "replay restored: $kept${if (skipped > 0) ", skipped $skipped old rows" else ""}")
        } catch (_: Exception) {
            _state.value = _state.value.copy(status = "replay restore failed")
        }
    }
}
