package com.example.hlcandlerl.exchange

import com.example.hlcandlerl.data.Candle
import com.example.hlcandlerl.data.PerpContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HyperLiquidCosts(
    val crossFeeRate: Double,
    val addFeeRate: Double,
    val fundingRateHourly: Double,
    val source: String,
    val context: PerpContext
)

class HyperLiquidInfoClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    fun loadCostsAndContext(coin: String): HyperLiquidCosts {
        val fees = post(JSONObject().put("type", "userFees").put("user", ZERO_USER))
        val schedule = fees.optJSONObject("feeSchedule")
        val baseCross = fees.optString("userCrossRate", schedule?.optString("cross", "") ?: "").toDouble()
        val baseAdd = fees.optString("userAddRate", schedule?.optString("add", "") ?: "").toDouble()
        val asset = loadPerpAsset(coin)
        val source = "hyperliquid_info:userFees+metaAndAssetCtxs coin=$coin deployerFeeScale=${asset.deployerScale}"
        return HyperLiquidCosts(
            crossFeeRate = baseCross * asset.deployerScale,
            addFeeRate = baseAdd * asset.deployerScale,
            fundingRateHourly = asset.context.fundingRateHourly,
            source = source,
            context = asset.context.copy(source = source)
        )
    }

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
                val scale = asset.optString("deployerFeeScale", "1.0").toDouble()
                return AssetContext(
                    deployerScale = scale,
                    context = PerpContext(
                        fundingRateHourly = ctx.optString("funding", "0").toDouble(),
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

    private fun post(payload: JSONObject): JSONObject = JSONObject(postText(payload))

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

    private data class AssetContext(val deployerScale: Double, val context: PerpContext)

    private companion object {
        const val ZERO_USER = "0x0000000000000000000000000000000000000000"
    }
}
