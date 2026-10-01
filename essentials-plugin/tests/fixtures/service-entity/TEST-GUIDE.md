# Test guide — `service-entity`

A **synthetic** Essentials bounded context (`com.example.shipping`) on the service-entity write-style lane
(`rules/slice-design.md` §R5). Never shipped, not a template — to copy something, copy
`references/slice/templates/` instead. `brownfield-layered/` is the `slice-discover` counterpart.

**What makes it on-lane rather than merely entity-shaped:** the Essentials command bus and `EventBus`
are on the classpath and the write path goes through them, `entities/` holds the state-stored entity,
and nothing references an `EventStore`, an `AggregateType` or an `EventOrder` (the pom declares no
event-store artifact). Strip the Essentials dependency and this becomes the `brownfield-layered` case
— *nearest* to the lane, not on it.

It carries nine planted findings and fifteen traps. One planted finding is a **misplaced** write
repository (`persistence/ShippingOrders`, which the law puts in `entities/`): it forces gate 15 to
identify a write repository by type rather than by path, so a path-keyed implementation fails the
fixture loudly instead of passing it silently.

The sources carry no oracle labels: the findings and traps live only here and in `expected.yaml`
(machine-readable, `path:line` + anchor per entry). When the two disagree, fix both.
`uv run --script tests/fixtures/check-expected.py` (from `essentials-plugin/`) checks `expected.yaml` against the tree and that no source carries an oracle label.

Eval: `evals/slice-check-service-entity/`, and change-router's `gate-service-entity` case — graders generated from `expected.yaml`; how to run and read it: `evals/README.md`.

Run:

```
/essentials:slice-check essentials-plugin/tests/fixtures/service-entity
```

Then compare the report with the ground truth below.

Unlike `brownfield-layered/`, this tree **has opted into the law** — four manifests, four per-slice
`CLAUDE.md`s — so `slice-check`'s Blocking / Should-fix / Advisory severities apply in full.

## Lane detection (gate 14)

| Expected | Why it matters |
|---|---|
| `shipping: service-entity` | All three signals present: `entities/`, the Essentials command bus on the write path, and **no** `EventStore`/`AggregateType` anywhere |
| Reported **before** the findings table, one line per BC | Gates 10 and 13 are skipped or reshaped here; without the lane line a reader cannot tell a skipped gate from a passing one |
| Gate 10 named as **skipped**, not silently absent | Silence reads as a pass |
| No `aggregates/`, no deciders → **no** multi-lane Blocking | A single-lane BC must not trip the conflict branch |

## Findings — all nine must appear

| # | Severity | Gate | Where | What |
|---|---|---|---|---|
| 1 | **Blocking** | 8(d) | `events/ShippingOrderRegistered.java` — `from(RegisterShippingOrder)` | Command-type leakage into `events/`, the BC's **importable** surface. The worse of the two leaks: it makes a slice-private wire contract part of every foreign consumer's compile surface |
| 2 | **Blocking** | 8(d) | `entities/ShippingOrder.java` — `ShippingOrder(RegisterShippingOrder cmd)` | Command-type leakage into the consistency boundary |
| 3 | **Should-fix** | 15(b) | `persistence/ShippingOrders.java` — `findByShipped(boolean)` | A finder on the **write** repository whose only caller is a read path. It belongs to `views/order_status` as its own query interface |
| 4 | **Should-fix** | 15(c) | `persistence/ShippingOrders.java` | The write repository does not live in `entities/`, beside the entity it persists (§R5). The fix is a file move. Note `persistence/` is **not** on gate 2's forbidden-layer list — if gate 2 reports this instead, the wrong gate fired |
| 5 | **Should-fix** | 16 | `entities/ShippingOrder.java` — `setShipped(boolean)` | A public setter writing the field `markOrderAsShipped()` guards. The one invariant on this entity is bypassable — the lane-specific defect an ORM pushes you into |
| 6 | **Should-fix** | 16 | `use_cases/register_shipping_order/RegisterShippingOrderAPI.java` | `@RestController` returning the `@Entity` directly |
| 7 | **Should-fix** | 17 | `routing/ShippingCommand.java` | A routing marker on a lane that has no decider to filter and no stream to select. Nothing implements it. `routing/` is decider-lane-only and **required** there — this is the absent-on-the-other-lanes direction of gate 17 |
| 8 | **Blocking** | 18(c) | `views/order_status/OrderStatusQueries.java` — `findById(String)` | A **projection** return type on a method named after a CRUD base method. `SimpleJpaRepository` owns `findById(ID)` and the match is on name + parameter types, so the method is captured by the base, returns `ShippingOrder`, and the caller gets a `ClassCastException`. Fix: rename to `findOrderStatusById` |
| 9 | **Should-fix** | 18(a) | `persistence/ShippingOrders.java` | The write repository extends `JpaRepository` rather than the bare `Repository` marker, so its surface is everything Spring Data offers instead of the sanctioned load/save/delete |

