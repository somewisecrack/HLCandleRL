package com.example.hlcandlerl.exchange

import com.example.hlcandlerl.data.Candle
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class HyperLiquidCandleWsClient(
    private val coin: String,
    private val interval: String = "1m",
    private val onCandle: (Candle) -> Unit,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closedByUser = false
    @Volatile private var reconnectDelayMs = MIN_RECONNECT_MS

    fun connect() {
        closedByUser = false
        reconnectDelayMs = MIN_RECONNECT_MS
        openSocket()
    }

    private fun openSocket() {
        if (closedByUser) return
        val req = Request.Builder().url("wss://api.hyperliquid.xyz/ws").build()
        ws = shared.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelayMs = MIN_RECONNECT_MS
                onStatus("connected")
                val sub = JSONObject()
                    .put("method", "subscribe")
                    .put("subscription", JSONObject().put("type", "candle").put("coin", coin).put("interval", interval))
                webSocket.send(sub.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closedByUser) return
                parseCandle(text)?.let(onCandle)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closedByUser) return
                onStatus("ws failure: ${t.message}; reconnecting")
                reconnectLater()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closedByUser) return
                onStatus("closed: $reason")
                reconnectLater()
            }
        })
    }

    /**
     * Exponential backoff, capped. A fixed 3 s retry meant a phone with no connectivity woke a new
     * reconnect thread every three seconds for as long as the learner was running.
     */
    private fun reconnectLater() {
        if (closedByUser) return
        val delay = reconnectDelayMs
        reconnectDelayMs = min(delay * 2, MAX_RECONNECT_MS)
        Thread {
            try { Thread.sleep(delay) } catch (_: InterruptedException) { }
            if (!closedByUser) openSocket()
        }.apply { isDaemon = true }.start()
    }

    fun close() {
        closedByUser = true
        try { ws?.close(1000, "user closed") } catch (_: Exception) { }
        // cancel() also tears down a socket that never finished connecting, which close() leaves open.
        try { ws?.cancel() } catch (_: Exception) { }
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

    companion object {
        private const val MIN_RECONNECT_MS = 3_000L
        private const val MAX_RECONNECT_MS = 60_000L

        /**
         * One OkHttpClient for every socket this app opens. A client owns a dispatcher thread pool
         * and a connection pool, so building one per learner start leaked both every time the user
         * pressed Start.
         */
        internal val shared: OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
