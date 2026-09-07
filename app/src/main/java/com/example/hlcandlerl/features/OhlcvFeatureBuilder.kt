package com.example.hlcandlerl.features

import com.example.hlcandlerl.data.Candle
import com.example.hlcandlerl.data.MarketFrame
import com.example.hlcandlerl.data.Position
import com.example.hlcandlerl.data.Side
import kotlin.math.ln

class OhlcvFeatureBuilder(private val window: Int = 32) {
    private val candles = ArrayDeque<Candle>()
    val featureSize: Int = window * 7 + 6 + 4

    fun seed(history: List<Candle>) {
        candles.clear()
        history.sortedBy { it.openTimeMillis }.takeLast(window).forEach { candles.addLast(it) }
    }

    fun add(frame: MarketFrame) {
        val c = frame.candle
        if (candles.lastOrNull()?.openTimeMillis == c.openTimeMillis) {
            candles.removeLast()
        }
        candles.addLast(c)
        while (candles.size > window) candles.removeFirst()
    }

    fun build(frame: MarketFrame, position: Position?, step: Long): FloatArray? {
        add(frame)
        if (candles.size < 2) return null
        return encode(frame, position, step)
    }

    fun patchPosition(state: FloatArray, position: Position?, step: Long): FloatArray {
        val out = state.copyOf()
        out[out.size - 4] = when (position?.side) { Side.LONG -> 1f; Side.SHORT -> -1f; null -> 0f }
        out[out.size - 3] = position?.let { ((step - it.entryStep).toDouble() / 300.0).clip(0.0, 5.0).toFloat() } ?: 0f
        if (position == null) out[out.size - 2] = 0f
        return out
    }

    fun reset() { candles.clear() }

    private fun encode(frame: MarketFrame, position: Position?, step: Long): FloatArray {
        val out = ArrayList<Float>(featureSize)
        val list = candles.toList()
        val pad = window - list.size
        repeat(pad) { repeat(7) { out += 0f } }
        for (i in list.indices) {
            val c = list[i]
            val prevClose = if (i == 0) c.open else list[i - 1].close
            val close = c.close.coerceAtLeast(1e-9)
            out += (ln(c.open / prevClose.coerceAtLeast(1e-9)) * 10_000.0).clip(-500.0, 500.0).toFloat()
            out += (ln(c.high / close) * 10_000.0).clip(0.0, 500.0).toFloat()
            out += (ln(c.low / close) * 10_000.0).clip(-500.0, 0.0).toFloat()
            out += (ln(c.close / prevClose.coerceAtLeast(1e-9)) * 10_000.0).clip(-500.0, 500.0).toFloat()
            out += (ln(1.0 + c.volume)).clip(0.0, 30.0).toFloat()
            out += (ln(1.0 + c.trades)).clip(0.0, 15.0).toFloat()
            out += ((c.close - c.open) / close * 10_000.0).clip(-500.0, 500.0).toFloat()
        }

        val ctx = frame.context
        val px = frame.price.coerceAtLeast(1e-9)
        out += (ln(1.0 + ctx.openInterest)).clip(0.0, 40.0).toFloat()
        out += (((ctx.markPx.takeIf { it > 0.0 } ?: px) - px) / px * 10_000.0).clip(-200.0, 200.0).toFloat()
        out += (((ctx.oraclePx.takeIf { it > 0.0 } ?: px) - px) / px * 10_000.0).clip(-200.0, 200.0).toFloat()
        out += (ctx.premium * 10_000.0).clip(-200.0, 200.0).toFloat()
        out += (ln(1.0 + ctx.dayNtlVlm)).clip(0.0, 40.0).toFloat()
        out += (ln(1.0 + ctx.dayBaseVlm)).clip(0.0, 40.0).toFloat()

        val side = when (position?.side) { Side.LONG -> 1f; Side.SHORT -> -1f; null -> 0f }
        val age = position?.let { ((step - it.entryStep).toDouble() / 300.0).clip(0.0, 5.0).toFloat() } ?: 0f
        val unreal = position?.let {
            val sign = if (it.side == Side.LONG) 1.0 else -1.0
            (sign * (px - it.entryPx) / it.entryPx * 10_000.0).clip(-1000.0, 1000.0).toFloat()
        } ?: 0f
        out += side
        out += age
        out += unreal
        out += 1f
        return out.toFloatArray()
    }

    private fun Double.clip(lo: Double, hi: Double) = coerceIn(lo, hi)
}

private operator fun <T> ArrayList<T>.plusAssign(x: T) { add(x) }
