package com.example.hlphonerl.rl

import com.example.hlphonerl.data.Action
import kotlin.math.max
import kotlin.random.Random

data class Transition(
    val state: FloatArray,
    val action: Int,
    val reward: Double,
    val nextState: FloatArray,
    val nextMask: BooleanArray,
    val done: Boolean
)

class ReplayBuffer(private val capacity: Int = 20_000, private val rng: Random = Random(7)) {
    private val data = ArrayList<Transition>(capacity)
    fun add(t: Transition) { if (data.size == capacity) data.removeAt(0); data.add(t) }
    fun size() = data.size
    fun sample(n: Int): List<Transition> = List(minOf(n, data.size)) { data[rng.nextInt(data.size)] }
}

/**
 * Phone-friendly pure RL baseline: masked Double Q-learning with linear function approximation.
 * It learns only from virtual trading rewards and L2-book observations. No indicators/candles.
 * This can later be replaced by a small dueling neural net while preserving the same interface.
 */
class MaskedDoubleQLearner(
    private val inputDim: Int,
    private val actions: Int = Action.entries.size,
    private val gamma: Double = 0.997,
    private val lr: Double = 0.0005,
    private val l2: Double = 0.00001,
    private val rng: Random = Random(42)
) {
    private val wA = Array(actions) { DoubleArray(inputDim + 1) { rng.nextDouble(-0.001, 0.001) } }
    private val wB = Array(actions) { DoubleArray(inputDim + 1) { rng.nextDouble(-0.001, 0.001) } }
    var epsilon = 0.20
        private set
    var updates = 0
        private set

    fun select(state: FloatArray, mask: BooleanArray, explore: Boolean = true): Int {
        val valid = mask.indices.filter { mask[it] }
        if (valid.isEmpty()) return 0
        if (explore && rng.nextDouble() < epsilon) return valid[rng.nextInt(valid.size)]
        val q = qValues(state)
        return valid.maxBy { q[it] }
    }

    fun qValues(state: FloatArray): DoubleArray = DoubleArray(actions) { a ->
        (dot(wA[a], state) + dot(wB[a], state)) / 2.0
    }

    fun train(batch: List<Transition>) {
        if (batch.isEmpty()) return
        for (t in batch) {
            val trainA = rng.nextBoolean()
            val online = if (trainA) wA else wB
            val targetNet = if (trainA) wB else wA
            val nextOnlineQ = DoubleArray(actions) { a -> dot(online[a], t.nextState) }
            val validNext = t.nextMask.indices.filter { t.nextMask[it] }
            val nextAction = if (t.done || validNext.isEmpty()) 0 else validNext.maxBy { nextOnlineQ[it] }
            val bootstrap = if (t.done || validNext.isEmpty()) 0.0 else dot(targetNet[nextAction], t.nextState)
            val target = t.reward + gamma * bootstrap
            val pred = dot(online[t.action], t.state)
            val err = (target - pred).coerceIn(-10.0, 10.0)
            update(online[t.action], t.state, err)
            updates++
        }
        epsilon = max(0.03, epsilon * 0.9995)
    }

    private fun dot(w: DoubleArray, x: FloatArray): Double {
        var s = w[0]
        for (i in x.indices) s += w[i + 1] * x[i]
        return s
    }

    private fun update(w: DoubleArray, x: FloatArray, err: Double) {
        w[0] += lr * err
        for (i in x.indices) w[i + 1] += lr * (err * x[i] - l2 * w[i + 1])
    }
}
