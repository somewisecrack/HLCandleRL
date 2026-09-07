package com.example.hlcandlerl.engine

object AppRuntime {
    // Curated from HyperLiquid public metaAndAssetCtxs dayNtlVlm: keep this list liquid enough for
    // 1m candle learning and virtual execution diagnostics, rather than exposing every listed market.
    val markets: Map<String, String> = linkedMapOf(
        // Base HyperLiquid perps
        "BTC" to "BTC",
        "ETH" to "ETH",
        "ZEC" to "ZEC",
        "HYPE" to "HYPE",
        "PUMP" to "PUMP",
        "PONS" to "PONS",
        "SOL" to "SOL",
        "TAO" to "TAO",
        "ARB" to "ARB",
        "XRP" to "XRP",
        "NEAR" to "NEAR",
        "LIT" to "LIT",
        "LINK" to "LINK",
        "WLD" to "WLD",
        "UNI" to "UNI",
        "XMR" to "XMR",
        "ENA" to "ENA",
        "JUP" to "JUP",
        "INJ" to "INJ",
        "BNB" to "BNB",
        "DOGE" to "DOGE",
        "LTC" to "LTC",
        "SUI" to "SUI",
        "CRV" to "CRV",

        // Hyperliquid XYZ/high-liquidity perp markets: equities, indices, commodities, ETFs.
        "SKHX" to "xyz:SKHX",
        "WTI" to "xyz:CL",
        "Brent" to "xyz:BRENTOIL",
        "XYZ100" to "xyz:XYZ100",
        "SP500" to "xyz:SP500",
        "Gold" to "xyz:GOLD",
        "MU" to "xyz:MU",
        "SNDK" to "xyz:SNDK",
        "Silver" to "xyz:SILVER",
        "SKHY" to "xyz:SKHY",
        "DRAM" to "xyz:DRAM",
        "SMSN" to "xyz:SMSN",
        "INTC" to "xyz:INTC",
        "EWY" to "xyz:EWY",
        "NVDA" to "xyz:NVDA",
        "SPCX" to "xyz:SPCX",
        "HOOD" to "xyz:HOOD",
        "CRCL" to "xyz:CRCL",
        "MSTR" to "xyz:MSTR",
        "GOOGL" to "xyz:GOOGL",
        "PLTR" to "xyz:PLTR",
        "TSLA" to "xyz:TSLA",
        "AAPL" to "xyz:AAPL"
    )
    val engine: RlEngine by lazy { RlEngine(defaultMarketLabel = "BTC", markets = markets) }
}
