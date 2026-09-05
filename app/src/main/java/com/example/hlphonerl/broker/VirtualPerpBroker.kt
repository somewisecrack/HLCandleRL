package com.example.hlphonerl.broker

import com.example.hlphonerl.data.*
import kotlin.math.min

class VirtualPerpBroker(
    private val notionalUsd: Double = 1000.0,
    private val takerFeeRate: Double = 0.00045,
    private val maxHoldSteps: Long = 300
) {
    var cashPnl: Double = 0.0
        private set
    var position: Position? = null
        private set
    private var lastEquity: Double = 0.0

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
        when (effective) {
            Action.WAIT -> reason = if (position == null) "wait_flat" else "invalid_wait_while_holding"
            Action.HOLD -> reason = if (position != null) "hold" else "invalid_hold_flat"
            Action.ENTER_LONG -> if (position == null) {
                fillBuy(book, notionalUsd)?.let { (px, qty) ->
                    cashPnl -= notionalUsd * takerFeeRate
                    position = Position(Side.LONG, px, qty, System.currentTimeMillis(), step)
                    reason = "enter_long"
                } ?: run { reason = "no_ask_liquidity" }
            }
            Action.ENTER_SHORT -> if (position == null) {
                fillSell(book, notionalUsd)?.let { (px, qty) ->
                    cashPnl -= notionalUsd * takerFeeRate
                    position = Position(Side.SHORT, px, qty, System.currentTimeMillis(), step)
                    reason = "enter_short"
                } ?: run { reason = "no_bid_liquidity" }
            }
            Action.EXIT -> position?.let { pos ->
                val exit = if (pos.side == Side.LONG) sellQty(book, pos.qty) else buyQty(book, pos.qty)
                exit?.let { px ->
                    val gross = if (pos.side == Side.LONG) (px - pos.entryPx) * pos.qty else (pos.entryPx - px) * pos.qty
                    val fee = px * pos.qty * takerFeeRate
                    realized = gross - fee
                    cashPnl += realized
                    position = null
                    reason = if (forced) "forced_exit_max_hold" else "exit"
                } ?: run { reason = "no_exit_liquidity" }
            }
        }
        val after = equity(book)
        val reward = after - before
        lastEquity = after
        return StepResult(effective, reward, after, realized, reason)
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
