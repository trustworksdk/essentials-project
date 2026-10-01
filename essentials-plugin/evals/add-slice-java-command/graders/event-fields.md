---
type: regex
target: { source: file, path: "backend/src/main/java/com/example/shop/orders/events/OrderPlaced.java" }
pattern: 'sku[\s\S]*quantity'
match: contains
---
