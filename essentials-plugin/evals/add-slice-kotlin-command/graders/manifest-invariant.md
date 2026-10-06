---
type: regex
target: { source: file, path: "backend/src/main/kotlin/com/example/shop/orders/use_cases/place_order/slice.yaml" }
pattern: 'invariants:[\s\S]*quantity'
match: contains
flags: i
---
