# essentials-webshop-demo

Kotlin Spring Boot demo of Module 6's event model (`docs/presentation/Module 6 - …pptx`), built on
`components/kotlin-eventsourcing`. Only module exercising that component. Source of code panels in
`presentation/module6/`. Not part of release.

```bash
mvn verify -pl :essentials-webshop-demo                  # 34 unit + 3 ITs (Docker)
mvn -Pjackson2 verify -pl :essentials-webshop-demo -am   # other flavour; -am required
mvn spring-boot:run -pl :essentials-webshop-demo -Dspring-boot.run.profiles=compose
#   shop: /shop/index.html   admin: /essentials/admin
mvn spring-boot:run -pl :essentials-webshop-demo -Dspring-boot.run.profiles=compose,compose-fresh
#   same, but throws the data away on stop (down -v) — before a talk, or when old state confuses a run
./run-demo.sh            # same as the plain run; Ctrl-C stops the containers and KEEPS the data
./run-demo.sh --fresh    # Ctrl-C removes containers and volume
./run-demo.sh --wipe     # remove containers and volume now, without starting
```

**Ctrl-C does not `down` the stack.** Boot's compose lifecycle runs its *stop* command on graceful shutdown, and
that defaults to `stop`: containers go to `Exited`, the volume and its data stay. Verified both ways — default
profile leaves `Exited` containers and the volume; `compose-fresh` leaves neither. A `kill -9` skips the JVM
shutdown hook and therefore skips both, which is what `--wipe` is for.

`compose` profile supplies datasource + starts PostgreSQL and Kafka. Without it: no datasource, context fails.

**ITs do not use the compose stack** — `WebshopFlowIT` starts its own Testcontainers PostgreSQL and already gets
an empty database every run. `down -v` changes nothing for `mvn verify`; it is only for the demo's own volume,
which persists across runs on purpose.

`docs/ui-flow.md` — which command each shop-page button sends, and which projection feeds each panel (Mermaid + table).

## Bounded contexts

| BC | Aggregate types | Slices |
|---|---|---|
| `sales` | `Products`, `ShoppingBaskets`, `Orders` | 9 command, 4 view |
| `shipping` | `ShippingOrders` | 2 command, 1 view, 1 outgoing integration |
| `payment` | `CreditCardHolds` | 3 command, 3 automation (own state), 1 view, 1 gateway port, 1 incoming integration |

All on **decider write style** — no `AggregateRoot`, no repository. `Decider` = pure `(command, events) -> event?`;
`DeciderAndAggregateTypeConfigurator` (one bean, `config/`) binds every decider bean to command bus. Don't convert
to aggregates — trading demo is the aggregate-style example, this is its counterpart.

No `slice.yaml` manifests here by choice. Layout still follows slice law: `<bc>/use_cases|views|automations|
external_systems/<slice>/`, plus `events/ types/ routing/ config/` as context's shared surface.

## Gotchas

- **`-java-parameters` in POM load-bearing.** Kotlin emits no Java `MethodParameters` by default →
  `Parameter#getName()` = `arg0`. Jackson 3 binds properties-creator by param name → every command, event and
  request body deserializes all-null. Surfaces as `Parameter specified as non-null is null: … parameter id`, web
  layer *and* event-store reads. `jackson-module-kotlin` not needed; flag is whole fix.
- **Semantic types subclass Java `CharSequenceType`** — deviates from `.claude/rules/code-style.md` (Kotlin should
  implement `*ValueType<SELF>`). Reason: Kotlin `value class` needs `jackson-module-kotlin` on the *persistence*
  mapper to write a scalar, and under Jackson 3 the starter ignores `Module` beans by design — app would need own
  `JSONEventSerializer` + per-flavour source sets. `CharSequenceType` needs none.
- **Async capture, webhook, idempotency, reconciliation: `docs/payment-async-capture.md`.** Read it before
  touching `payment` — it carries the reasoning for the five rules below and the failure cases they exist for.
- **A new automation must not act on history** — both payment policies override
  `isStartSubscriptionFromLatestEvent() = true`. The default (start of stream) replayed history on first run and
  charged nine already-shipped orders. Projections keep the default; replaying a row is free, replaying a charge
  is not.
