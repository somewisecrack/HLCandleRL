package com.example.hlcandlerl.data

import com.example.hlcandlerl.candle
import org.junit.Assert.assertEquals
import org.junit.Test

class MarketFrameTest {

    @Test
    fun executionPriceIsCandleCloseNotMarkPrice() {
        val f = MarketFrame(
            candle = candle(close = 100.0),
            context = PerpContext(markPx = 999.0, oraclePx = 888.0, premium = 0.01, openInterest = 123.0)
        )
        assertEquals(100.0, f.price, 0.0)
    }

    @Test
    fun executionPriceIgnoresContextEvenWhenContextIsEmpty() {
        val f = MarketFrame(candle(close = 42.5), PerpContext())
        assertEquals(42.5, f.price, 0.0)
    }
}
