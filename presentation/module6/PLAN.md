# Presentation Plan — Module 6, rebuilt on Essentials

**Source material:** `docs/presentation/Module 6 - Simplifying with Event Modeling, Event Sourcing, and CQRS.pptx`
(88 slides, training-module pacing, Kotlin snippets).

**Audience:** colleagues and course attendees, mixed experience, mostly new to event sourcing.

**Slot:** 45 minutes — 36 minutes of content across 28 slides, then 8–9 minutes of questions.

**Relationship to the source deck:** this is not a one-to-one port. The pptx teaches the concepts; this deck
follows its structure and shows, concept by concept, how Trustworks Essentials answers it in running code. Every
code panel is a trimmed excerpt of real code from `examples/essentials-webshop-demo` — licence header and KDoc
removed to fit a slide, nothing else changed, and nothing invented.

**Deliverables, as built:**

| Path | What it is |
|---|---|
| `presentation/module6/deck.html` | One self-contained bilingual (EN/DA) HTML deck, 28 slides |
| `presentation/module6/NOTES.md`, `NOTES.da.md` | Speaker notes, run of show, expected questions |
| `presentation/module6/demo-script.md` | Live-demo runbook with exact commands and fallbacks |
| `examples/essentials-webshop-demo/` | The pptx's sales system, implemented as an Essentials application |

The earlier deck ("The Event Is The Record", now `presentation/instrument/`) is left untouched. This deck
reuses its technical shell — 16:9 container-query canvas, `data-lang` EN/DA spans, `data-note-en`/`data-note-da`
speaker notes, `data-act`/`data-min` budgets, handout palette, the `.panel` / `.gloss` / `.lane` component
vocabulary — so the two decks look like one family and the keyboard controls behave identically.

## Status — built

All of it. 28 slides plus 2 appendix slides, 36 minutes of budget, bilingual; the application boots,
passes 30 unit tests and 3 integration tests under **both** Jackson flavours, and has been driven end to
end against real PostgreSQL and Kafka.

Five things came out differently from the plan, and each is recorded where it matters:

| Planned | Built | Why |
|---|---|---|
| `examples/essentials-sales-demo` | `examples/essentials-webshop-demo` | The package would have read `examples.sales.sales.types`. The app is a webshop; `sales` is one context inside it |
| `slice.yaml` per slice, per-slice `CLAUDE.md` | Neither | Cut on request, to keep the module simple. The directory layout still follows the slice law, and the module `CLAUDE.md` says so |
| `payment` = 1 command + 1 view + 1 automation | 1 command + 1 automation that owns its state + 1 gateway port | The split version raced its own projection and dead-lettered under `-Pjackson2`. See below |
| Kotlin semantic types per `code-style.md` | Java-style `CharSequenceType` subclasses | A Kotlin `value class` needs `jackson-module-kotlin` on the framework's persistence mapper, which a Jackson 3 application cannot add without replacing the serializer bean. Deviation agreed explicitly and documented in the module `CLAUDE.md` |
| Snapshots / closing books mentioned in Act 3 | Moved to the limits slide and the runbook's retired segment | The trading demo already shows them properly, and they are not in this event model |

### The four defects found by building it

Worth keeping, because three of them are invisible until they bite and all four are now written down in
`examples/essentials-webshop-demo/CLAUDE.md` — and the first is in the root `CLAUDE.md`, because it
applies to any Kotlin consumer of Essentials.

1. **Kotlin needs `-java-parameters`.** Without it, Kotlin emits no Java parameter-name metadata, Jackson
   3 binds every constructor parameter to `null`, and it surfaces as Kotlin's own
   `Parameter specified as non-null is null: … parameter id` — in the web layer *and* when reading events
   back out of the store. Every request in the first end-to-end run failed on this.
2. **A dead letter is silent.** The payment automation read a sibling projection, lost the race that
   exists on every order, exhausted its retries, and the message was dead-lettered: logged at ERROR,
   nothing failed, and the order simply never got charged. The fix was structural — the policy now owns
   its own work-item state and re-checks completeness on every event — not a longer retry.
3. **`@Container` on a Kotlin `companion object` is not a shared container.** Testcontainers 2.0.5 stops
   it after the first test method, and the rest of the class talks to a destroyed database.
4. **Money through the JPA converter loses scale.** `AmountAttributeConverter` round-trips through a
   `double`, and `BigDecimal.equals` is scale-sensitive, so `1999.50` comes back as `1999.5`. Compare with
   `compareTo` — the decider does, and so does the integration test.

## Decisions taken

