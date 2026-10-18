# `integration` — not a bounded context

Shared mechanics for receiving the simulators' events: the exchange-simulator's and the payment-gateway's. Not a
slice and not a bounded context, like `config/`: it holds no domain concept and no external schema beyond the
envelope both simulators share. Each bounded context that consumes simulator events does so in its own translation
slice under `<bc>/external_systems/<system>/`, with its own webhook subscription, endpoint, `Inbox` and validator,
and uses these classes to do it in a few lines. The design is in `docs/EXPANSION_PLAN.md` (D11) and follows the
simulators' receiver guide ("A correct receiver, in five rules").

| Class | Role |
|---|---|
| `ExternalEventEnvelope` | The canonical envelope. The payload stays a `JsonNode`; only the consuming translation slice interprets it |
| `WebhookSignatureVerifier` | HMAC-SHA256 over `<timestamp>.<raw body>`, either value during a rotation, timestamp against the real clock |
| `SignedWebhookReceiver` | One subscription's receiving side: verify, parse, filter, validate, hand to the intake, answer |
| `ExternalEventIntake` | Deduplicates per `(consumer, eventId)` and queues to the consumer's `Inbox`, in one unit of work |
| `ExternalEventLogCatchUp` | Reads the source's `/api/events` log behind a fenced lock and feeds the same intake |

## Rules that are easy to break

- **Validate before deduplicating.** The simulators' fault injection damages delivered copies (missing, null, invalid
  fields), never the canonical event. A damaged copy that got its `eventId` recorded would swallow the good copy that
  the retry brings. So a slice's validator runs first, and a failing copy is answered `422` and recorded nowhere.
- **The deduplication row and the `Inbox` message commit together**, or a failed enqueue leaves a row that makes the
  retry look like a duplicate.
- **Nothing here reorders.** Subscriptions are unordered (D11), so deliveries arrive out of order. Messages are
  `OrderedMessage`s keyed by the source's `aggregateId` with its `sequence` as the order, which keeps a backlog in
  order, but the queue delivers what is visible: an earlier event arriving later is delivered later. Consumers
  tolerate it — compare the source's sequence, or be idempotent on the source's own ids (`executionId`, `paymentId`).
  Expect the shard-owned engine to count such pairs as `orderViolations`; they are the producer (the network)
  numbering and committing in different orders, not an engine fault.
- **Webhooks are the fast path; the catch-up reader is the guarantee.** Without it, a delivery dead-lettered at the
  source, or an outage longer than its retry span, is lost.
- **`external_inbound_event` grows by one row per event per consumer and is never pruned yet.** At load-test volume
  that is the first table to watch; pruning or partitioning by `received_at` is a phase 6 item.
- **Business time is the source's** (`occurredAt`, D10). The signature timestamp is the exception: it is the sender's
  real clock, checked against ours.
