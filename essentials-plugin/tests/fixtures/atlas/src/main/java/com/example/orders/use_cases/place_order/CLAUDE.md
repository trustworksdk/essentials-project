# place_order

Takes a `PlaceOrder` from the API and publishes **OrderPlaced**.

## Invariants

- A quantity of zero or less is rejected with an `IllegalArgumentException`.
- The order id comes from the caller; see [the API](PlaceOrderAPI.java).

| Field | Meaning |
|---|---|
| orderId | the caller's id for the order |
| quantity | how many, *always* positive |

Raw markup such as <b>bold</b> & <script>alert(1)</script> is shown as text.

```java
new PlaceOrder("o-1", "c-7", 2);
```
