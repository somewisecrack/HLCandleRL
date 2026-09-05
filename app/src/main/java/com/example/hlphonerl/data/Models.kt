package com.example.hlphonerl.data

data class BookLevel(val px: Double, val sz: Double, val n: Int)

data class L2Book(
    val coin: String,
    val timeMillis: Long,
    val bids: List<BookLevel>,
    val asks: List<BookLevel>
) {
    val bestBid: Double? get() = bids.firstOrNull()?.px
    val bestAsk: Double? get() = asks.firstOrNull()?.px
    val mid: Double? get() = if (bestBid != null && bestAsk != null) (bestBid!! + bestAsk!!) / 2.0 else null
    val spread: Double? get() = if (bestBid != null && bestAsk != null) bestAsk!! - bestBid!! else null
}

enum class Action { WAIT, ENTER_LONG, ENTER_SHORT, HOLD, EXIT }
enum class Side { LONG, SHORT }

data class Position(
    val side: Side,
    val entryPx: Double,
    val qty: Double,
    val entryTimeMillis: Long,
    val entryStep: Long
)

data class StepResult(
    val action: Action,
    val reward: Double,
    val equity: Double,
    val realizedPnl: Double,
    val reason: String
)