- **Authorize at placement, capture at packing.** Dispatch gates on `paymentSettled`, so a settlement refused
  after packing blocks the parcel instead of needing an un-shipment.
- **The idempotency key is derived (`IdempotencyKey.forOrderCapture`), and the request is recorded as an event
  before the gateway is called.** A key minted per retry is not an idempotency key; a charge made before the
  request was recorded cannot be reconciled.
- **A webhook outcome arrives twice, early, or never** — idempotent decider; retryable
  `CaptureNotYetRequestedException` (never `require(...)`, which dead-letters instantly); and
  `captures_awaiting_outcome` + `CaptureReconciler`, the only clock-triggered automation here.
- **Webhook endpoint stores to an `Inbox` and returns 202; it never does the work.** `@Transactional` so ACK and
  store commit together, `SingleGlobalConsumer`, signature checked — the URL is public and moves money.
- **`RestClient.Builder` is not a bean here** (Boot 4 split its auto-configuration out); the gateway simulator
  uses `RestClient.create()`. Gateway dials are in `WebshopPaymentProperties`; ITs need `RANDOM_PORT` because
  the callback is a real HTTP call to our own port.
- **Events here carry no causation** — `caused_by_event_id` and `correlation_id` are null on every row, because
  the starter's default `PersistableEventMapper` sets neither (its javadoc says otherwise). So "which event
  caused this one?" cannot be answered from the data, only inferred from the model. Framework proposal:
  `docs/event-causation-and-correlation.md`.
- **`OrderSummaryView.lastUpdated` is display-only.** It exists so the order-history panel can put the row you
  just touched at the top, and nothing decides anything from it — event ordering is `EventOrder` /
  `GlobalEventOrder`, never a timestamp. The history panel is the same read model as the summary queried without
  an id (`GET /api/orders?page&size`), not a second view slice — paged in SQL, `size` clamped to
  `OrderSummaryAPI.MAX_PAGE_SIZE`, and returning the slice's own DTO rather than a Spring Data `Page` (whose
  `content`/`pageable`/`sort` envelope is not a contract this slice wants to keep).
- **Server log is the demo's second screen — keep it that way.** The app's own classes log outcomes only: a
  command refused, a hold authorized or declined, an order blocked for packing or cancelled, an event published
  to Kafka. Six lines for a full run. `application.yml` pins
  `org.apache.kafka.common.config.AbstractConfig: WARN` (creating the producer otherwise dumps ~100 lines of
  config at the worst moment) and documents `dk.trustworks.essentials.examples.webshop: DEBUG` for the
  automation's own "is this work item complete?" reasoning. Don't log per-event in projections.
- **A deleted slice keeps running out of `target/classes`.** `payment/views/orders_awaiting_hold_on_credit_card/`
  is gone from source but its `.class` files survive any build that is not `clean` — Spring component-scans
  them, the projection resubscribes, `ddl-auto` recreates `orders_awaiting_hold_view`, and it races the
  automation on the same row. Surfaces as `duplicate key … orders_awaiting_hold_view_pkey` plus
  `[OrdersAwaitingHoldProjection:Orders-Orders] Skipping … event because of error` from a class that no longer
  exists. Fix: `mvn clean`, then delete the orphaned `durable_subscriptions` rows and the table.
- **A refused command must reach the browser as a 409, not a 500.** Domain refusals implement
  `config/DomainRefusal`; `DomainRefusalExceptionHandler` walks the `UnitOfWorkException` cause chain (the
  command bus wraps whatever the decider threw) and answers 409 + reason. Anything unmarked stays a 500, which
  is the point — a refusal is an answer, a defect is not. New refusal exception ⇒ add the marker or the shop
  page can only say "HTTP 500".
- **`shop/index.html` must never announce an event it did not get.** Every button checks `res.ok` and calls
  `refused(...)`; logging the event name unconditionally is how "add to basket" on a checked-out basket printed
  `ItemAddedToShoppingBasket` twice while the (correct, empty) basket panel looked broken instead.
