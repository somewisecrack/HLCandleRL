package com.example.hlphonerl.engine

import com.example.hlphonerl.broker.VirtualPerpBroker
import com.example.hlphonerl.data.*
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
    val qValues: String = ""
)

class RlEngine(private val coin: String = "xyz:SP500") {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var decisionJob: Job? = null
    private val featureBuilder = L2FeatureBuilder(depth = 5)
    private val broker = VirtualPerpBroker()
    private val learner = MaskedDoubleQLearner(inputDim = featureBuilder.featureSize)
    private val replay = ReplayBuffer()
    private var ws: HyperLiquidWsClient? = null
    private var latestBook: L2Book? = null
    private var lastState: FloatArray? = null
    private var lastAction: Int? = null
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
        if (_state.value.running) return
        _state.value = _state.value.copy(status = "starting", running = true)
        ws = HyperLiquidWsClient(
            coin = coin,
            onBook = { latestBook = it },
            onStatus = { s -> _state.value = _state.value.copy(status = s) }
        ).also { it.connect() }
        decisionJob = scope.launch {
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
        stepNo++
        val state = featureBuilder.build(book, broker.position, stepNo) ?: return
        val mask = broker.validMask()
        val actionIdx = learner.select(state, mask, explore = true)
        val action = Action.entries[actionIdx]
        val result = broker.step(action, book, stepNo)

        val nextState = featureBuilder.build(book, broker.position, stepNo) ?: state
        lastState?.let { prev ->
            lastAction?.let { a ->
                val transition = Transition(prev, a, result.reward, nextState, broker.validMask(), broker.position == null && action == Action.EXIT)
                replay.add(transition)
                appendTransition(transition)
            }
        }
        if (replay.size() >= 64) learner.train(replay.sample(32))
        lastState = nextState
        lastAction = actionIdx

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
            qValues = q
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
