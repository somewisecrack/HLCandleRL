package com.example.hlphonerl.engine

object AppRuntime {
    val markets: Map<String, String> = linkedMapOf(
        "SP500" to "xyz:SP500",
        "BTC" to "BTC",
        "ETH" to "ETH",
        "SOL" to "SOL",
        "HYPE" to "HYPE",
        "XRP" to "XRP",
        "DOGE" to "DOGE",
        "LINK" to "LINK",
        "WTI" to "xyz:CL",
        "Gold" to "xyz:GOLD"
    )
    val engine: RlEngine by lazy { RlEngine(defaultMarketLabel = "SP500", markets = markets) }
}
