package com.example.hlphonerl.exchange

import com.example.hlphonerl.data.BookLevel
import com.example.hlphonerl.data.L2Book
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject

class HyperLiquidWsClient(
    private val coin: String = "xyz:SP500",
    private val onBook: (L2Book) -> Unit,
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
                    .put("subscription", JSONObject().put("type", "l2Book").put("coin", coin))
                webSocket.send(sub.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseBook(text)?.let(onBook)
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

    private fun parseBook(text: String): L2Book? {
        return try {
            val root = JSONObject(text)
            if (root.optString("channel") != "l2Book") return null
            val data = root.getJSONObject("data")
            val msgCoin = data.optString("coin", coin)
            if (msgCoin != coin) return null
            if (!data.has("time")) return null
            val levels = data.getJSONArray("levels")
            val bids = parseSide(levels.getJSONArray(0))
            val asks = parseSide(levels.getJSONArray(1))
            if (bids.isEmpty() || asks.isEmpty()) return null
            if (bids.first().px <= 0.0 || asks.first().px <= 0.0 || bids.first().px >= asks.first().px) return null
            L2Book(
                coin = msgCoin,
                timeMillis = data.getLong("time"),
                bids = bids,
                asks = asks
            )
        } catch (_: Exception) { null }
    }

    private fun parseSide(arr: JSONArray): List<BookLevel> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        BookLevel(o.getString("px").toDouble(), o.getString("sz").toDouble(), o.optInt("n", 0))
    }.filter { it.px > 0.0 && it.sz > 0.0 }
}
