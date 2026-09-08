package com.example.hlcandlerl.data

data class Candle(
    val coin: String,
    val interval: String,
    val openTimeMillis: Long,
    val closeTimeMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val trades: Int = 0
)

data class PerpContext(
    val openInterest: Double = 0.0,
    val markPx: Double = 0.0,
    val oraclePx: Double = 0.0,
    val premium: Double = 0.0,
    val dayNtlVlm: Double = 0.0,
    val dayBaseVlm: Double = 0.0,
    val source: String = "not_loaded"
)

data class MarketFrame(
    val candle: Candle,
    val context: PerpContext
) {
    // Candle RL executes virtual trades on the candle close proxy. Public mark/oracle/OI/premium
    // remain context features/display fields, but must not replace historical candle prices during
    // offline replay or all historical steps can share one stale mark price.
    val price: Double get() = candle.close
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
