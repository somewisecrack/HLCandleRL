package com.example.hlphonerl.features

import com.example.hlphonerl.data.L2Book
import com.example.hlphonerl.data.Position
import com.example.hlphonerl.data.Side
import kotlin.math.ln

class L2FeatureBuilder(private val depth: Int = 5) {
    private var lastMid: Double? = null
    private var lastImb: Double? = null

    val featureSize: Int = 6 + depth * 6 + 4

    fun build(book: L2Book, position: Position?, step: Long): FloatArray? {
        val mid = book.mid ?: return null
        val spread = book.spread ?: return null
        val bids = book.bids.take(depth)
        val asks = book.asks.take(depth)
        if (bids.size < depth || asks.size < depth) return null

        val bidSum = bids.sumOf { it.sz }.coerceAtLeast(1e-9)
        val askSum = asks.sumOf { it.sz }.coerceAtLeast(1e-9)
        val imb = (bidSum - askSum) / (bidSum + askSum)
        val micro = ((book.bestAsk ?: mid) * bidSum + (book.bestBid ?: mid) * askSum) / (bidSum + askSum)
        val midRet = lastMid?.let { ln(mid / it) } ?: 0.0
        val imbChange = lastImb?.let { imb - it } ?: 0.0
        lastMid = mid
        lastImb = imb

        val out = ArrayList<Float>(featureSize)
        out += (spread / mid * 10_000.0).clip(-50.0, 50.0).toFloat()
        out += (midRet * 10_000.0).clip(-100.0, 100.0).toFloat()
        out += imb.toFloat()
        out += imbChange.clip(-2.0, 2.0).toFloat()
        out += ((micro - mid) / mid * 10_000.0).clip(-100.0, 100.0).toFloat()
        out += ln(1.0 + bidSum + askSum).toFloat()

        for (i in 0 until depth) {
            val b = bids[i]
            val a = asks[i]
            out += ((mid - b.px) / mid * 10_000.0).clip(0.0, 200.0).toFloat()
            out += ln(1.0 + b.sz).toFloat()
            out += ln(1.0 + b.n).toFloat()
            out += ((a.px - mid) / mid * 10_000.0).clip(0.0, 200.0).toFloat()
            out += ln(1.0 + a.sz).toFloat()
            out += ln(1.0 + a.n).toFloat()
        }

        val side = when (position?.side) { Side.LONG -> 1f; Side.SHORT -> -1f; null -> 0f }
        val age = position?.let { ((step - it.entryStep).toDouble() / 300.0).clip(0.0, 5.0).toFloat() } ?: 0f
        val unreal = position?.let {
            val sign = if (it.side == Side.LONG) 1.0 else -1.0
            (sign * (mid - it.entryPx) / it.entryPx * 10_000.0).clip(-500.0, 500.0).toFloat()
        } ?: 0f
        out += side
        out += age
        out += unreal
        out += if (System.currentTimeMillis() - book.timeMillis < 3000) 1f else 0f

        return out.toFloatArray()
    }

    fun patchPosition(state: FloatArray, position: Position?, step: Long): FloatArray {
        val out = state.copyOf()
        out[out.size - 4] = when (position?.side) { Side.LONG -> 1f; Side.SHORT -> -1f; null -> 0f }
        out[out.size - 3] = position?.let { ((step - it.entryStep).toDouble() / 300.0).clip(0.0, 5.0).toFloat() } ?: 0f
        if (position == null) out[out.size - 2] = 0f
        return out
    }

    fun reset() {
        lastMid = null
        lastImb = null
    }

    private fun Double.clip(lo: Double, hi: Double) = coerceIn(lo, hi)
}

private operator fun <T> ArrayList<T>.plusAssign(x: T) { add(x) }
