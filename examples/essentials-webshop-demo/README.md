# Essentials Webshop Demo

A small webshop, event-sourced end to end, built from the event model used in *Module 6 — Simplifying with Event
Modeling, Event Sourcing and CQRS*. It is written in **Kotlin** on
[`kotlin-eventsourcing`](../../components/kotlin-eventsourcing/README.md): every write is a `Decider`, a pure
function from a command and the past events to at most one new event, and every read is a projection built from
those events.

Three bounded contexts — `sales`, `shipping`, `payment` — plus a browser page that composes their read models into
one screen. They share nothing but events and one identifier.

## Running it

Docker is required; PostgreSQL and Kafka are started for you.

```bash
mvn spring-boot:run -pl :essentials-webshop-demo -Dspring-boot.run.profiles=compose
```

| What | Where |
|---|---|
| The shop | <http://localhost:8080/shop/index.html> |
| The Essentials admin console — event streams, subscriptions, queues | <http://localhost:8080/essentials/admin> |
| Health | <http://localhost:8080/actuator/health> |

The `compose` profile is what supplies the datasource and starts the containers. Without it the application has no
database and will not start.

To wipe everything and begin again:

```bash
docker compose -f src/main/resources/compose.yml down -v
```

## What to look at, and in what order

1. **One slice, all four files** — `sales/use_cases/change_product_price/`. A command, a decider, a rejection, an
   HTTP endpoint. The decider has the three outcomes a decision can have: an event, no event (the price is already
   that), or an exception (no such product).
2. **Its tests** — `src/test/.../change_product_price/ChangeProductPriceDeciderTest.kt`. Given/When/Then with no
   database, no Spring context and no mocks, because a decider is a function. The whole unit suite is 30 tests in
   under half a second.
3. **State without an aggregate class** — `sales/use_cases/remove_item_from_shopping_basket/`. The decider needs to
   know what is in the basket, so it folds the basket's own events with an `Evolver`. `request_checkout` folds the
   same events into a total instead: two small folds, each answering one question, neither knowing about the other.
4. **A read model** — `sales/views/products_for_sale/`. A `ViewEventProcessor`, a JPA entity, a repository, a query
   endpoint. The framework delivers events in order, resumes where it left off after a restart, and holds a lock so
   only one instance projects; what it cannot do for you is decide what applying an event twice should mean, which
   is why each handler compares the event's order against the row's version.
5. **The automation pattern** — `payment/automations/hold_funds_on_order_placed/`. `sales` records `OrderPlaced`
   and knows nothing more. `payment` subscribes to the three events it needs, keeps its own work-item row, and when
   that row becomes complete it asks the card network and sends itself a command whose decider records the answer.
   Delete the whole context and `sales` does not change. The row lives in the automation rather than in a view
   slice on purpose — see this module's `CLAUDE.md` for what the split version got wrong.
6. **The same pattern with a person instead of a machine** — `shipping/views/orders_ready_for_packaging/` plus
   `shipping/use_cases/package_order/`. The warehouse screen is a to-do list; a human drains it.
7. **The composite screen** — `sales/views/order_summary/`. One row assembled from four event streams across three
   contexts, so the confirmation page is a single query with no joins and no fan-out of service calls.
8. **The dual write, answered** — `shipping/external_systems/order_management/outgoing/`. Shipping an order writes
   only to the event store, in one local transaction. A subscription then publishes an external event to Kafka, at
   least once, with a resume point so a broker outage does not lose it.

## The flow, as the demo runs it

```
Web app        │ add product ─┐
               │              ▼
sales          │        ProductAdded ──────────────▶ products_for_sale
               │ add to basket ─▶ ItemAddedToShoppingBasket ──▶ shopping_basket
               │ checkout ─────▶ CheckOutRequested (order id + total)
               │ details ──────▶ ShippingDetailsAdded, PaymentDetailsAdded
               │ place ────────▶ OrderPlaced ──────────────────▶ order_summary
               │                      │        │
payment        │                      │        └──▶ automation (own work-item state)
               │                      │                  └──▶ PlaceHoldOnCreditCard
               │                      │                        └──▶ CreditCardHoldPlaced / Rejected
shipping       │                      └──▶ orders_ready_for_packaging (warehouse screen)
               │                                  └──▶ PackageOrder ──▶ OrderPackagingRequested
               │                                        └──▶ ShipOrder ──▶ OrderShipped ──▶ Kafka
```

Everything to the right of an arrow happens on a subscription, which is why the screens are eventually consistent:
after "place order" the payment status and the packaging list arrive a moment later, and the shop page polls rather
than pretending otherwise.

## Trying the unhappy paths

- **Idempotency.** Press *Add product* twice with the same values from the same form, or re-send a price change
  that changes nothing: the second call returns `204`/`200` and appends no event. Every decider here treats "already
  done" as success, never as an error.
- **A declined card.** Put an order above 10,000 through the checkout with `CREDIT_CARD`. The in-memory gateway
  declines it, `CreditCardHoldRejected` is recorded, and the summary shows `REJECTED` — the refusal is a fact in the
  stream, not a log line.
- **An invoice order.** Choose `INVOICE` and no hold is placed at all; the order still reaches the warehouse.
- **Replay.** Reset a projection's subscription from the admin console and watch the read model rebuild itself from
  the events. Nothing is lost, because the events were the record and the table was only a cache of them.
- **The broker down.** Stop the Kafka container and ship an order. The order ships anyway — the write only needed
  the database — and the external event is delivered when the broker returns.

## Tests

```bash
mvn test -pl :essentials-webshop-demo      # 30 decider tests, no Docker, ~0.3s
mvn verify -pl :essentials-webshop-demo    # + 3 integration tests over the whole flow (Docker)
```

`WebshopFlowIT` walks the full path — catalogue, basket, checkout, placement, the automatic hold, packaging,
dispatch — and waits on each read model rather than asserting straight after the command, because asserting a
projection synchronously is asserting a race.

## What this demo is not

It is a teaching example, and a few things are deliberately simpler than production:

- security is wide open (`AllAccessAuthenticatedUser`), so the admin console works without an identity provider;
- the payment gateway is in memory and declines by amount;
- read models are created by Hibernate's `ddl-auto`, not by migrations;
- there is no authentication, no tenancy, and no money type beyond `Amount`.

For the aggregate-based style — `AggregateRoot`, snapshots, closing books — see
[`essentials-trading-demo`](../essentials-trading-demo/README.md). For the same domain without an event store, see
[`postgresql-inbox-outbox`](../essentials-spring-examples/postgresql-inbox-outbox/README.md).
