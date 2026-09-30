---
type: regex
target: { source: file, path: "backend/src/main/java/com/acme/shop/orders/events/OrderPlaced.java" }
pattern: 'sku[\s\S]*quantity'
match: contains
---
