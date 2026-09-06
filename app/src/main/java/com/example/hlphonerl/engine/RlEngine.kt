package com.example.hlphonerl.engine

import com.example.hlphonerl.broker.VirtualPerpBroker
import com.example.hlphonerl.data.*
import com.example.hlphonerl.exchange.HyperLiquidInfoClient
import com.example.hlphonerl.exchange.HyperLiquidWsClient
import com.example.hlphonerl.features.L2FeatureBuilder
import com.example.hlphonerl.rl.*
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
    val policy: String = "Masked Double Q-learning from L2 order book only",
    val learningRule: String = "epsilon-greedy actions + replay + executable-equity reward",
    val mid: Double = 0.0,
    val spreadBps: Double = 0.0,
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
    val bookUpdates: Long = 0,
    val bookAgeMs: Long = 0,
    val crossFeeBps: Double = 0.0,
    val fundingBpsPerHour: Double = 0.0,
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
    private val featureBuilder = L2FeatureBuilder(depth = 5)
    private val broker = VirtualPerpBroker()
    private val infoClient = HyperLiquidInfoClient()
    private val learner = MaskedDoubleQLearner(inputDim = featureBuilder.featureSize)
    private val replay = ReplayBuffer()
    private var ws: HyperLiquidWsClient? = null
    private var latestBook: L2Book? = null
    private var lastEquity: Double? = null
    private var pendingState: FloatArray? = null
    private var pendingAction: Int? = null
    private var pendingDone: Boolean = false
    private var lastDecisionBookTimeMillis: Long = 0L
    private var stepNo: Long = 0
    private var persistenceDir: File? = null
    private var replayAppendFile: File? = null
    private var loadedReplay = false
    private var persistenceRoot: File? = null
    private var bookUpdates: Long = 0L
    private var lastBookWallMillis: Long = 0L

    private val _state = MutableStateFlow(EngineUiState(market = marketLabel, coin = coin, markets = markets))
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
        latestBook = null
        lastEquity = null
        pendingState = null
        pendingAction = null
        pendingDone = false
        lastDecisionBookTimeMillis = 0L
        stepNo = 0L
        bookUpdates = 0L
        lastBookWallMillis = 0L
        featureBuilder.reset()
        configureMarketPersistence()
        loadReplayOnce()
        _state.value = EngineUiState(status = "market changed", market = marketLabel, coin = coin, markets = markets, replay = replay.size())
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
        latestBook = null
        lastEquity = null
        pendingState = null
        pendingAction = null
        pendingDone = false
        lastDecisionBookTimeMillis = 0L
        stepNo = 0L
        featureBuilder.reset()
        loadedReplay = true
        _state.value = EngineUiState(
            status = if (wasRunning) "learning reset; press Start" else "learning reset",
            running = false,
            market = marketLabel,
            coin = coin,
            markets = markets,
            crossFeeBps = broker.crossFeeRate * 10_000.0,
            fundingBpsPerHour = broker.fundingRateHourly * 10_000.0,
            costSource = broker.costSource
        )
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
        _state.value = _state.value.copy(status = "loading HyperLiquid costs", running = false)
        decisionJob = scope.launch {
            try {
                val costs = withContext(Dispatchers.IO) { infoClient.loadCosts(coin) }
                broker.setCosts(costs.crossFeeRate, costs.addFeeRate, costs.fundingRateHourly, costs.source)
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = "cost load failed: ${e.javaClass.simpleName}; refusing to train", running = false)
                decisionJob = null
                return@launch
            }
            if (!broker.costsLoaded) {
                _state.value = _state.value.copy(status = "cost load failed; refusing to train", running = false)
                decisionJob = null
                return@launch
            }
            latestBook = null
            lastDecisionBookTimeMillis = 0L
            pendingState = null
            pendingAction = null
            pendingDone = false
            featureBuilder.reset()
            _state.value = _state.value.copy(
                status = "starting",
                running = true,
                crossFeeBps = broker.crossFeeRate * 10_000.0,
                fundingBpsPerHour = broker.fundingRateHourly * 10_000.0,
                costSource = broker.costSource
            )
            ws = HyperLiquidWsClient(
                coin = coin,
                onBook = {
                    if (it.coin == coin && it.timeMillis > lastDecisionBookTimeMillis) {
                        latestBook = it
                        bookUpdates += 1
                        lastBookWallMillis = System.currentTimeMillis()
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

    private fun tick() {
        val book = latestBook ?: return
        if (book.timeMillis == lastDecisionBookTimeMillis) return
        lastDecisionBookTimeMillis = book.timeMillis
        stepNo++
        val stateBeforeAction = featureBuilder.build(book, broker.position, stepNo) ?: return
        val firstDecision = lastEquity == null
        val previousEquity = lastEquity ?: broker.equity(book)

        pendingState?.let { ps ->
            pendingAction?.let { pa ->
                val intervalReward = broker.equity(book) - previousEquity
                val transition = Transition(ps, pa, intervalReward, stateBeforeAction, broker.validMask(), pendingDone)
                replay.add(transition)
                appendTransition(transition)
                if (replay.size() >= 64) learner.train(replay.sample(32))
            }
        }

        val mask = broker.validMask()
        val actionIdx = learner.select(stateBeforeAction, mask, explore = true)
        val action = Action.entries[actionIdx]
        val result = broker.step(action, book, stepNo)
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

        val mid = book.mid ?: 0.0
        val spreadBps = book.spread?.let { it / mid * 10_000.0 } ?: 0.0
        val q = learner.qValues(stateBeforeAction).joinToString(prefix = "[", postfix = "]") { "%.3f".format(it) }
        _state.value = EngineUiState(
            status = "running",
            running = true,
            market = marketLabel,
            coin = coin,
            markets = markets,
            mid = mid,
            spreadBps = spreadBps,
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
            bookUpdates = bookUpdates,
            bookAgeMs = if (lastBookWallMillis == 0L) 0L else System.currentTimeMillis() - lastBookWallMillis,
            crossFeeBps = broker.crossFeeRate * 10_000.0,
            fundingBpsPerHour = broker.fundingRateHourly * 10_000.0,
            costSource = broker.costSource
        )
    }

    private fun appendTransition(t: Transition) {
        try {
            replayAppendFile?.appendText(t.toJsonLine() + "\n")
        } catch (_: Exception) {
            _state.value = _state.value.copy(status = "replay append failed")
        }
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