| Decision | Choice | Why |
|---|---|---|
| Example-app language | Kotlin, on `components/kotlin-eventsourcing` | The pptx snippets were written against that module — `Decider`, `Evolver`, `GivenWhenThenScenario`, `EventOutOfOrderException` are all its types. No example application in the repository exercises it today. |
| App scope | Full flow, all swimlanes | Slides 18 and 70–75 show Web App / Sales / Shipping / Payment. Anything less leaves the automation and integration acts without live code. |
| Read-model storage | Spring Data JPA | Slides 64–67 are JPA. `types-springdata-jpa` supplies the converters for semantic types, so the deck panels match the source deck exactly. |
| External events | Real Kafka: `compose.yml` broker plus a TestContainers integration test | Matches slides 87–88 and the existing `postgresql-cqrs` `ShippingFlowIT`. The demo can show the external event arriving on the topic. |
| Web-app lane | Vanilla-JS shop page plus an admin-console walkthrough | Makes the composite-UI slide (73) real, and the admin console covers event streams, subscriptions and queues in the same demo. No Node, no build step — repository rule. |
| Deck language | Bilingual EN/DA | Consistent with the existing deck and notes. |

## The example application

`examples/essentials-webshop-demo`, a Spring Boot application on `spring-boot-starter-postgresql-event-store`,
in the root reactor next to `essentials-trading-demo`. Packaged by **vertical slice** under the same law as
`postgresql-cqrs` and `essentials-trading-demo`: no `controllers/`, `services/` or `repositories/` packages. Per-slice
`slice.yaml` manifests and per-slice `CLAUDE.md` files were cut on request; the module `CLAUDE.md` records that the
layout still follows the law.

### Bounded contexts

The pptx's swimlanes map to three backend contexts; the Web App lane is the shop page plus the HTTP endpoints the
slices expose.

| BC | Aggregate streams | Command slices | View slices | Automations / integrations |
|---|---|---|---|---|
| `sales` | `Products`, `ShoppingBaskets`, `Orders` | `add_product`, `change_product_price`, `add_item_to_shopping_basket`, `remove_item_from_shopping_basket`, `request_checkout`, `add_shipping_details_to_order`, `add_payment_details_to_order`, `place_order` | `products_for_sale`, `shopping_basket`, `order_summary` | — |
| `shipping` | `ShippingOrders` | `package_order`, `ship_order` | `orders_ready_for_packaging` (to-do list, human trigger) | `external_systems/order_management/outgoing` (Kafka publisher) |
| `payment` | `CreditCardHolds` | `place_hold_on_credit_card` | — | `automations/hold_funds_on_order_placed` (owns its work-item state), `external_systems/payment_gateway` (in-memory request/response port) |

Sixteen slices. Packaging stayed a **human** trigger rather than an automation, so the deck can show both ends of
the same pattern: `payment` drains its list automatically, the warehouse screen is drained by a person.

### Event catalogue

Taken from the pptx so the deck and the source material agree on names.

- `sales`: `ProductAdded`, `ProductPriceChanged`, `ItemAddedToShoppingBasket`, `ItemRemovedFromShoppingBasket`,
  `CheckOutRequested`, `ShippingDetailsAdded`, `PaymentDetailsAdded`, `OrderPlaced`
- `shipping`: `OrderPackagingRequested`, `OrderShipped`
- `payment`: `CreditCardHoldPlaced`, `CreditCardHoldRejected`
- External, on the Kafka topic: `ExternalOrderShipped`

### Which slides become live code

| Slide(s) | Concept | Where it lives in the app |
|---|---|---|
| 25 | Command and event naming, Kotlin data classes | `sales/events/`, each command slice |
| 26, 34–36 | Decider plus three pure unit tests | `sales/use_cases/change_product_price/` |
| 27–33 | Event store table, `fetchStream`, `appendToStream` | `sales` basket slices, shown against the real `events` table |
| 61, 63–67 | Evolver projection, idempotency, out-of-order handling | `sales/views/products_for_sale/` |
| 64 | JPA view entity and repository | same slice |
| 68–69 | `Evolver.applyEvents` for state inside a decider | `sales/use_cases/remove_item_from_shopping_basket/` |
| 12, 74 | Automation pattern: event → work-item state → command | `payment/automations/hold_funds_on_order_placed/` |
| 72 | To-do list driving a command | `shipping/views/orders_ready_for_packaging/` + `shipping/use_cases/package_order/` |
| 73 | Composite UI | the shop page, composed from three separate view endpoints |
| 75 | IT-Ops integration, request/response in memory | `payment/external_systems/payment_gateway/` |
| 86–88 | Dual write, and publishing to Kafka from a subscription | `shipping/external_systems/order_management/outgoing/` |
| 17–18, 20 | Slices, autonomous capabilities, Given/When/Then from the model | the directory layout itself, plus the unit tests |

The pptx skips the wiring. The deck does not: `AggregateTypeConfiguration`,
`DeciderAndAggregateTypeConfigurator`, the `CommandBus` and the UnitOfWork that the command bus owns are one
slide in Act 2, because that is where "Essentials answers this" is most concrete.

### Tests

- Pure `GivenWhenThenScenario` unit tests per command slice — no database, no Spring. Slides 34–36 are three of
  them, verbatim.
