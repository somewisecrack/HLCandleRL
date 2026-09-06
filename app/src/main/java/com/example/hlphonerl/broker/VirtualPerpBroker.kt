package com.example.hlphonerl.broker

import com.example.hlphonerl.data.*

class VirtualPerpBroker(
    private val notionalUsd: Double = 1000.0,
    private val maxHoldSteps: Long = 300
) {
    var crossFeeRate: Double = 0.0
        private set
    var addFeeRate: Double = 0.0
        private set
    var fundingRateHourly: Double = 0.0
        private set
    var costSource: String = "not_loaded"
        private set
    val costsLoaded: Boolean get() = costSource.startsWith("hyperliquid_info:")
    var cashPnl: Double = 0.0
        private set
    var position: Position? = null
        private set
    private var lastFundingMillis: Long? = null

    fun setCosts(crossFeeRate: Double, addFeeRate: Double, fundingRateHourly: Double, source: String) {
        this.crossFeeRate = crossFeeRate
        this.addFeeRate = addFeeRate
        this.fundingRateHourly = fundingRateHourly
        this.costSource = source
    }

    fun reset() {
        cashPnl = 0.0
        position = null
        lastFundingMillis = null
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
        applyFunding(frame, System.currentTimeMillis())
        when (effective) {
            Action.WAIT -> reason = if (position == null) "wait_flat" else "invalid_wait_while_holding"
            Action.HOLD -> reason = if (position != null) "hold" else "invalid_hold_flat"
            Action.ENTER_LONG -> if (position == null) {
                val qty = notionalUsd / px
                val fee = notionalUsd * crossFeeRate
                cashPnl -= fee
                val ts = System.currentTimeMillis()
                position = Position(Side.LONG, px, qty, ts, step)
                lastFundingMillis = ts
                reason = "enter_long"
            }
            Action.ENTER_SHORT -> if (position == null) {
                val qty = notionalUsd / px
                val fee = notionalUsd * crossFeeRate
                cashPnl -= fee
                val ts = System.currentTimeMillis()
                position = Position(Side.SHORT, px, qty, ts, step)
                lastFundingMillis = ts
                reason = "enter_short"
            }
            Action.EXIT -> position?.let { pos ->
                val gross = if (pos.side == Side.LONG) (px - pos.entryPx) * pos.qty else (pos.entryPx - px) * pos.qty
                val fee = px * pos.qty * crossFeeRate
                realized = gross - fee
                cashPnl += realized
                position = null
                lastFundingMillis = null
                reason = if (forced) "forced_exit_max_hold" else "exit"
            }
        }
        val after = equity(frame)
        return StepResult(effective, after - before, after, realized, reason)
    }

    private fun applyFunding(frame: MarketFrame, nowMillis: Long) {
        val p = position ?: return
        val last = lastFundingMillis ?: return
        if (fundingRateHourly == 0.0) return
        val hours = ((nowMillis - last).coerceAtLeast(0)).toDouble() / 3_600_000.0
        if (hours <= 0.0) return
        val notional = kotlin.math.abs(frame.price * p.qty)
        // HyperLiquid funding sign convention: positive funding means longs pay shorts.
        val sideSign = if (p.side == Side.LONG) 1.0 else -1.0
        cashPnl -= notional * fundingRateHourly * hours * sideSign
        lastFundingMillis = nowMillis
    }

    fun unrealized(frame: MarketFrame): Double {
        val p = position ?: return 0.0
        val px = frame.price.takeIf { it > 0.0 } ?: return 0.0
        return if (p.side == Side.LONG) (px - p.entryPx) * p.qty else (p.entryPx - px) * p.qty
    }
}
