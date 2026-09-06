package com.example.hlphonerl.broker

import com.example.hlphonerl.data.*
import kotlin.math.min

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

    fun equity(book: L2Book): Double = cashPnl + unrealized(book)

    fun validMask(): BooleanArray = if (position == null) {
        booleanArrayOf(true, true, true, false, false)
    } else {
        booleanArrayOf(false, false, false, true, true)
    }

    fun step(action: Action, book: L2Book, step: Long): StepResult {
        val before = equity(book)
        val forced = position?.let { step - it.entryStep >= maxHoldSteps } == true
        val effective = if (forced && action == Action.HOLD) Action.EXIT else action
        var realized = 0.0
        var reason = "noop"
        applyFunding(book, System.currentTimeMillis())
        when (effective) {
            Action.WAIT -> reason = if (position == null) "wait_flat" else "invalid_wait_while_holding"
            Action.HOLD -> reason = if (position != null) "hold" else "invalid_hold_flat"
            Action.ENTER_LONG -> if (position == null) {
                fillBuy(book, notionalUsd)?.let { (px, qty) ->
                    cashPnl -= notionalUsd * crossFeeRate
                    val ts = System.currentTimeMillis()
                    position = Position(Side.LONG, px, qty, ts, step)
                    lastFundingMillis = ts
                    reason = "enter_long"
                } ?: run { reason = "no_ask_liquidity" }
            }
            Action.ENTER_SHORT -> if (position == null) {
                fillSell(book, notionalUsd)?.let { (px, qty) ->
                    cashPnl -= notionalUsd * crossFeeRate
                    val ts = System.currentTimeMillis()
                    position = Position(Side.SHORT, px, qty, ts, step)
                    lastFundingMillis = ts
                    reason = "enter_short"
                } ?: run { reason = "no_bid_liquidity" }
            }
            Action.EXIT -> position?.let { pos ->
                val exit = if (pos.side == Side.LONG) sellQty(book, pos.qty) else buyQty(book, pos.qty)
                exit?.let { px ->
                    val gross = if (pos.side == Side.LONG) (px - pos.entryPx) * pos.qty else (pos.entryPx - px) * pos.qty
                    val fee = px * pos.qty * crossFeeRate
                    realized = gross - fee
                    cashPnl += realized
                    position = null
                    lastFundingMillis = null
                    reason = if (forced) "forced_exit_max_hold" else "exit"
                } ?: run { reason = "no_exit_liquidity" }
            }
        }
        val after = equity(book)
        val reward = after - before
        return StepResult(effective, reward, after, realized, reason)
    }

    private fun applyFunding(book: L2Book, nowMillis: Long) {
        val p = position ?: return
        val last = lastFundingMillis ?: return
        if (fundingRateHourly == 0.0) return
        val hours = ((nowMillis - last).coerceAtLeast(0)).toDouble() / 3_600_000.0
        if (hours <= 0.0) return
        val px = book.mid ?: p.entryPx
        val notional = kotlin.math.abs(px * p.qty)
        // HyperLiquid funding sign convention: positive funding means longs pay shorts.
        val sideSign = if (p.side == Side.LONG) 1.0 else -1.0
        val payment = notional * fundingRateHourly * hours * sideSign
        cashPnl -= payment
        lastFundingMillis = nowMillis
    }

    private fun unrealized(book: L2Book): Double {
        val p = position ?: return 0.0
        val exitPx = if (p.side == Side.LONG) book.bestBid else book.bestAsk
        val px = exitPx ?: return 0.0
        return if (p.side == Side.LONG) (px - p.entryPx) * p.qty else (p.entryPx - px) * p.qty
    }

    private fun fillBuy(book: L2Book, notional: Double): Pair<Double, Double>? = walkNotional(book.asks, notional)
    private fun fillSell(book: L2Book, notional: Double): Pair<Double, Double>? = walkNotional(book.bids, notional)
    private fun buyQty(book: L2Book, qty: Double): Double? = walkQty(book.asks, qty)
    private fun sellQty(book: L2Book, qty: Double): Double? = walkQty(book.bids, qty)

    private fun walkNotional(levels: List<BookLevel>, notional: Double): Pair<Double, Double>? {
        var remaining = notional
        var qty = 0.0
        var cost = 0.0
        for (l in levels) {
            val takeQty = min(l.sz, remaining / l.px)
            qty += takeQty
            cost += takeQty * l.px
            remaining -= takeQty * l.px
            if (remaining <= 1e-6) return Pair(cost / qty, qty)
        }
        return null
    }

    private fun walkQty(levels: List<BookLevel>, qty: Double): Double? {
        var remaining = qty
        var cost = 0.0
        var got = 0.0
        for (l in levels) {
            val take = min(l.sz, remaining)
            got += take
            cost += take * l.px
            remaining -= take
            if (remaining <= 1e-9) return cost / got
        }
        return null
    }
}
