# Bounded context: shipping

The module's only bounded context. Four slices, one entity, one collection.

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
the Kafka `Outbox` — but they are never appended to a stream and never replayed. If that ever stops
being true, the migration is to the decider or aggregate lane, and it is a real one: there is no history
kept to migrate *from*.

Consequences worth knowing before editing:
- **No `routing/`.** Its job is picking an event stream; the command bus routes by command type.
- **No `use_cases/_shared/`.** There are no evolvers, so there is no fold to promote. A `_shared/` here
  would be a service class in disguise.
- **Reads are strongly consistent** — same collection, same transaction. See `views/order_status`.
- **`slice-check` gate 10 (projection idempotency) does not apply** on this tier.

## Five things not to undo

- **`entities/ShippingOrder` exposes no accessors.** Spring Data maps the fields directly, so nothing
  needs to be public; the one public method carries an invariant. A public `setShipped(boolean)` would
  make `markOrderAsShipped()`'s guard bypassable — the defect an ORM pushes you into on this lane, and
  the one the `postgresql-inbox-outbox` sibling had.
- **`entities/ShippingOrders` extends the bare `Repository` marker, not `MongoRepository`.** That is
  what keeps `findAll`/`deleteAll`/`saveAll` off a write repository whose sanctioned surface is
  load-by-id and save. A finder added here for a screen is the read side served from the write model —
  it belongs on `views/order_status`'s own query interface.
- **Neither `events/` nor `entities/` names a command type.** `ShippingOrderRegistered.from(cmd)` and
  `new ShippingOrder(cmd)` both existed and are both §R4 violations; the emitting slice unpacks its own
  command. `events/` is the BC's importable surface, which makes that the worse of the two.
- **`ShipOrderHandler` calls `save()` explicitly.** Spring Data MongoDB has no dirty checking. Dropping
  that line silently reverts the idempotency guard and duplicates outgoing Kafka events — see
  `use_cases/ship_order/CLAUDE.md`.
- **The Kafka DTOs carry a plain `String` id, not `OrderId`.** Converting happens in the two adapters of
  `external_systems/order_management`, and nowhere else. Typing the DTOs with `OrderId` means the ACL
  stops translating — and it also drags the Essentials value-type serializer onto Kafka's `ObjectMapper`,
  which is what used to break this module under `-Pjackson2`.

## Public surface

`events/` and `types/` are the only packages another bounded context may import. `ShippingEvent` is
`sealed`, so adding a variant means appending to `permits` — the one sanctioned cross-slice edit.