Ordering: the three Blocking findings first. Within severity, either order is acceptable.

**Finding 8 is the one that must be found from the declaration alone.** `findById` here has **no
caller** — no API method, no test. A gate that reasons from call sites will miss it, which is exactly
how this defect survives into production: it is not a wiring error, it does not fail at startup, and
no test that never invokes it will catch it. If finding 8 is absent, gate 18(c) was implemented as a
usage check rather than a declaration check.

**Findings 8 and 9 are in different files on purpose.** 9 is a *capability* defect on the write
repository (wrong base interface, nothing broken yet); 8 is a *correctness* defect on a view slice's
query interface, whose base interface is already correct. A gate 18 that only inspects `extends`
clauses finds 9 and misses 8; one that only inspects method names finds 8 and misses 9.

**Findings 3 and 4 together are the load-bearing pair.** Gate 15 identifies a write repository **by
type** — a mutating Spring Data interface over an entity declared in `entities/` — precisely so that
a misplaced one is still analysed. A path-keyed gate 15 would find no write repository in this
fixture at all and would silently pass findings 3 *and* the whole (a)/(b) branch. If finding 4
appears but finding 3 does not, the identification regressed to path matching.

## Traps — the report must contain NONE of these

This is the half that matters. A gate that fires here is worse than one that misses a finding,
because it is what makes an audit tool get switched off.

