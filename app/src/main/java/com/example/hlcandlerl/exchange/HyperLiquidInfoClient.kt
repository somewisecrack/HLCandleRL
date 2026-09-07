package com.example.hlcandlerl.exchange

import com.example.hlcandlerl.data.Candle
import com.example.hlcandlerl.data.PerpContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HyperLiquidInfoClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    fun loadContext(coin: String): PerpContext = loadPerpAsset(coin).context

    fun loadRecentCandles(coin: String, interval: String = "1m", lookbackMillis: Long = 3_600_000L): List<Candle> {
        val now = System.currentTimeMillis()
        val payload = JSONObject()
            .put("type", "candleSnapshot")
            .put("req", JSONObject()
                .put("coin", coin)
                .put("interval", interval)
                .put("startTime", now - lookbackMillis)
                .put("endTime", now))
        val arr = org.json.JSONArray(postText(payload))
        return List(arr.length()) { i -> parseCandle(arr.getJSONObject(i), coin, interval) }
            .filter { it.open > 0.0 && it.high >= it.low && it.close > 0.0 }
            .sortedBy { it.openTimeMillis }
    }

    private fun parseCandle(d: JSONObject, coin: String, interval: String): Candle = Candle(
        coin = d.optString("s", coin),
        interval = d.optString("i", interval),
        openTimeMillis = d.getLong("t"),
        closeTimeMillis = d.optLong("T", d.getLong("t")),
        open = d.getString("o").toDouble(),
        high = d.getString("h").toDouble(),
        low = d.getString("l").toDouble(),
        close = d.getString("c").toDouble(),
        volume = d.getString("v").toDouble(),
        trades = d.optInt("n", 0)
    )

    private fun loadPerpAsset(coin: String): AssetContext {
        val dex = coin.substringBefore(":", missingDelimiterValue = "")
        val metaReq = JSONObject().put("type", "metaAndAssetCtxs")
        if (dex.isNotBlank()) metaReq.put("dex", dex)
        val arr = org.json.JSONArray(postText(metaReq))
        val meta = arr.getJSONObject(0)
        val ctxs = arr.getJSONArray(1)
        val universe = meta.getJSONArray("universe")
        val shortName = coin.substringAfter(":")
        for (i in 0 until universe.length()) {
            val asset = universe.getJSONObject(i)
            val name = asset.optString("name")
            if (name == coin || name == shortName) {
                val ctx = ctxs.getJSONObject(i)
                return AssetContext(
                    context = PerpContext(
                        openInterest = ctx.optString("openInterest", "0").toDouble(),
                        markPx = ctx.optString("markPx", "0").toDouble(),
                        oraclePx = ctx.optString("oraclePx", "0").toDouble(),
                        premium = ctx.optString("premium", "0").toDouble(),
                        dayNtlVlm = ctx.optString("dayNtlVlm", "0").toDouble(),
                        dayBaseVlm = ctx.optString("dayBaseVlm", "0").toDouble(),
                        source = "hyperliquid_info:metaAndAssetCtxs coin=$coin"
                    )
                )
            }
        }
        error("selected asset $coin not found in HyperLiquid metaAndAssetCtxs")
    }

    private fun postText(payload: JSONObject): String {
        val req = Request.Builder()
            .url("https://api.hyperliquid.xyz/info")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HyperLiquid /info HTTP ${resp.code}")
            return resp.body?.string() ?: error("empty HyperLiquid /info response")
        }
    }

    private data class AssetContext(val context: PerpContext)
}
