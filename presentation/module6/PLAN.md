# Presentation Plan — Module 6, rebuilt on Essentials

**Source material:** `docs/presentation/Module 6 - Simplifying with Event Modeling, Event Sourcing, and CQRS.pptx`
(88 slides, training-module pacing, Kotlin snippets).

**Audience:** colleagues and course attendees, mixed experience, mostly new to event sourcing.

**Slot:** 45 minutes — 36 minutes of content across 32 slides (14 concept/answer pairs), then questions.

**Relationship to the source deck:** this is not a one-to-one port. The pptx teaches the concepts; this deck
follows its structure and shows, concept by concept, how Trustworks Essentials answers it in running code. Every
code panel is a trimmed excerpt of real code from `examples/essentials-webshop-demo` — licence header and KDoc
removed to fit a slide, nothing else changed, and nothing invented.

**Deliverables, as built:**

| Path | What it is |
|---|---|
| `presentation/module6/deck.html` | Bilingual (EN/DA) HTML deck, 32 slides, needs `images/` beside it |
| `presentation/module6/images/` | Six diagrams extracted from the module's own pptx |
| `presentation/module6/NOTES.md`, `NOTES.da.md` | Speaker notes, run of show, expected questions |
| `presentation/module6/demo-script.md` | Live-demo runbook with exact commands and fallbacks |
| `examples/essentials-webshop-demo/` | The pptx's sales system, implemented as an Essentials application |

The earlier deck ("The Event Is The Record", now `presentation/instrument/`) is left untouched. This deck
reuses its technical shell — 16:9 container-query canvas, `data-lang` EN/DA spans, `data-note-en`/`data-note-da`
speaker notes, `data-act`/`data-min` budgets, handout palette, the `.panel` / `.gloss` / `.lane` component
vocabulary — so the two decks look like one family and the keyboard controls behave identically.

## Status — built

All of it. 32 slides in 14 concept/answer pairs, 36 minutes of budget, bilingual; the application boots,
passes 30 unit tests and 3 integration tests under **both** Jackson flavours, and has been driven end to
end against real PostgreSQL and Kafka.

Five things came out differently from the plan, and each is recorded where it matters:

| Planned | Built | Why |
|---|---|---|
| `examples/essentials-sales-demo` | `examples/essentials-webshop-demo` | The package would have read `examples.sales.sales.types`. The app is a webshop; `sales` is one context inside it |
| `slice.yaml` per slice, per-slice `CLAUDE.md` | Neither | Cut on request, to keep the module simple. The directory layout still follows the slice law, and the module `CLAUDE.md` says so |
| `payment` = 1 command + 1 view + 1 automation | 1 command + 1 automation that owns its state + 1 gateway port | The split version raced its own projection and dead-lettered under `-Pjackson2`. See below |
| Kotlin semantic types per `code-style.md` | Java-style `CharSequenceType` subclasses | A Kotlin `value class` needs `jackson-module-kotlin` on the framework's persistence mapper, which a Jackson 3 application cannot add without replacing the serializer bean. Deviation agreed explicitly and documented in the module `CLAUDE.md` |
| Snapshots / closing books mentioned in Act 3 | Named on the "left out on purpose" slide | The trading demo already shows them properly, and they are not in this event model |
| Six acts of our own narrative | Fourteen concept/answer pairs following the module's own sequence | The first build retold the module in different words, which spends the hour on the half the room already knows. Rebuilt on request |
| Live demo as Act 4 | Cut; `demo-script.md` kept as a standalone runbook | Fourteen pairs fill 36 minutes. A real loss, and the trade the slot forces |

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
4. **Money in a read model was stored as a floating-point double.** `AmountAttributeConverter` extends a base
   declared `AttributeConverter<T, Double>`, so `1999.50` came back as `1999.5` — and, worse, `sum()` over a
   money column was float arithmetic. The demo now uses its own `MoneyAttributeConverter` mapping to `numeric`,
   and `WebshopFlowIT` asserts exact scale so a revert fails. The framework-side fix is written up in
   `docs/bigdecimal-attribute-converter-improvements.md`. Separately, and unrelated to JPA:
   `BigDecimal.equals` is scale-sensitive, so incoming money is compared with `compareTo` in the decider.

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

## Deck structure — rebuilt