| # | Must **not** be reported | Why it is correct |
|---|---|---|
| 1 | `ShippingOrder.getOrderId()` / `getDestination()` as a **query surface** | Their only callers are the ORM and `toString()`. § The entity's own bar distinguishes by **caller**, not by shape — and `@Access(AccessType.FIELD)` is present, making the intent structural |
| 2 | `ShippingOrder`'s `protected` no-arg constructor | Persistence-mandated. Not a "method that only assigns fields" |
| 3 | `ShippingOrders.findByIdIn(...)` as read-side drift | Its only caller is `AutoShipProcessor.shipBatch`, which loads the batch **in order to mutate it**. That is the write path, and the repository doing its job |
| 3b | `views/order_status/OrderStatusQueries` under gate 15(c) | It is a `Repository<ShippingOrder, String>` living outside `entities/` — the exact shape 15(c) looks for, minus the one thing that matters: it exposes no mutation. Gate 15 keys on **mutation**, so the sanctioned read shape is out of scope. If 15(c) fires here it will fire on every view slice on this lane |
| 4 | `AutoShipProcessor`'s import of `use_cases.ship_order.ShipOrder` | The R4 carve-out: the type's only use is constructing a command handed to `commandBus.sendAndDontWait`. §R4 *prescribes* this collaboration. **If gate 8(a) flags it, the carve-out was not implemented and the law contradicts itself** |
| 5 | `views/order_status/OrderStatusQueries` as a layer/repository violation, or under gate 15 | It is a Spring Data `Repository`, and its *shape* is correct: slice-private, read-only, narrow, extending the bare marker, returning the read shape. It is not the BC's write repository. Its one defect is finding 8, a method **name** — do not let that turn the whole file into a structural finding |
| 6 | `OrderStatusAPI`'s three `@GetMapping` methods under gate 6 | A **view** slice is scoped by the read model it owns, not by endpoint count. All three are declared in `slice.yaml` `endpoints` |
| 6b | `byStatus()` as an endpoint **missing** from the manifest, or its manifest entry as an endpoint missing from the **code** | It is `@GetMapping(params = "status")` sharing a route with `list()`, declared as `path: "/api/shipping/order-status?status="`. The query string appears nowhere in the source by construction, so a gate 6 that compares the literal path string fires on every run — on a slice doing exactly what §R2 permits. Split at `?`, match the route, then confirm `status` is bound in that handler (`manifest-guide.md` §3) |
| 6c | `list()`'s `@RequestParam(defaultValue = "false") boolean shipped` as an undeclared endpoint | An optional filter with a default does not select a different mapping, so it is not a discriminator and gets no `endpoints` entry. Only `params = "…"`-style selection does |
| 7 | `_loadtest/` as a slice, or as missing a manifest | `_`-prefixed directories are not slices. It carries the required one-line `CLAUDE.md`, so not even Advisory |
| 8 | Any `OrderedMessage` / `Version` / projection-idempotency finding | Gate 10 does not apply on this lane — there is no versioned read model to double-apply into |
| 9 | `views/order_status`'s empty `projections: []` as drift | Correct on this lane; a view slice here owns a query, not a projector |
| 10 | A `use_cases/_shared/` finding | There is no `_shared/` here. (The *presence* of one would be Should-fix — this fixture does not exercise that branch) |
| 11 | `routing/ShippingCommand` under gate 8(c), gate 2, or gate 14 | It is BC-*internal* here, not imported across a BC boundary (8c); `routing/` is a legal directory name, not a layer (gate 2); and it is **not** a lane signal — gate 14 detects lanes from deciders, `aggregates/`, and `entities/` only. If gate 14 reads `routing/` as decider evidence it will report a bogus multi-lane Blocking, which is the circularity gate 17 is written to avoid |
| 12 | `ShippingOrders.findById(String)` under gate 18(c) | It is a reserved name, but it returns the **entity** — precisely what the base implementation provides and what a write repository wants. 18(c) is about a *projection* return type being silently discarded; an entity return type is the base doing its job. A gate that flags every reserved name will fire on every correct write repository in existence |
| 13 | `OrderStatusQueries.findByOrderId(String)` under gate 18(c) | Not a reserved name. It derives a query on the entity's `orderId` property and the projection applies. If this fires, gate 18(c) is matching "looks like an id lookup" rather than the base implementation's actual method names |
| 14 | `OrderStatusQueries` under gate 18(a) | It already extends the bare `Repository` marker — the correct base. Finding 9 is on `ShippingOrders`, not here |
| 15 | Findings 3 or 6 re-reported under gate 18(b) | `ShippingOrders.findByShipped` returning `List<ShippingOrder>` is gate 15(b)'s read-side-from-the-write-model finding, and `RegisterShippingOrderAPI` returning the entity is gate 16's. Gate 18(b) must not double-report either — one defect, one finding, under the most specific gate |

## Manifest expectations (gates 1, 11)

- All four `slice.yaml` files validate, and all four carry `tier: service-entity`.
- `views/order_status/slice.yaml` uses `reads[].via: OrderStatusQueries` — the **existing** schema
  property for a reader interface. A report suggesting a new `readsVia` property is wrong.
- No `slice.yaml` needs a schema change to express this lane; `tier` is an open string.

## Known gaps

- **The multi-lane conflict branch is not exercised here** — this BC is single-lane by construction.
  Adding an `aggregates/` directory alongside `entities/` would exercise it, but would also make the
  fixture's other 24 expectations conditional on a Blocking finding, so it is deliberately left out.
- **Gate 17's decider-lane branches are not exercised** — a missing `routing/`, a command that does
  not implement its BC's marker, and a sealed marker all need a decider-lane fixture, and this repo
  has none. Only the absent-on-this-lane direction is covered here.
- **Kotlin is not exercised.** The lane supports both languages; this fixture is Java only.
- **Mongo is not exercised.** The entity here is JPA-shaped. The lane is persistence-neutral above
  `entities/`, so every other file in the tree would be byte-identical under Mongo.
- `--fix-source` is not exercised. None of the nine findings is in its three-fix scope.
