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
import java.io.File

 data class EngineUiState(
    val status: String = "idle",
    val running: Boolean = false,
    val market: String = "SP500",
    val coin: String = "xyz:SP500",
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
    val crossFeeBps: Double = 0.0,
    val fundingBpsPerHour: Double = 0.0,
    val openInterest: Double = 0.0,
    val premiumBps: Double = 0.0,
    val markPx: Double = 0.0,
    val oraclePx: Double = 0.0,
    val costSource: String = "not_loaded"
)

class RlEngine(
    defaultMarketLabel: String = "SP500",
    private val markets: Map<String, String> = mapOf("SP500" to "xyz:SP500")
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var decisionJob: Job? = null
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
    private var loadedReplay = false
    private var persistenceRoot: File? = null
    private var candleUpdates: Long = 0L
    private var lastCandleWallMillis: Long = 0L

    private val _state = MutableStateFlow(EngineUiState(market = marketLabel, coin = coin, markets = markets, interval = interval))
    val state: StateFlow<EngineUiState> = _state

    fun attachPersistenceDir(dir: File) {
        persistenceRoot = dir.also { it.mkdirs() }
        configureMarketPersistence()
        loadReplayOnce()
    }

    fun setMarket(label: String) {
        if (_state.value.running || decisionJob != null) return
        val newCoin = markets[label] ?: return
        marketLabel = label
        coin = newCoin
        loadedReplay = false
        replay.clear()
        broker.reset()
        learner.reset()
        clearRuntime()
        configureMarketPersistence()
        loadReplayOnce()
        _state.value = EngineUiState(status = "market changed", market = marketLabel, coin = coin, markets = markets, interval = interval, replay = replay.size())
    }

    private fun configureMarketPersistence() {
        val root = persistenceRoot ?: return
        val safe = coin.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        persistenceDir = File(root, safe).also { it.mkdirs() }
        replayAppendFile = File(persistenceDir, "replay.jsonl")
    }

    fun policyFile(): File? = persistenceDir?.let { File(it, "policy.json") }
    fun exportPolicyJson(): String = learner.snapshotJson()
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
            interval = interval,
            crossFeeBps = broker.crossFeeRate * 10_000.0,
            fundingBpsPerHour = broker.fundingRateHourly * 10_000.0,
            costSource = broker.costSource
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
        loadReplayOnce()
        if (_state.value.running || decisionJob != null) return
        _state.value = _state.value.copy(status = "loading HyperLiquid costs/context", running = false)
        decisionJob = scope.launch {
            try {
                val costs = withContext(Dispatchers.IO) { infoClient.loadCostsAndContext(coin) }
                val history = withContext(Dispatchers.IO) { infoClient.loadRecentCandles(coin, interval) }
                broker.setCosts(costs.crossFeeRate, costs.addFeeRate, costs.fundingRateHourly, costs.source)
                context = costs.context
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = "cost/context load failed: ${e.javaClass.simpleName}; refusing to train", running = false)
                decisionJob = null
                return@launch
            }
            if (!broker.costsLoaded) {
                _state.value = _state.value.copy(status = "cost load failed; refusing to train", running = false)
                decisionJob = null
                return@launch
            }
            clearRuntime(clearContext = false)
            featureBuilder.seed(history)
            _state.value = _state.value.copy(
                status = "starting",
                running = true,
                crossFeeBps = broker.crossFeeRate * 10_000.0,
                fundingBpsPerHour = broker.fundingRateHourly * 10_000.0,
                costSource = broker.costSource
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
            crossFeeBps = broker.crossFeeRate * 10_000.0,
            fundingBpsPerHour = broker.fundingRateHourly * 10_000.0,
            openInterest = context.openInterest,
            premiumBps = context.premium * 10_000.0,
            markPx = context.markPx,
            oraclePx = context.oraclePx,
            costSource = broker.costSource
        )
    }

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
            val loaded = file.readLines().mapNotNull { line ->
                if (line.isBlank()) null else try { Transition.fromJsonLine(line) } catch (_: Exception) { null }
            }
            replay.addAll(loaded)
            _state.value = _state.value.copy(replay = replay.size(), status = "replay restored: ${replay.size()}")
        } catch (_: Exception) {
            _state.value = _state.value.copy(status = "replay restore failed")
        }
    }
}
