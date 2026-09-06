package com.example.hlphonerl.exchange

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
    val source: String
)

class HyperLiquidInfoClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    fun loadCosts(coin: String): HyperLiquidCosts {
        val fees = post(JSONObject().put("type", "userFees").put("user", ZERO_USER))
        val schedule = fees.optJSONObject("feeSchedule")
        val baseCross = fees.optString("userCrossRate", schedule?.optString("cross", "") ?: "").toDouble()
        val baseAdd = fees.optString("userAddRate", schedule?.optString("add", "") ?: "").toDouble()

        val dex = coin.substringBefore(":", missingDelimiterValue = "")
        val metaReq = JSONObject().put("type", "metaAndAssetCtxs")
        if (dex.isNotBlank()) metaReq.put("dex", dex)
        val metaResp = postArrayText(metaReq)
        val arr = org.json.JSONArray(metaResp)
        val meta = arr.getJSONObject(0)
        val ctxs = arr.getJSONArray(1)
        val universe = meta.getJSONArray("universe")
        var funding: Double? = null
        var scale: Double? = null
        val shortName = coin.substringAfter(":")
        for (i in 0 until universe.length()) {
            val asset = universe.getJSONObject(i)
            val name = asset.optString("name")
            if (name == coin || name == shortName) {
                funding = ctxs.getJSONObject(i).optString("funding", "0").toDouble()
                scale = asset.optString("deployerFeeScale", "1.0").toDouble()
                break
            }
        }
        val resolvedFunding = funding ?: error("selected asset $coin not found in HyperLiquid metaAndAssetCtxs")
        val resolvedScale = scale ?: error("selected asset $coin not found in HyperLiquid metaAndAssetCtxs")
        return HyperLiquidCosts(
            crossFeeRate = baseCross * resolvedScale,
            addFeeRate = baseAdd * resolvedScale,
            fundingRateHourly = resolvedFunding,
            source = "hyperliquid_info:userFees+metaAndAssetCtxs coin=$coin deployerFeeScale=$resolvedScale"
        )
    }

    private fun post(payload: JSONObject): JSONObject = JSONObject(postArrayText(payload))

    private fun postArrayText(payload: JSONObject): String {
        val req = Request.Builder()
            .url("https://api.hyperliquid.xyz/info")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HyperLiquid /info HTTP ${resp.code}")
            return resp.body?.string() ?: error("empty HyperLiquid /info response")
        }
    }

    private companion object {
        const val ZERO_USER = "0x0000000000000000000000000000000000000000"
    }
}