- **Not every endpoint answers JSON** — `POST /api/products` replies `text/plain` with the product id. The
  page's `api()` therefore parses by content-type; a blind `JSON.parse` throws inside the helper, rejects the
  caller's promise before it can log or refresh, and the button silently does nothing.
- **A checked-out basket is closed, and its view empties** — `RequestCheckOut` is the basket's last event, so
  further adds are refused and `shopping_basket_view` has no rows. Empty basket panel after checkout is correct,
  not a projection bug; "New basket" is the way on.
- **Money loses its scale in the browser, not in the stack.** Column is `numeric(19,2)`, event JSON says
  `1999.50`, response body says `1999.50` — then `JSON.parse` makes it a binary double and it renders `1999.5`.
  `shop/index.html` formats every money field through its own `money()` helper and posts prices as strings. Don't
  "fix" the converter for this.
- **Money uses `config/MoneyAttributeConverter.kt`, not the framework's converter.**
  `AmountAttributeConverter` extends a base declared `AttributeConverter<T, Double>` → `double precision`
  column, `1999.50` back as `1999.5`, `sum()` in float. Ours maps to `numeric`; every money field names it plus
  `@Column(precision = 19, scale = 2)`. `autoApply` off on purpose — theirs is `autoApply=true`, two would be
  ambiguous. `WebshopFlowIT` asserts exact scale, so a revert fails the build. Framework fix:
  `docs/bigdecimal-attribute-converter-improvements.md`.
- **`BigDecimal.equals` is scale-sensitive anyway** (`100.00 != 100.0`). Compare *incoming* money with
  `compareTo`, as `ChangeProductPriceDecider` does — that comparison reads event JSON, never JPA.
- **No field named `log` or `commandBus` in a processor subclass** — both exist on framework base classes; Kotlin
  property hides Java field. `log` = compile error (KT-56386), `commandBus` = warning. Use `logger`,
  `paymentCommandBus`.
- **Payment automation owns its state — keep it that way.** `OrderAwaitingHold` lives in the automation slice,
  written and read back in the same handler + transaction. Earlier split (view slice + policy on its own
  subscription) worked most of the time: two subscriptions have no relative order, policy ran before row existed,
  leaned on redelivery. Under `-Pjackson2` retries ran out → dead letter → order never charged. Every handler ends
  by re-asking "row complete now?", so whichever event lands last triggers authorization.
- **Dead letter is silent** — one ERROR line, a dead-letter row, a queue count. Nothing throws (no caller is
  waiting), no test fails, health stays green, business outcome never happens.
