package com.example.hlcandlerl.exchange

import com.example.hlcandlerl.data.Candle
import okhttp3.*
import org.json.JSONObject

class HyperLiquidCandleWsClient(
    private val coin: String,
    private val interval: String = "1m",
    private val onCandle: (Candle) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closedByUser = false

    fun connect() {
        closedByUser = false
        openSocket()
    }

    private fun openSocket() {
        val req = Request.Builder().url("wss://api.hyperliquid.xyz/ws").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus("connected")
                val sub = JSONObject()
                    .put("method", "subscribe")
                    .put("subscription", JSONObject().put("type", "candle").put("coin", coin).put("interval", interval))
                webSocket.send(sub.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseCandle(text)?.let(onCandle)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("ws failure: ${t.message}; reconnecting")
                reconnectLater()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("closed: $reason")
                if (!closedByUser) reconnectLater()
            }
        })
    }

    private fun reconnectLater() {
        if (closedByUser) return
        Thread {
            try { Thread.sleep(3000) } catch (_: InterruptedException) { }
            if (!closedByUser) openSocket()
        }.start()
    }

    fun close() {
        closedByUser = true
        ws?.close(1000, "user closed")
        ws = null
    }

    internal fun parseCandle(text: String): Candle? {
        return try {
            val root = JSONObject(text)
            if (root.optString("channel") != "candle") return null
            val d = root.getJSONObject("data")
            val c = d.optString("s", coin)
            if (c != coin) return null
            val i = d.optString("i", interval)
            if (i != interval) return null
            Candle(
                coin = c,
                interval = i,
                openTimeMillis = d.getLong("t"),
                closeTimeMillis = d.optLong("T", d.getLong("t")),
                open = d.getString("o").toDouble(),
                high = d.getString("h").toDouble(),
                low = d.getString("l").toDouble(),
                close = d.getString("c").toDouble(),
                volume = d.getString("v").toDouble(),
                trades = d.optInt("n", 0)
            ).takeIf { it.open > 0.0 && it.high > 0.0 && it.low > 0.0 && it.close > 0.0 && it.high >= it.low }
        } catch (_: Exception) { null }
    }
}
