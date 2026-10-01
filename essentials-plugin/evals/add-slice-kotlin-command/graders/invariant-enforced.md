---
type: llm
focus: { source: file, path: "backend/src/main/kotlin/com/example/shop/orders/use_cases/place_order/PlaceOrderDecider.kt" }
---

The file is the decider of a `place_order` command slice. The user asked for one invariant: a PlaceOrder whose
quantity is below 1 is rejected.

PASS if the decider rejects a PlaceOrder with a quantity below 1 (throws, or returns a rejection the file makes
explicit) and otherwise decides an OrderPlaced event carrying the command's sku and quantity.
FAIL if the check is missing, inverted or left as a TODO, or if the event is not carrying both facts.
