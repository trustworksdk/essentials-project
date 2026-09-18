# Taking the money: authorization, capture, and everything that can go wrong

The `payment` context asks the card network two different questions, and it asks them in two different ways.

**Authorization** — "may we reserve this money?" — is synchronous. The bank answers in a few hundred
milliseconds, there is nothing to record until it has, and waiting is the simplest correct thing to do. It
happens when the order is placed.

**Capture** — "give us the money" — is not. A settlement is a *process* at the other end, and every real card
platform today answers the same way: `202 Accepted`, here is a reference, we will call you back. It happens when
the parcel is packed.

Everything awkward in this context follows from that second fact, and none of it can be avoided by wishing the
API were synchronous.

## Why capture at packing

A hold is a promise the bank can still break. `FundsCaptured` is money.

Retail captures at dispatch, and that timing is not an accounting detail — it is what makes failure survivable.
Consider the same refusal at three different moments:

| Capture at | A refused settlement means |
|---|---|
| Order placement | The order is unpaid and the warehouse may already have packed it. Un-pack, re-shelve, refund. |
| **Packing (this demo)** | **The parcel is on a bench. It simply never becomes dispatchable. Nothing to undo.** |
| Dispatch | A human is waiting on a webhook while holding a box, and the button has to block. |

So the capture sits between packing and dispatch, and the warehouse work item is what enforces it: a packed
order shows `AWAITING_PAYMENT` until `FundsCaptured` arrives, and only then offers **Ship**. `ShipOrderDecider`
does not and cannot check this — it sees the `ShippingOrders` stream alone, and payment lives in another
context's stream. The read model is the guard, which is the same pattern the declined-authorization case uses.

Compensation you cannot actually perform — a courier already holding the parcel — is the thing to design away,
not to model.

## The order of operations that makes reconciliation possible

`CaptureFundsWhenPackagedPolicy` does exactly this, in exactly this order:

1. Send `RequestFundsCapture`, so `FundsCaptureRequested` — carrying the idempotency key — is **committed** to
   the stream.
2. *Then* call the gateway.

Never the other way round. Between those two steps the process can die, the call can time out, the answer can be
lost. In every one of those cases the recorded request is what lets us find the charge again and ask about it. A
call made before the request was recorded is a charge that may exist at the bank with nothing in our system to
match it against — the one state from which no reconciliation is possible.

## The idempotency key

```kotlin
fun forOrderCapture(orderId: OrderId): IdempotencyKey = IdempotencyKey("capture:$orderId")
```

Two properties, both easy to get wrong:

- **Derived, not random.** Every retry of the same charge recomputes the same key. A key minted per attempt —
  `UUID.randomUUID()` inside the retry loop — looks like an idempotency key and provides none of its protection:
  the gateway sees a new key and charges again.
- **Recorded before the call**, on the event, so whoever retries uses the key the charge was made with rather
  than computing a new one from possibly-changed inputs.

The key is the gateway's contract, not ours: *it* uses the key to tell "the same charge" from "another charge".
Our own idempotency check — the decider refusing to record a second request — is a different, inner guard. Both
are needed. The decider cannot stop a timed-out call from having succeeded at the bank, and the key cannot stop
a stream from accumulating duplicate requests.

## Three things a webhook does that a response does not

```
gateway --202--> us            (no outcome yet)
   ...
gateway --POST /api/payment/webhooks/card-gateway--> us     (the outcome, eventually)
```

**It arrives twice.** Webhook delivery is at-least-once, always. `RecordCaptureOutcomeDecider` returns `null`
when an outcome is already recorded, so the second delivery records nothing, re-triggers nothing, and releases
no second parcel. The demo's gateway duplicates *every* callback on purpose, so this path is exercised in every
single run rather than in production only.

