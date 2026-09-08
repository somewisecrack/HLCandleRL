package com.example.hlcandlerl.rl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReplayAndLearnerTest {

    private fun transition(seed: Int, dim: Int = 6) = Transition(
        state = FloatArray(dim) { (seed + it).toFloat() / 7f },
        action = seed % 5,
        reward = seed * 0.5,
        nextState = FloatArray(dim) { (seed - it).toFloat() / 3f },
        nextMask = booleanArrayOf(true, true, true, false, false),
        done = seed % 2 == 0
    )

    @Test
    fun transitionJsonRoundTripsExactly() {
        val t = transition(3)
        val back = Transition.fromJsonLine(t.toJsonLine())
        assertArrayEquals(t.state, back.state, 0f)
        assertArrayEquals(t.nextState, back.nextState, 0f)
        assertEquals(t.action, back.action)
        assertEquals(t.reward, back.reward, 1e-12)
        assertEquals(t.nextMask.toList(), back.nextMask.toList())
        assertEquals(t.done, back.done)
    }

    @Test
    fun transitionJsonLineHasNoEmbeddedNewline() {
        assertTrue(!transition(1).toJsonLine().contains("\n"))
    }

    @Test
    fun replayBufferIsBoundedAndDropsOldestFirst() {
        val buf = ReplayBuffer(capacity = 3)
        (1..5).forEach { buf.add(transition(it)) }
        assertEquals(3, buf.size())
        val kept = buf.snapshot().map { it.reward }
        assertEquals(listOf(3 * 0.5, 4 * 0.5, 5 * 0.5), kept)
    }

    @Test
    fun replaySampleIsBoundedByStoredSize() {
        val buf = ReplayBuffer(capacity = 100)
        (1..4).forEach { buf.add(transition(it)) }
        assertEquals(4, buf.sample(32).size)
        buf.clear()
        assertEquals(0, buf.size())
        assertEquals(0, buf.sample(32).size)
    }

    @Test
    fun policySnapshotRoundTripsAndPreservesQValues() {
        val a = MaskedDoubleQLearner(inputDim = 6, rng = Random(1))
        val batch = (1..200).map { transition(it) }
        a.train(batch)
        assertTrue(a.updates > 0)
        val json = a.snapshotJson()
        val b = MaskedDoubleQLearner(inputDim = 6, rng = Random(99))
        b.restoreJson(json)
        assertEquals(a.updates, b.updates)
        assertEquals(a.epsilon, b.epsilon, 1e-12)
        val s = FloatArray(6) { it.toFloat() }
        assertArrayEquals(a.qValues(s), b.qValues(s), 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun restoringAPolicyWithADifferentFeatureSizeIsRejectedNotSilentlyLoaded() {
        val old = MaskedDoubleQLearner(inputDim = 5).snapshotJson()
        MaskedDoubleQLearner(inputDim = 6).restoreJson(old)
    }

    @Test
    fun selectOnlyReturnsActionsAllowedByTheMask() {
        val l = MaskedDoubleQLearner(inputDim = 4, rng = Random(5))
        val s = FloatArray(4) { 1f }
        val flatMask = booleanArrayOf(true, true, true, false, false)
        repeat(200) { assertTrue(flatMask[l.select(s, flatMask, explore = true)]) }
        val holdMask = booleanArrayOf(false, false, false, true, true)
        repeat(200) { assertTrue(holdMask[l.select(s, holdMask, explore = true)]) }
        // Greedy selection must also stay inside the mask.
        repeat(20) { assertTrue(holdMask[l.select(s, holdMask, explore = false)]) }
    }

    @Test
    fun epsilonDecaysWithTrainingButStaysAboveFloor() {
        val l = MaskedDoubleQLearner(inputDim = 4, rng = Random(3))
        val start = l.epsilon
        repeat(50) { l.train(listOf(transition(it, dim = 4))) }
        assertTrue(l.epsilon < start)
        repeat(50_000) { l.train(listOf(transition(it, dim = 4))) }
        assertTrue(l.epsilon >= 0.03)
    }

    @Test
    fun trainingMovesQValuesTowardRewardAndResetClearsThem() {
        val l = MaskedDoubleQLearner(inputDim = 3, rng = Random(11))
        val s = floatArrayOf(1f, 0f, 0f)
        val t = Transition(s, 1, 5.0, s, booleanArrayOf(false, false, false, false, false), done = true)
        val before = l.qValues(s)[1]
        repeat(500) { l.train(listOf(t)) }
        val after = l.qValues(s)[1]
        assertTrue("q should rise toward the positive reward: $before -> $after", after > before)
        assertNotEquals(0, l.updates)
        l.reset()
        assertEquals(0, l.updates)
        assertTrue(kotlin.math.abs(l.qValues(s)[1]) < 0.01)
    }

    @Test
    fun trainingOnAnEmptyBatchIsANoop() {
        val l = MaskedDoubleQLearner(inputDim = 3)
        l.train(emptyList())
        assertEquals(0, l.updates)
    }
}
