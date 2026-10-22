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
| `SimulatorProperties`, `SimulatorIntegrationConfiguration` | `trading-demo.simulators.*` (set by the `simulators` profile), the shared `WebhookSignatureVerifier` bean, which exists only when a secret file is configured, and the exchange's generated `ApiClient`, which exists only when its base URL is |
| `BusinessCalendar` | The business days as the exchange closes them: table `business_day_closed`, fed by `BusinessDayClosed` (subscription `demo-business-calendar` + catch-up), announced in process as `BusinessDayClosedNotice`; `latestClosedBusinessDate()`. Reference data, not a context's state; the store D24 plans for EF-1. Simulators profile only |
| `exchange_simulator/client` (generated) | The exchange-simulator's client, generated at build time from `src/main/openapi/exchange-simulator/openapi-3.0.json` (Spring `RestClient`, Jackson 3). Contract only - adapters in each context's `external_systems/` use it, and no generated type leaves them |

**The simulators' OpenAPI documents are copied into `src/main/openapi`**, so the build needs nothing outside this
repository (CI cannot reach the simulators' artifact). They come from `dk.trustworks.simulators:simulator-api-specs`,
at the version in the POM's `simulator-api-specs.version`. To refresh: raise it, run
`mvn -pl examples/essentials-trading-demo initialize -P refresh-simulator-specs` with the artifact in the local
repository, and commit the result. Never edit the copies by hand.

**Generated request models send every field, set or not**: unset lists as `[]` (a `WebhookRequest` without
`participants` - `400`, "1 to 100 participant IDs") and unset optional scalars as `null` (`ClockAdvance` with only
`days` - `400`, "hours: invalid type: null"). The simulators treat an absent field and a `null` one differently. Set
every field of a request model you build.

Tests: `ExternalEventIntakeIT` runs against a stub source and always runs. `ExchangeSimulatorWebhookIT` runs against
the real `exchange-simulator:dev` image with its fault injection on (duplicates, missing fields, out-of-order), and
**skips itself when the image is not built locally** (`SimulatorImages`) — a skip there is not a pass. Its webhook
endpoint is created and `exposeHostPorts`-ed in a static block *before* the container fields: Testcontainers makes a
host port reachable only from containers started after that call.

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
