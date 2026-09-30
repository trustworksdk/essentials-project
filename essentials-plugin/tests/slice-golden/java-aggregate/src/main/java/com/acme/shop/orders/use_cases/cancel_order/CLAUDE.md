# orders.cancel_order — command slice (aggregate lane)

One intent: **TODO, one sentence.**

## Files

| File | Role |
|---|---|
| `CancelOrder.java` | the intent as data. No routing marker on this lane |
| `CancelOrderHandler.java` | load → call one aggregate method → done. Holds **no** business rule |
| `CancelOrderAPI.java` | the one endpoint (§R2) |
| `../../events/OrderCancelled.java` | the event this slice emits, owned here, living in the BC's `events/` |

## Where the decision lives

**On `Order`, not in `CancelOrderHandler`.** The handler loads and delegates; the guard runs
inside the aggregate before `apply`, so a rejected command leaves nothing in the stream.

If you are about to add an `if` about domain state to the handler, it belongs on the aggregate
(`rules/slice-design.md` § The aggregate's own bar). That is the single rule that keeps this lane
from collapsing into a god-service with an anaemic data holder behind it.

## Idempotency

The command bus delivers at least once. `Order.applyPlaceholder` returns `false` when the
state was already reached, so a redelivered `CancelOrder` appends no second event. Creation slices
guard with `Orders.isOrderMissing` instead.

## Done means wired

`@Component` + a scanned package is the whole registration —
`ReactiveHandlersBeanPostProcessor` does the rest. Confirm
`reactive-bean-post-processor-enabled` is not switched off in any profile: disabling it unwires
every handler in the application, with no compile error and no failing unit test. That is why
`CancelOrderIT` sends through the bus rather than calling the handler directly.
