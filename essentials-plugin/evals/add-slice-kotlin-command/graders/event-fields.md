---
type: regex
target: { source: file, path: "backend/src/main/kotlin/com/example/shop/orders/events/OrderPlaced.kt" }
pattern: 'sku[\s\S]*quantity'
match: contains
---
