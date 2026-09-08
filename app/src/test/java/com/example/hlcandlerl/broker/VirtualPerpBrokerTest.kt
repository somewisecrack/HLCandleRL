package com.example.hlcandlerl.broker

import com.example.hlcandlerl.data.Action
import com.example.hlcandlerl.data.Side
import com.example.hlcandlerl.frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualPerpBrokerTest {

    @Test
    fun longPnlUsesFixedThousandDollarNotional() {
        val b = VirtualPerpBroker()
        b.step(Action.ENTER_LONG, frame(100.0), 1)
        val pos = b.position!!
        assertEquals(Side.LONG, pos.side)
        assertEquals(100.0, pos.entryPx, 1e-9)
        assertEquals(10.0, pos.qty, 1e-9) // qty = 1000 / 100
        val exit = b.step(Action.EXIT, frame(110.0), 2)
        assertEquals(100.0, exit.realizedPnl, 1e-9)
        assertEquals(100.0, b.cashPnl, 1e-9)
        assertNull(b.position)
    }

    @Test
    fun shortPnlIsPositiveWhenPriceFalls() {
        val b = VirtualPerpBroker()
        b.step(Action.ENTER_SHORT, frame(100.0), 1)
        val exit = b.step(Action.EXIT, frame(90.0), 2)
        assertEquals(100.0, exit.realizedPnl, 1e-9)
        assertEquals(100.0, b.cashPnl, 1e-9)
    }

    @Test
    fun roundTripAtSamePriceIsExactlyZeroWithNoFeeDrag() {
        val b = VirtualPerpBroker()
        b.step(Action.ENTER_LONG, frame(100.0), 1)
        val exit = b.step(Action.EXIT, frame(100.0), 2)
        assertEquals(0.0, exit.realizedPnl, 0.0)
        assertEquals(0.0, b.cashPnl, 0.0)
        assertEquals(0.0, b.equity(frame(100.0)), 0.0)

        val s = VirtualPerpBroker()
        s.step(Action.ENTER_SHORT, frame(250.0), 1)
        s.step(Action.EXIT, frame(250.0), 2)
        assertEquals(0.0, s.cashPnl, 0.0)
    }

    @Test
    fun maskIsFlatEntryOnlyWhenFlatAndHoldExitOnlyWhenHolding() {
        val b = VirtualPerpBroker()
        assertArrayTrue(booleanArrayOf(true, true, true, false, false), b.validMask())
        b.step(Action.ENTER_LONG, frame(100.0), 1)
        assertArrayTrue(booleanArrayOf(false, false, false, true, true), b.validMask())
        b.step(Action.EXIT, frame(100.0), 2)
        assertArrayTrue(booleanArrayOf(true, true, true, false, false), b.validMask())
    }

    @Test
    fun holdBeyondMaxHoldStepsIsForcedIntoAnExit() {
        val b = VirtualPerpBroker(maxHoldSteps = 5)
        b.step(Action.ENTER_LONG, frame(100.0), 1)
        val stillHolding = b.step(Action.HOLD, frame(101.0), 5)
        assertEquals(Action.HOLD, stillHolding.action)
        assertNotNull(b.position)
        val forced = b.step(Action.HOLD, frame(105.0), 6) // 6 - 1 >= 5
        assertEquals(Action.EXIT, forced.action)
        assertEquals("forced_exit_max_hold", forced.reason)
        assertEquals(50.0, forced.realizedPnl, 1e-9)
        assertNull(b.position)
    }

    @Test
    fun invalidActionsAreRejectedWithoutMutatingState() {
        val b = VirtualPerpBroker()
        val holdFlat = b.step(Action.HOLD, frame(100.0), 1)
        assertEquals("invalid_hold_flat", holdFlat.reason)
        assertNull(b.position)
        val exitFlat = b.step(Action.EXIT, frame(100.0), 2)
        assertEquals("noop", exitFlat.reason)
        assertEquals(0.0, b.cashPnl, 0.0)

        b.step(Action.ENTER_LONG, frame(100.0), 3)
        val entryTwice = b.step(Action.ENTER_SHORT, frame(120.0), 4)
        assertEquals("noop", entryTwice.reason)
        assertEquals(Side.LONG, b.position!!.side)
        assertEquals(100.0, b.position!!.entryPx, 1e-9)
    }

    @Test
    fun nonPositivePriceIsRefusedRatherThanCreatingAnInfinitePosition() {
        val b = VirtualPerpBroker()
        val r = b.step(Action.ENTER_LONG, frame(0.0), 1)
        assertEquals("no_price", r.reason)
        assertNull(b.position)
    }

    @Test
    fun rewardIsMarkToMarketDeltaAndEntryExitAreCostFree() {
        val b = VirtualPerpBroker()
        val entry = b.step(Action.ENTER_LONG, frame(100.0), 1)
        assertEquals("entering must not move equity when there is no cost model", 0.0, entry.reward, 0.0)
        // Mark-to-market between candles is what carries reward.
        assertEquals(50.0, b.equity(frame(105.0)), 1e-9)
        val exit = b.step(Action.EXIT, frame(105.0), 2)
        assertEquals("exiting must not move equity when there is no cost model", 0.0, exit.reward, 0.0)
        assertEquals(50.0, b.cashPnl, 1e-9)
    }

    @Test
    fun resetClearsPositionAndCash() {
        val b = VirtualPerpBroker()
        b.step(Action.ENTER_LONG, frame(100.0), 1)
        b.step(Action.EXIT, frame(150.0), 2)
        assertTrue(b.cashPnl > 0.0)
        b.reset()
        assertEquals(0.0, b.cashPnl, 0.0)
        assertNull(b.position)
    }

    private fun assertArrayTrue(expected: BooleanArray, actual: BooleanArray) =
        assertEquals(expected.toList(), actual.toList())
}
