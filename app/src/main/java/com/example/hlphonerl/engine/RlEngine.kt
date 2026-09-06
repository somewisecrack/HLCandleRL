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
    val coin: String = "xyz:SP500",
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
    val crossFeeBps: Double = 0.0,
    val fundingBpsPerHour: Double = 0.0,
    val costSource: String = "not_loaded"
)

class RlEngine(private val coin: String = "xyz:SP500") {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var decisionJob: Job? = null
    private val featureBuilder = L2FeatureBuilder(depth = 5)
    private val broker = VirtualPerpBroker()
    private val infoClient = HyperLiquidInfoClient()
    private val learner = MaskedDoubleQLearner(inputDim = featureBuilder.featureSize)
    private val replay = ReplayBuffer()
    private var ws: HyperLiquidWsClient? = null
    private var latestBook: L2Book? = null
    private var lastEquity: Double? = null
    private var lastDecisionBookTimeMillis: Long = 0L
    private var stepNo: Long = 0
    private var persistenceDir: File? = null
    private var replayAppendFile: File? = null
    private var loadedReplay = false

    private val _state = MutableStateFlow(EngineUiState(coin = coin))
    val state: StateFlow<EngineUiState> = _state

    fun attachPersistenceDir(dir: File) {
        persistenceDir = dir.also { it.mkdirs() }
        replayAppendFile = File(dir, "replay.jsonl")
        loadReplayOnce()
    }

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
        lastDecisionBookTimeMillis = 0L
        stepNo = 0L
        loadedReplay = true
        _state.value = EngineUiState(
            status = if (wasRunning) "learning reset; press Start" else "learning reset",
            running = false,
            coin = coin,
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
            _state.value = _state.value.copy(
                status = "starting",
                running = true,
                crossFeeBps = broker.crossFeeRate * 10_000.0,
                fundingBpsPerHour = broker.fundingRateHourly * 10_000.0,
                costSource = broker.costSource
            )
            ws = HyperLiquidWsClient(
                coin = coin,
                onBook = { latestBook = it },
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
        val state = featureBuilder.build(book, broker.position, stepNo) ?: return
        val mask = broker.validMask()
        val actionIdx = learner.select(state, mask, explore = true)
        val action = Action.entries[actionIdx]
        val previousEquity = lastEquity ?: broker.equity(book)
        val result0 = broker.step(action, book, stepNo)
        val reward = result0.equity - previousEquity
        lastEquity = result0.equity
        val result = result0.copy(reward = reward)

        val nextState = featureBuilder.build(book, broker.position, stepNo) ?: state
        val transition = Transition(state, actionIdx, result.reward, nextState, broker.validMask(), broker.position == null && action == Action.EXIT)
        replay.add(transition)
        appendTransition(transition)
        if (replay.size() >= 64) learner.train(replay.sample(32))

        val mid = book.mid ?: 0.0
        val spreadBps = book.spread?.let { it / mid * 10_000.0 } ?: 0.0
        val q = learner.qValues(state).joinToString(prefix = "[", postfix = "]") { "%.3f".format(it) }
        _state.value = EngineUiState(
            status = "running",
            running = true,
            coin = coin,
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
