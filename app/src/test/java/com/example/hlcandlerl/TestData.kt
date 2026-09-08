package com.example.hlcandlerl

import com.example.hlcandlerl.data.Candle
import com.example.hlcandlerl.data.MarketFrame
import com.example.hlcandlerl.data.PerpContext

/** Deterministic synthetic candles so broker/feature/trainer tests never touch the network. */
fun candle(
    close: Double,
    openTimeMillis: Long = 0L,
    open: Double = close,
    high: Double = maxOf(open, close),
    low: Double = minOf(open, close),
    volume: Double = 10.0,
    trades: Int = 5,
    coin: String = "BTC"
) = Candle(
    coin = coin,
    interval = "1m",
    openTimeMillis = openTimeMillis,
    closeTimeMillis = openTimeMillis + 60_000L,
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
    trades = trades
)

fun frame(close: Double, openTimeMillis: Long = 0L, context: PerpContext = PerpContext()) =
    MarketFrame(candle(close, openTimeMillis), context)

/** Deterministic zig-zag price series with real movement so PnL cannot be trivially zero. */
fun syntheticSeries(n: Int, base: Double = 100.0, coin: String = "BTC"): List<Candle> =
    (0 until n).map { i ->
        val px = base + 5.0 * kotlin.math.sin(i / 7.0) + 0.02 * i
        candle(close = px, openTimeMillis = i * 60_000L, open = px - 0.1, high = px + 0.3, low = px - 0.4, coin = coin)
    }
