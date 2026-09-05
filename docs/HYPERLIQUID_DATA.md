# HyperLiquid Data

The MVP uses only public WebSocket L2 data.

## Subscription

```json
{
  "method": "subscribe",
  "subscription": {
    "type": "l2Book",
    "coin": "xyz:SP500"
  }
}
```

## L2 fields used

Each level has:

```text
px: price
sz: size
n: number of orders at that level
```

The app currently uses top 5 bid levels and top 5 ask levels.

## Not used in MVP

- trades tape
- candles
- funding
- open interest
- account data
- private fills/orders