The first build of this deck compressed the module into a six-act narrative of its own. That was the wrong
shape: the room has already seen Module 6, so a deck that retells it in different words spends its time on
the half they know. **Rebuilt as fourteen concept/answer pairs**, which is the structure the brief actually
asked for — introduce each concept as the module teaches it, then show the Essentials code that implements
it.

32 slides, 36 minutes. Act and minute budgets live in `data-act` and `data-min` on each slide and the
on-screen timer reads them, so this table and the deck cannot drift.

| Slides | Content | Min |
|---|---|---|
| 1–2 | Title, and the roadmap: four questions in the order you hit them | 0.5 |
| 3–4 | **1** An event is a fact → sealed family, `events/` as contract | 2.5 |
| 5–6 | **2** Discovering and modeling → one slice = the model's four boxes | 2.75 |
| 7–8 | **3** The three patterns → three directory names, three base types | 2.5 |
| 9–10 | **4** Slices and capabilities → three lanes as directories | 2.5 |
| 11–12 | **5** Tests come from the model → `GivenWhenThenScenario` | 2.25 |
| 13–14 | **6** Command + state = event → the formula is the signature | 2.75 |
| 15–16 | **7** The decider → one bean per aggregate type | 2.25 |
| 17–18 | **8** Event store and replay → two orderings, neither a clock | 2.5 |
| 19–20 | **9** State inside a decision → `Evolver.applyEvents` | 2.25 |
| 21–22 | **10** Why view projections → a processor and a table | 2.25 |
| 23–24 | **11** Order, delivery, idempotence → two theirs, one yours | 2.25 |
| 25–26 | **12** CQRS and stale data → the query never touches the domain | 2.5 |
| 27–28 | **13** Composite UI and automations → one row, four streams | 2.75 |
| 29–30 | **Bonus** The dual write → one local transaction, then publish | 2.5 |
| 31–32 | Left out on purpose, and the close | 1 |

### Two structural consequences

**A grey/orange rhythm rather than acts.** `data-side="concept"` slides carry the module's own words and
diagrams with a muted eyebrow; `data-side="answer"` slides carry code with the accent colour. The rail
reads `n/13`. The pairing is not explained beyond one line on the roadmap: the first grey-then-orange
transition teaches it, and a slide spent describing the slide format is a slide wasted. An earlier version of
slide 2 did exactly that — it described grey and orange slides before the room had seen either, next to an
unlabelled table of thirteen numbers — and it was replaced with a roadmap grouping the concepts under the
four questions they answer.

**No live demo.** Fourteen pairs fill 36 minutes. The close tells the room how to run the app themselves,
and `demo-script.md` remains the runbook for a longer slot. That is a real loss — watching the order
summary fill in field by field is the one thing a slide cannot show — and it is the trade the 45-minute
slot forces.

### Visuals: what could and could not be reused

The concept slides use the module's own artwork, extracted from the pptx into `images/` (see
`images/README.md`). Six files were usable:

| Used | What |
|---|---|
| `event-model-legend.jpg` | a complete event model with the full legend — carries pairs 2, 3 and 5 |
| `wireframe-products/basket/checkout.png` | the module's Web App lane, three screens |
| `composite-ui.png` | the order confirmation, colour-boxed per view — the best slide in the source deck |
| `dual-write.png` | the module's own hand-drawn EventStore → Outbox → Kafka diagram |

**What could not be extracted:** the swimlane timelines on slides 18, 22 and 70–72 — the webshop event
model itself — are drawn with PowerPoint shapes, not embedded images, so only the wireframes inside them
came out. No renderer is available in this container to rasterise the slides. Those timelines are
therefore redrawn as inline SVG or restated as `.chain` node lists, using the module's own event names.

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
  saying on pair 7's answer slide rather than discovering mid-demo.
- **Compression loss.** Seventeen CQRS slides become one pair, and the latency arithmetic survives only as a
  bullet on its concept slide. Everything cut is named on the "left out on purpose" slide and in the notes, so a
  question can be answered from them rather than deflected.
- **Demo surface.** PostgreSQL, Kafka and a projection that is eventually consistent, all in a 3-minute segment.
  The runbook needs a scripted fallback per step, and the shop page must await projections rather than assume.
- **Build hygiene.** Apache licence headers on every new file, and the OWASP dependency check runs over examples.
