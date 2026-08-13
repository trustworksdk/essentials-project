# Bounded context: shipping

The module's only bounded context. Four slices, one entity, one table.

| Slice | Kind | Trigger |
|---|---|---|
| `use_cases/register_shipping_order` | command | `POST /shipping/register-order` |
| `use_cases/ship_order` | command | `POST /shipping/ship-order`, **or** `OrderAccepted` over Kafka |
| `views/order_status` | view | `GET /shipping/orders` |
| `external_systems/order_management` | translation | Kafka, both directions |

Each slice directory has its own `CLAUDE.md` and `slice.yaml`. Start there.

## Write style: service-entity (§R5) — a decision, not a default

This BC decides on a **state-stored entity**, `entities/ShippingOrder`, loaded through a repository and
mutated in place inside the command's transaction. `entities/` is the lane marker; there is no
`aggregates/` and no per-slice deciders, and a BC never holds two write styles.

**The decision on the record: this context has no need to reconstruct state from history.** No audit
trail derived from events, no temporal queries, no replay-driven projections. `ShippingEvent` and its
two variants are still declared and still published — on the `EventBus`, as integration facts that feed
the Kafka `Outbox` — but they are never appended to a stream and never replayed. The `postgresql-cqrs`
example in this repo is the *other* choice, on the same domain, so the two sit side by side on purpose.

Consequences worth knowing before editing:
- **No `routing/`.** Its job is picking an event stream; the command bus routes by command type.
- **No `use_cases/_shared/`.** There are no evolvers, so there is no fold to promote.
- **Reads are strongly consistent** — same table, same transaction. See `views/order_status`.
- **`slice-check` gate 10 (projection idempotency) does not apply** on this tier.

## Six things not to undo

- **`ShippingOrder.@Id` is a plain `String`, not an `OrderId`.** Typing it failed on an earlier JPA
  version. `config/converters/OrderIdAttributeConverter` is the machinery for that attempt and is kept
  against a retry — it currently applies to no field in this module. The `mongodb-inbox-outbox` sibling
  *does* use `@Id OrderId`; the divergence is deliberate, and it is why `OrderStatusView.getId()`
  returns `String` here and `OrderId` there.
- **`entities/ShippingOrder` exposes no accessors, and `@Access(AccessType.FIELD)` makes that
  structural.** It used to carry a full get/set pair per field, and `setShipped(boolean)` among them
  made `markOrderAsShipped()`'s guard trivially bypassable — the defect an ORM pushes you into on this
  lane. JPA reads and writes the fields directly; nothing needs to be public.
- **The entity defensive-copies `ShippingDestinationAddress`.** JPA requires an `@Embeddable` to be a
  mutable class with a no-arg constructor, so unlike the MongoDB sibling's record it cannot be
  immutable. Storing the instance by reference would leave a command and a long-lived row sharing
  state. Its fields are `private` for the same reason, and the copy helper is a **static factory**
  rather than a second constructor, which Jackson 3 would treat as another implicit creator.
- **`entities/ShippingOrders` extends the bare `Repository` marker, not `JpaRepository`.** That keeps
  `findAll`/`deleteAll`/`saveAll` off a write repository whose sanctioned surface is load-by-id and
  save. It previously carried `findByShipped`, `findByIdIn` and `findAllOrderIds`: the first is now
  `views/order_status`'s, the second had no callers at all, the third moved to the load harness.
- **The Kafka DTOs carry a plain `String` id, not `OrderId`.** Converting happens in the two adapters of
  `external_systems/order_management`, and nowhere else. Typing the DTOs with `OrderId` means the ACL
  stops translating — and it also drags the Essentials value-type serializer onto Kafka's `ObjectMapper`,
  which is what used to break this module under `-Pjackson2`.
- **Neither `events/` nor `entities/` names a command type.** `ShippingOrderRegistered.from(cmd)` and
  `new ShippingOrder(cmd)` both existed and are both §R4 violations; the emitting slice unpacks its own
  command. `events/` is the BC's importable surface, which makes that the worse of the two.

## The load-test harness is not here

`LoadOrderShippingProcessor`, `RecreateShippingOrderView(s)` and `LoadTestShippingOrders` live in
`src/test/java/.../shipping/load/`. They used to be `src/main` `@Service`s, which meant the running demo
application started a 20-consumer `load-test` Inbox at boot and the production write repository carried
a query only they used. They are not a domain slice under any reading, so they are test scope now.

## Public surface

`events/` and `types/` are the only packages another bounded context may import. `ShippingEvent` is
`sealed`, so adding a variant means appending to `permits` — the one sanctioned cross-slice edit.
