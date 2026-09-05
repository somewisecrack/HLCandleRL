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
    private var ws: WebSocket? = null

    fun connect() {
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
                onStatus("ws failure: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("closed: $reason")
            }
        })
    }

    fun close() { ws?.close(1000, "user closed"); ws = null }

    private fun parseBook(text: String): L2Book? {
        val root = JSONObject(text)
        if (root.optString("channel") != "l2Book") return null
        val data = root.getJSONObject("data")
        val levels = data.getJSONArray("levels")
        return L2Book(
            coin = data.optString("coin", coin),
            timeMillis = data.optLong("time", System.currentTimeMillis()),
            bids = parseSide(levels.getJSONArray(0)),
            asks = parseSide(levels.getJSONArray(1))
        )
    }

    private fun parseSide(arr: JSONArray): List<BookLevel> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        BookLevel(o.getString("px").toDouble(), o.getString("sz").toDouble(), o.optInt("n", 0))
    }
}
