package com.example.hlcandlerl.features

import com.example.hlcandlerl.candle
import com.example.hlcandlerl.data.Candle
import com.example.hlcandlerl.data.MarketFrame
import com.example.hlcandlerl.data.PerpContext
import com.example.hlcandlerl.data.Position
import com.example.hlcandlerl.data.Side
import com.example.hlcandlerl.data.StepResult
import com.example.hlcandlerl.frame
import com.example.hlcandlerl.rl.MaskedDoubleQLearner
import com.example.hlcandlerl.syntheticSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OhlcvFeatureBuilderTest {

    @Test
    fun featureSizeMatchesEncodedVectorAndLearnerInputDim() {
        val fb = OhlcvFeatureBuilder(window = 32)
        assertEquals(32 * 7 + 6 + 4, fb.featureSize)
        fb.add(frame(100.0, 0))
        val v = fb.build(frame(101.0, 60_000), null, 2)!!
        assertEquals(fb.featureSize, v.size)
        // Learner is constructed with featureSize; qValues must accept the vector without overflow.
        val learner = MaskedDoubleQLearner(inputDim = fb.featureSize)
        assertEquals(5, learner.qValues(v).size)
    }

    @Test
    fun noFundingOrCostFieldsExistInAnyObservationModel() {
        val models = listOf(Candle::class, PerpContext::class, MarketFrame::class, Position::class, StepResult::class)
        val banned = listOf("fee", "fund", "cost", "premiumcost")
        models.forEach { k ->
            k.java.declaredFields.forEach { f ->
                val n = f.name.lowercase()
                assertTrue(
                    "${k.java.simpleName}.${f.name} looks like a cost/funding field",
                    banned.none { b -> n.contains(b) }
                )
            }
        }
    }

    @Test
    fun buildNeedsAtLeastTwoCandles() {
        val fb = OhlcvFeatureBuilder(window = 32)
        assertNull(fb.build(frame(100.0, 0), null, 1))
        assertNotNull(fb.build(frame(101.0, 60_000), null, 2))
    }

    @Test
    fun sameOpenTimeReplacesRatherThanAppendsPartialCandle() {
        val fb = OhlcvFeatureBuilder(window = 4)
        fb.add(frame(100.0, 0))
        fb.add(frame(101.0, 60_000))
        val partial = fb.build(frame(102.0, 60_000), null, 3)!!
        val revised = fb.build(frame(105.0, 60_000), null, 4)!!
        // A revised in-progress candle must overwrite the previous tick, not shift the window.
        assertTrue(!partial.contentEquals(revised))
        fb.reset()
        assertNull(fb.build(frame(100.0, 0), null, 5))
    }

    @Test
    fun seedTakesTheMostRecentWindowInChronologicalOrder() {
        val fb = OhlcvFeatureBuilder(window = 8)
        fb.seed(syntheticSeries(50).shuffled())
        // Seeded window is already full, so the very next frame builds a full vector.
        val v = fb.build(frame(120.0, 50 * 60_000L), null, 1)!!
        assertEquals(fb.featureSize, v.size)
        assertTrue(v.take(7).any { it != 0f }) // no zero padding left
    }

    @Test
    fun patchPositionUpdatesSideAgeAndClearsUnrealizedWhenFlat() {
        val fb = OhlcvFeatureBuilder(window = 8)
        fb.add(frame(100.0, 0))
        val flat = fb.build(frame(100.0, 60_000), null, 1)!!
        val n = flat.size
        assertEquals(0f, flat[n - 4]) // side
        assertEquals(0f, flat[n - 3]) // age
        assertEquals(0f, flat[n - 2]) // unrealized
        assertEquals(1f, flat[n - 1]) // bias

        val long = Position(Side.LONG, 100.0, 10.0, 0L, entryStep = 1)
        val patchedLong = fb.patchPosition(flat, long, step = 151)
        assertEquals(1f, patchedLong[n - 4])
        assertEquals(0.5f, patchedLong[n - 3], 1e-6f) // (151-1)/300
        assertEquals(1f, patchedLong[n - 1])

        val short = Position(Side.SHORT, 100.0, 10.0, 0L, entryStep = 1)
        assertEquals(-1f, fb.patchPosition(flat, short, step = 2)[n - 4])

        val stale = flat.copyOf().also { it[n - 2] = 42f }
        assertEquals(0f, fb.patchPosition(stale, null, step = 9)[n - 2])
    }

    @Test
    fun unrealizedFeatureTracksOpenPositionDirection() {
        val fb = OhlcvFeatureBuilder(window = 8)
        fb.add(frame(100.0, 0))
        val long = Position(Side.LONG, 100.0, 10.0, 0L, entryStep = 1)
        val up = fb.build(frame(110.0, 60_000), long, 2)!!
        assertTrue(up[up.size - 2] > 0f)
        val short = Position(Side.SHORT, 100.0, 10.0, 0L, entryStep = 1)
        val down = fb.build(frame(110.0, 120_000), short, 3)!!
        assertTrue(down[down.size - 2] < 0f)
    }

    @Test
    fun contextFeaturesAreFiniteEvenWithMissingContext() {
        val fb = OhlcvFeatureBuilder(window = 8)
        fb.add(frame(100.0, 0))
        val v = fb.build(MarketFrame(candle(100.0, 60_000), PerpContext()), null, 2)!!
        assertTrue(v.all { it.isFinite() })
    }
}
