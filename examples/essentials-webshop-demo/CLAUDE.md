# essentials-webshop-demo

Kotlin Spring Boot demo of Module 6's event model (`docs/presentation/Module 6 - …pptx`), built on
`components/kotlin-eventsourcing`. Only module exercising that component. Source of code panels in
`presentation/module6/`. Not part of release.

```bash
mvn verify -pl :essentials-webshop-demo                  # 30 unit + 3 ITs (Docker)
mvn -Pjackson2 verify -pl :essentials-webshop-demo -am   # other flavour; -am required
mvn spring-boot:run -pl :essentials-webshop-demo -Dspring-boot.run.profiles=compose
#   shop: /shop/index.html   admin: /essentials/admin
```

`compose` profile supplies datasource + starts PostgreSQL and Kafka. Without it: no datasource, context fails.

## Bounded contexts

| BC | Aggregate types | Slices |
|---|---|---|
| `sales` | `Products`, `ShoppingBaskets`, `Orders` | 8 command, 4 view |
| `shipping` | `ShippingOrders` | 2 command, 1 view, 1 outgoing integration |
| `payment` | `CreditCardHolds` | 1 command, 1 automation (owns state), 1 gateway port |

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
- **`Amount` column needs explicit `@Convert`** — `AmountAttributeConverter` is `autoApply=true` but sits in
  `types-springdata-jpa` jar, outside scanned packages. Converts via **double**: `1999.50` → `1999.5`. Compare
  money with `compareTo`, never `equals`.
- **No field named `log` or `commandBus` in a processor subclass** — both exist on framework base classes; Kotlin
  property hides Java field. `log` = compile error (KT-56386), `commandBus` = warning. Use `logger`,
  `paymentCommandBus`.
- **Payment automation owns its state — keep it that way.** `OrderAwaitingHold` lives in the automation slice,
  written and read back in the same handler + transaction. Earlier split (view slice + policy on its own
  subscription) worked most of the time: two subscriptions have no relative order, policy ran before row existed,
  leaned on redelivery. Under `-Pjackson2` retries ran out → dead letter → order never charged. Every handler ends
  by re-asking "row complete now?", so whichever event lands last triggers authorization.
- **Dead letter is silent** — logged ERROR, nothing fails, business outcome never happens. `CentralizedMessageFetcher`
  also treats `IllegalArgumentException` anywhere in cause chain as *permanent* → no retries. So Kotlin `require(...)`
  in a message handler = dead letter on first delivery. Deciders may use `require`; handlers must not.
- **`@Testcontainers`/`@Container` broken from Kotlin `companion object`** (Testcontainers 2.0.5). `@Container` and
  `@field:Container` alike: container stopped after first test method, rest of class hits destroyed DB
  (`FATAL: terminating connection due to unexpected postmaster exit`). `WebshopFlowIT` starts it in companion init;
  Ryuk still cleans up.
- **Awaitility retries only `AssertionError`** — `orElseThrow()` inside `untilAsserted` aborts the wait. Assert
  `isPresent` first.
- **Own Postgres volume + `wal_level=logical`** in `src/main/resources/compose.yml`. Sibling demos share the image;
  data dir holding trading demo's replication slot won't start without it. Port 5432 shared — run one demo at a time.
- **`OrderPlaced` carries only the order id.** Total on `CheckOutRequested`, address on `ShippingDetailsAdded`,
  method on `PaymentDetailsAdded`. Don't copy them onto it — second competing copy of permanent facts.
- **`ItemRemovedFromShoppingBasket` carries removed unit's price.** Without it, basket view and checkout total each
  guess which unit left, both wrong when two units went in at different prices.
- **Decider sees only its own stream.** `PackageOrderDecider` can't check order was placed — that's `sales`' stream.
  To-do view is the guard. Don't inject another context's write side.

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
payment   [CheckOutRequested + PaymentDetailsAdded + OrderPlaced] → automation's own state → gateway
          PlaceHoldOnCreditCard     → CreditCardHoldPlaced | CreditCardHoldRejected
shipping  [OrderPlaced]             → orders_ready_for_packaging (to-do view, human trigger)
          PackageOrder              → OrderPackagingRequested
          ShipOrder                 → OrderShipped               → Kafka: ExternalOrderShipped
```