- `WebshopFlowIT`: the end-to-end flow (add product → basket → checkout → details → place order → hold placed →
  packaged → shipped), plus the declined-card and invoice-order paths. One PostgreSQL container, no broker — the
  publisher's send fails and is retried, which is the point of publishing from a subscription.
- Both Jackson flavours: `mvn verify -pl :essentials-webshop-demo` and `mvn -Pjackson2 verify -pl :essentials-webshop-demo -am`.
- 30 unit tests in 0.3 s; the IT class in about 9 s.

## Deck structure

36 minutes of content. Act and minute budgets are carried in `data-act` and `data-min` on each slide, and the
on-screen timer reads them, so the budget in this table and the budget in the deck cannot drift.

| Act | Subject | pptx source | Slides | Min |
|---|---|---|---|---|
| 0 | The hook and the map | 2, 24 | 3 | 2 |
| 1 | From conversation to model | 3–20 | 5 | 7 |
| 2 | Event sourcing: the write side | 24–36, 68–69 | 6 | 9 |
| 3 | View projections: the read side | 38–39, 61–67 | 4 | 5.5 |
| 4 | CQRS, briefly | 40–58 | 3 | 4 |
| 5 | Automations, integrations, dual write | 12, 74–75, 86–88 | 4 | 5.5 |
| 6 | Demo, limits, and how to start | — | 3 | 3 |

### Slide list

The deck itself is the authority — each `<section class="slide">` carries its act and its minute budget, and
`NOTES.md` walks the slides in order with what to say on each. What the built deck changed from the plan drafted
here:

- **Act 0 is 2 minutes, not 2.5.** The hook slide took the extra half minute's worth of content.
- **Act 2 is 9 minutes, not 8.5**, because the decider slide is worth 2.5 on its own.
- **Slide 6 shows the directory tree without `slice.yaml`**, since the manifests were cut.
- **Slide 21 carries the automation's own state**, not a separate to-do view, and its gloss tells the story of
  why — the split version was the one real design defect found while building this.
- **The appendix is two slides, not three.** Workshop mechanics, and the four defects found building the demo.
  The hotel-booking exercise from pptx slides 78–84 was dropped: it is a second model with no code behind it,
  and this deck's argument is that the code is the point.

## Build sequence — completed

| Phase | Work | Done when |
|---|---|---|
| A | Module POM with `kotlin-maven-plugin` and allopen, `compose.yml` (PostgreSQL + Kafka), application config, the three context configurations, web configuration (`EssentialsWebMvcConfigurer` + Jackson modules) | `mvn spring-boot:run -pl :essentials-webshop-demo` boots against Docker |
| B | `sales` context: events, types, all eight command slices, four view slices, unit tests | `mvn test -pl :essentials-webshop-demo` green |
| C | `shipping` and `payment`: slices, both automations, payment-gateway port, Kafka publisher, integration tests | `mvn verify -pl :essentials-webshop-demo -am` green, and again under `-Pjackson2` |
| D | Shop page, README, module and per-slice `CLAUDE.md`, every `slice.yaml`, licence headers | `/essentials:slice-check` clean, `graphify update .` run |
| E | `deck.html`, `NOTES.md`, `NOTES.da.md`, `demo-script.md`, `flow.sh` | Deck opens offline, both languages complete, budgets sum to 36 |
| F | Rehearsal pass: demo run end to end from a cold `docker compose up`, code panels read against the app | Nothing in the deck is stale, demo has a fallback for every step |

## Risks, as they played out

- **Jackson 3 and Kotlin data classes.** Jackson 3 reads constructor parameter names from the bytecode and uses
  any constructor as an implicit properties creator, so for a Kotlin data class the parameter *name* is the JSON
  contract. Every event and every snapshotted state class must name its parameters exactly as the properties they
  populate, and both Jackson profiles must run. This is the single most likely source of a late surprise.
- **Kotlin in the examples reactor is new.** `kotlin-maven-plugin` execution order against
  `spring-boot:repackage`, the allopen plugin for `@Configuration` classes, and compile-scope `kotlin-stdlib` in
  the application because the upstream module declares it `provided`.
- **`kotlin-eventsourcing` is experimental and one decision yields at most one event.** `place_order` must be
  modelled as a single `OrderPlaced` rather than a sequence, which is the right modelling answer anyway but needs
  saying on slide 14 rather than discovering mid-demo.
- **Compression loss.** Seventeen CQRS slides become three. The cut material — CQS code examples, the latency
  arithmetic on slide 56 — should live in the notes so a question can be answered from them.
- **Demo surface.** PostgreSQL, Kafka and a projection that is eventually consistent, all in a 3-minute segment.
  The runbook needs a scripted fallback per step, and the shop page must await projections rather than assume.
- **Build hygiene.** Apache licence headers on every new file, and the OWASP dependency check runs over examples.