**It can arrive before we are ready.** The gateway is fast and our own commit is not instant, so an outcome can
turn up for a capture the stream does not know about yet. That is a timing problem, not a bad message, so
`CaptureNotYetRequestedException` is a plain `RuntimeException` and the Inbox retries it a moment later. It must
**not** be `require(...)`: `IllegalArgumentException` is on the framework's permanent-error list and would
dead-letter the message on first delivery, no matter what the redelivery policy says.

**It may never arrive at all.** See below.

The endpoint itself does two things — verify the signature, store the message — and answers `202`. It never does
the work. A webhook caller is a third party with its own timeout and its own retry policy; applying the outcome
inline turns a slow projection into a gateway timeout, which turns into a retry that races the request still
running. The `Inbox` write happens in the request's own transaction (`@Transactional`), so the `202` is only sent
if the message is safely stored, and consumption is `SingleGlobalConsumer` so two instances cannot both record an
outcome for the same order and neither see the other's.

The signature check is demo-grade — a shared secret, where the real thing is an HMAC over the raw body plus a
timestamp, compared in constant time, with the timestamp checked so a captured callback cannot be replayed next
week. What is *not* demo-grade is the decision to verify at all: this URL is public and it moves money.

## A timeout is not a failure

It is an **unknown outcome**, and the difference matters. A failure is an answer you can act on; an unknown is a
charge that may or may not exist. Assuming either way is how customers get double-charged or goods get shipped
unpaid.

So an unanswered capture becomes a work item of its own — `captures_awaiting_outcome`, the to-do view whose rows
are unknowns — and `CaptureReconciler` drains it. It is the only automation here triggered by a **clock** rather
than by an event, because there is no event for "the webhook never came" and there never can be: a system that
only reacts to messages it receives cannot detect a message it did not receive.

For each capture outstanding too long, it asks the gateway (`outcomeFor`) and acts on one of three answers:

| Gateway says | Reconciler does | Why |
|---|---|---|
| Captured / Failed | Record the outcome, through the same command the webhook uses | The callback was lost; the money still moved, or did not |
| Never received | Ask again, **with the same key** | Our request never arrived. The only branch that charges anyone, and safe because the key is derived |
| Still pending | Nothing | Impatience here is how the system meant to prevent a double charge creates one |

A healthy system keeps that table nearly empty, which makes a row that lingers a real operational signal — money
in an unknown state is worth an alert.

## Making each path happen on demand

All dials are in `WebshopPaymentProperties`:

| To see | Do this | What happens |
|---|---|---|
| Happy capture | Any price under 5000 | `HELD` → pack → `CAPTURE_PENDING` → webhook (twice) → `CAPTURED` → **Ship** appears |
| Authorization declined | Price over 10000 | `REJECTED` before anything is packed; work item `BLOCKED`, cancel offered |
| Settlement refused after packing | Price between 5000 and 10000 | `HELD`, packs fine, then `CAPTURE_FAILED`; work item `BLOCKED` and the parcel never leaves |
| Lost callback | Price ending in `.13` | Gateway settles and drops the callback; the row sits in **Captures awaiting outcome** until the reconciler asks |
| No capture at all | Pay by `INVOICE` | No hold, no capture, `paymentSettled` set from `PaymentDetailsAdded` so dispatch is never gated |

## One hazard this expansion walked straight into

The first run of `CaptureFundsWhenPackagedPolicy` against a database with history in it **charged nine orders
that had already shipped.**

Subscriptions default to starting at the beginning of the stream. For a projection that is correct and desirable
— a read model is supposed to be rebuildable by replaying everything. For a policy that touches the outside
world it is a disaster: the policy replayed every `CreditCardHoldPlaced` and `OrderPackagingRequested` in the
store, decided each was complete work, and called the gateway.

Both payment policies now override `isStartSubscriptionFromLatestEvent()` to `true`, so a *new* subscription
starts at the head. Replaying deliberately is still possible — resetting a subscription is an admin operation —
and that is the right place for the decision, because whoever resets it can be told what it will do.

The rule worth taking away: **ask what replaying a subscription costs.** Rewriting a row costs nothing. Charging
a card costs money.
