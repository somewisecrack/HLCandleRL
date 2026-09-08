package com.example.hlcandlerl.exchange

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperLiquidParsingTest {

    private val info = HyperLiquidInfoClient()

    @Test
    fun candleSnapshotParsesStringOhlcvFields() {
        val json = JSONObject(
            """{"t":1700000000000,"T":1700000059999,"s":"BTC","i":"1m",
                "o":"42000.5","h":"42100.0","l":"41950.25","c":"42050.75","v":"12.5","n":314}"""
        )
        val c = info.parseCandle(json, "BTC", "1m")
        assertEquals("BTC", c.coin)
        assertEquals("1m", c.interval)
        assertEquals(1700000000000L, c.openTimeMillis)
        assertEquals(1700000059999L, c.closeTimeMillis)
        assertEquals(42000.5, c.open, 1e-9)
        assertEquals(42100.0, c.high, 1e-9)
        assertEquals(41950.25, c.low, 1e-9)
        assertEquals(42050.75, c.close, 1e-9)
        assertEquals(12.5, c.volume, 1e-9)
        assertEquals(314, c.trades)
    }

    @Test
    fun candleFallsBackToRequestedCoinIntervalAndOpenTime() {
        val json = JSONObject("""{"t":100,"o":"1","h":"2","l":"0.5","c":"1.5","v":"3"}""")
        val c = info.parseCandle(json, "xyz:NVDA", "1m")
        assertEquals("xyz:NVDA", c.coin)
        assertEquals("1m", c.interval)
        assertEquals(100L, c.closeTimeMillis)
        assertEquals(0, c.trades)
    }

    @Test
    fun contextRequestUsesDexFieldOnlyForXyzMarkets() {
        val btc = info.buildContextRequest("BTC")
        assertEquals("metaAndAssetCtxs", btc.getString("type"))
        assertTrue("base perp request must not carry a dex field", !btc.has("dex"))

        val nvda = info.buildContextRequest("xyz:NVDA")
        assertEquals("xyz", nvda.getString("dex"))
        assertEquals("metaAndAssetCtxs", nvda.getString("type"))
    }

    private val metaResponse = """
        [{"universe":[{"name":"BTC"},{"name":"NVDA"}]},
         [{"openInterest":"1234.5","markPx":"42010.0","oraclePx":"42005.0","premium":"0.0001",
           "dayNtlVlm":"98765.4","dayBaseVlm":"2.5"},
          {"openInterest":"10.0","markPx":"180.25","oraclePx":"180.10","premium":"-0.0002",
           "dayNtlVlm":"5000.0","dayBaseVlm":"27.7"}]]
    """.trimIndent()

    @Test
    fun metaAndAssetCtxsParsesContextWithoutAnyFeeOrFundingDependency() {
        val ctx = info.parseContext(metaResponse, "BTC")
        assertEquals(1234.5, ctx.openInterest, 1e-9)
        assertEquals(42010.0, ctx.markPx, 1e-9)
        assertEquals(42005.0, ctx.oraclePx, 1e-9)
        assertEquals(0.0001, ctx.premium, 1e-12)
        assertEquals(98765.4, ctx.dayNtlVlm, 1e-9)
        assertEquals(2.5, ctx.dayBaseVlm, 1e-9)
        assertTrue(ctx.source.contains("metaAndAssetCtxs"))
    }

    @Test
    fun dexQualifiedCoinResolvesByShortName() {
        val ctx = info.parseContext(metaResponse, "xyz:NVDA")
        assertEquals(180.25, ctx.markPx, 1e-9)
        assertEquals(-0.0002, ctx.premium, 1e-12)
    }

    @Test(expected = IllegalStateException::class)
    fun unknownAssetFailsLoudlyRatherThanReturningZeroContext() {
        info.parseContext(metaResponse, "DOGE")
    }

    @Test
    fun webSocketParserAcceptsMatchingCandlesAndRejectsEverythingElse() {
        val ws = HyperLiquidCandleWsClient(coin = "BTC", interval = "1m", onCandle = {}, onStatus = {})
        val good = """{"channel":"candle","data":{"t":1,"T":2,"s":"BTC","i":"1m",
            "o":"100","h":"110","l":"90","c":"105","v":"3","n":7}}"""
        val c = ws.parseCandle(good)!!
        assertEquals(105.0, c.close, 1e-9)
        assertEquals(7, c.trades)

        assertNull("other channels must be ignored", ws.parseCandle(good.replace("\"candle\"", "\"l2Book\"")))
        assertNull("other coins must be ignored", ws.parseCandle(good.replace("\"BTC\"", "\"ETH\"")))
        assertNull("other intervals must be ignored", ws.parseCandle(good.replace("\"1m\"", "\"5m\"")))
        assertNull("malformed payloads must not throw", ws.parseCandle("not json"))
        assertNull("non-positive prices must be dropped", ws.parseCandle(good.replace("\"c\":\"105\"", "\"c\":\"0\"")))
        assertNull("inverted high/low must be dropped", ws.parseCandle(good.replace("\"h\":\"110\"", "\"h\":\"80\"")))
    }
}
