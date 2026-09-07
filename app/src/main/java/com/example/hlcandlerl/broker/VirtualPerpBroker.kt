package com.example.hlcandlerl.broker

import com.example.hlcandlerl.data.*

class VirtualPerpBroker(
    private val notionalUsd: Double = 1000.0,
    private val maxHoldSteps: Long = 300
) {
    var cashPnl: Double = 0.0
        private set
    var position: Position? = null
        private set

    fun reset() {
        cashPnl = 0.0
        position = null
    }

    fun equity(frame: MarketFrame): Double = cashPnl + unrealized(frame)

    fun validMask(): BooleanArray = if (position == null) {
        booleanArrayOf(true, true, true, false, false)
    } else {
        booleanArrayOf(false, false, false, true, true)
    }

    fun step(action: Action, frame: MarketFrame, step: Long): StepResult {
        val before = equity(frame)
        val px = frame.price.takeIf { it > 0.0 } ?: return StepResult(action, 0.0, before, 0.0, "no_price")
        val forced = position?.let { step - it.entryStep >= maxHoldSteps } == true
        val effective = if (forced && action == Action.HOLD) Action.EXIT else action
        var realized = 0.0
        var reason = "noop"
        when (effective) {
            Action.WAIT -> reason = if (position == null) "wait_flat" else "invalid_wait_while_holding"
            Action.HOLD -> reason = if (position != null) "hold" else "invalid_hold_flat"
            Action.ENTER_LONG -> if (position == null) {
                val qty = notionalUsd / px
                position = Position(Side.LONG, px, qty, System.currentTimeMillis(), step)
                reason = "enter_long"
            }
            Action.ENTER_SHORT -> if (position == null) {
                val qty = notionalUsd / px
                position = Position(Side.SHORT, px, qty, System.currentTimeMillis(), step)
                reason = "enter_short"
            }
            Action.EXIT -> position?.let { pos ->
                realized = if (pos.side == Side.LONG) (px - pos.entryPx) * pos.qty else (pos.entryPx - px) * pos.qty
                cashPnl += realized
                position = null
                reason = if (forced) "forced_exit_max_hold" else "exit"
            }
        }
        val after = equity(frame)
        return StepResult(effective, after - before, after, realized, reason)
    }

    fun unrealized(frame: MarketFrame): Double {
        val p = position ?: return 0.0
        val px = frame.price.takeIf { it > 0.0 } ?: return 0.0
        return if (p.side == Side.LONG) (px - p.entryPx) * p.qty else (p.entryPx - px) * p.qty
    }
}