- **`IllegalArgumentException` = permanent, zero retries** — `CentralizedMessageFetcher.isPermanentError` (and
  `DefaultDurableQueueConsumer`'s identical copy) tests the outermost exception and the *deepest* root cause
  against a hard-coded list, OR-ed after your `RedeliveryPolicy`, so `alwaysRetry()` cannot opt out. Kotlin
  `require(...)` throws it — and so do `FailFast.requireNonNull`/`requireTrue`, this repo's guard idiom. Anything
  reachable from a `@MessageHandler`, deciders sent via the command bus included, therefore dead-letters on first
  delivery. `check(...)`/`IllegalStateException` retries. Use `IllegalArgumentException` only for "this message can
  never be processed"; if the condition may become true later, throw something else, as
  `WorkItemNotReadyException` does.
- **`@Testcontainers`/`@Container` broken from Kotlin `companion object`** (Testcontainers 2.0.5). `@Container` and
  `@field:Container` alike: container stopped after first test method, rest of class hits destroyed DB
  (`FATAL: terminating connection due to unexpected postmaster exit`). `WebshopFlowIT` starts it in companion init;
  Ryuk still cleans up.
- **Awaitility retries only `AssertionError`** — `orElseThrow()` inside `untilAsserted` aborts the wait. Assert
  `isPresent` first.
- **Compose project is named in `compose.yml` (`name: essentials-webshop-demo`), and it has to be.** Boot starts
  compose from `classpath:compose.yml` = `target/classes/compose.yml`, so without the key the project is named
  after that directory — `classes` — and `docker compose -f src/main/resources/compose.yml down -v` resolves to
  a *different* project (`resources`), appearing to work while wiping nothing.
- **Own Postgres volume + `wal_level=logical`** in `src/main/resources/compose.yml`. Sibling demos share the image;
  data dir holding trading demo's replication slot won't start without it. Port 5432 shared — run one demo at a time.
- **`OrderPlaced` carries only the order id.** Total on `CheckOutRequested`, address on `ShippingDetailsAdded`,
  method on `PaymentDetailsAdded`. Don't copy them onto it — second competing copy of permanent facts.
- **`ItemRemovedFromShoppingBasket` carries removed unit's price.** Without it, basket view and checkout total each
  guess which unit left, both wrong when two units went in at different prices.
- **Decider sees only its own stream.** `PackageOrderDecider` can't check order was placed — that's `sales`'
  stream — and can't check the card cleared, which is `payment`'s. To-do view is the guard for both: it projects
  `CreditCardHoldRejected` onto the row, the API derives `BLOCKED`, and the screen offers no button. Same shape in
  `CancelOrderDecider`, which cannot verify the decline it is cancelling for. Don't inject another context's write
  side; put the cross-context guard in a read model, where being a moment stale is acceptable.
- **A work-item row spans all of its steps, not just the first.** `orders_ready_for_packaging_view` used to be
  deleted on `OrderPackagingRequested`, which took the row's Ship button with it — `ShipOrderDecider` demands
  packaging first, so `OrderShipped` and the Kafka publication were unreachable from the UI and only ITs sending
  commands directly ever got there. Rows now carry `packaged` and leave on `OrderShipped` or `OrderCancelled`.
- **A new non-null column needs `@ColumnDefault`, or `ddl-auto: update` silently skips it.** `alter table … add
  column x boolean not null` is refused by PostgreSQL on a table that already has rows; Hibernate logs one
  `GenerationTarget encountered exception` WARN and starts anyway, so the column is missing and every read of that
  view 500s. Tests cannot catch it — they always build the schema from nothing — so it only appears on a developer's
  or demo's existing database. `OrderSummaryView.cancelled` and `OrderReadyForPackagingView.packaged` carry
  `@ColumnDefault("false")` for this reason.

## Event model, in order

```
catalog   AddProduct                → ProductAdded               → products_for_sale (view)
          ChangeProductPrice        → ProductPriceChanged
basket    AddItemToShoppingBasket   → ItemAddedToShoppingBasket   → shopping_basket (view)
          RemoveItemFromBasket      → ItemRemovedFromBasket
          RequestCheckOut           → CheckOutRequested (mints OrderId, folds total)
order     AddShippingDetailsToOrder → ShippingDetailsAdded
          AddPaymentDetailsToOrder  → PaymentDetailsAdded
          PlaceOrder                → OrderPlaced                → order_summary (view, 4 streams)
          CancelOrder               → OrderCancelled (carries reason; offered when payment is REJECTED)
payment   [CheckOutRequested + PaymentDetailsAdded + OrderPlaced] → automation's own state → gateway (sync)
          PlaceHoldOnCreditCard     → CreditCardHoldPlaced | CreditCardHoldRejected
          [CreditCardHoldPlaced + OrderPackagingRequested] → automation's own state → gateway (async, 202)
          RequestFundsCapture       → FundsCaptureRequested      → captures_awaiting_outcome (to-do view)
          webhook → Inbox → RecordCaptureOutcome
                                    → FundsCaptured | FundsCaptureFailed
          [no answer for N seconds]  → CaptureReconciler asks the gateway (clock-triggered, not event-triggered)
shipping  [OrderPlaced + PaymentDetailsAdded + CreditCardHoldRejected + FundsCaptured/Failed + OrderCancelled]
                                    → orders_ready_for_packaging (to-do view, human trigger)
          PackageOrder              → OrderPackagingRequested    (row AWAITING_PAYMENT until captured)
          ShipOrder                 → OrderShipped               → Kafka: ExternalOrderShipped
```
