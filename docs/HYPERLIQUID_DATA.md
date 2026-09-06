# HyperLiquid Data

HL Candle RL uses public HyperLiquid candle data plus public perp context. It does not use L2 order books.

## Candle subscription

```json
{
  "method": "subscribe",
  "subscription": {
    "type": "candle",
    "coin": "xyz:SP500",
    "interval": "1m"
  }
}
```

## Candle fields used

```text
t: open time
T: close time
o: open
h: high
l: low
c: close
v: volume
n: trade count, if present
```

## Public perp context

Loaded from HyperLiquid `/info` / `metaAndAssetCtxs` for the selected market:

```text
funding
openInterest
markPx
oraclePx
premium
dayNtlVlm
dayBaseVlm
```

Fees are loaded from `/info` / `userFees` using the zero-address public baseline.

## Not used

- L2 book levels
- trades tape
- private account data
- private fills/orders
- real trading endpoints
- handcrafted indicator strategy rules
