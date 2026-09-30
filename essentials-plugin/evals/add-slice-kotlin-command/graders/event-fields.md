---
type: regex
target: { source: file, path: "backend/src/main/kotlin/com/acme/shop/orders/events/OrderPlaced.kt" }
pattern: 'sku[\s\S]*quantity'
match: contains
---
