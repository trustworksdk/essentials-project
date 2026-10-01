# Test guide — `brownfield-layered`

A synthetic Maven/Spring/JPA service organised **by technical layer**, used to exercise
`/essentials:slice-discover`. It is *not* an Essentials project and does not follow
`rules/slice-design.md` — that is the point. Never shipped to a user.

```
pom.xml                                  Spring Boot 3 + web + data-jpa
src/main/java/com/example/shop/
  controller/   OrderController          5 mappings — the god controller
                InvoiceController        2 mappings
                ReportController         1 mapping — the trap
  service/      OrderService             writes Order
                BillingService           writes Invoice AND Order
                PaymentReminderJob       @Scheduled, writes
  repository/   OrderRepository, InvoiceRepository
  model/        Order, OrderLine, Invoice
  integration/  PaymentGatewayClient + dto/ (foreign snake_case schema)
```

Fourteen Java files. Every package name is a **layer**, so nothing in the tree names a domain
boundary — a package-name-driven analyser finds exactly one context here and is wrong. The fixture
holds one instance of everything the heuristics claim to find, **plus traps**: a fixture containing
only findings proves nothing about false positives, which are the failure mode that discredits an
inference tool.

The sources carry no oracle labels: the ground truth lives only here and in `expected.yaml`. Its ids: `P1`–`P6` for the ranked findings (Pass 3 order), `N1`–`N7` for the must-nots.
`uv run --script tests/fixtures/check-expected.py` (from `essentials-plugin/`) checks `expected.yaml` against the tree and that no source carries an oracle label.

Eval: `evals/slice-discover-brownfield-layered/` and `evals/slice-check-brownfield-layered/`; change-router's `gate-not-essentials` case — graders generated from `expected.yaml`; how to run and read it: `evals/README.md`.

Editing rules:

- Keep files short — these are signal carriers, not realistic code.
- **If you add a heuristic to `references/slice/discovery-heuristics.md`, add a fixture element that
  exercises it, a row here, and an entry in `expected.yaml`.** A heuristic with no fixture element is
  unexercised.
- Never put a finding or trap label in a source comment; the model under test reads the sources.

Run:

```
/essentials:slice-discover essentials-plugin/tests/fixtures/brownfield-layered
```

Then compare the report with the ground truth below.

`/essentials:slice-check` on this tree must decline at its Step 1.2: no `slice.yaml` and no role directory (`use_cases/`,
`views/`, `automations/`, `external_systems/`) means the project never opted into the law. It points at
`/essentials:slice-discover`, stops, runs no gates, reports no finding (not even "missing manifest"), and writes nothing
(`expected.yaml` `runs[1]`).

## Pass 0 — terrain

| Expected | Why it matters |
|---|---|
| Maven, single module, Java | Basic detection |
| Spring Boot web + Spring Data JPA; **no** `dk.trustworks.essentials.*` | Must not claim Essentials |
| R5 lane: **`none (nearest: service-entity)`** — no deciders, no `AggregateRoot`, and no Essentials on the classpath | The on-lane/nearest distinction (`discovery-heuristics.md` §3.1). This tree is entity-and-repository shaped, so *nearest* is service-entity — but the service-entity lane requires the Essentials command bus, which is absent, so it is **not on** it. Reporting `lane: service-entity` here is a **failure**: it would mean the plugin claims jurisdiction over plain Spring/JPA code |
| No `slice.yaml` anywhere → **does not** redirect to `slice-check` | The redirect must be conditional, not reflexive |

## Pass 1 — bounded contexts

**Ground truth: two contexts, and the evidence is genuinely mixed.**

| Candidate | Owns | Supporting evidence | Counter-evidence that MUST be reported |
|---|---|---|---|
| `orders` | `Order`, `OrderLine`, `OrderRepository` | `OrderService` is the only writer of `Order` apart from one method; `order_lines` FK-scoped to `orders` | `BillingService.payInvoice` also writes `Order` |
| `billing` | `Invoice`, `InvoiceRepository` | `BillingService` + `PaymentReminderJob` are its only writers; separate table; distinct lifecycle (`UNPAID`/`PAID`) | `Invoice.orderId` references the other context |

Checks:

- **Two contexts, not one.** Finding one context means package names were weighted too highly — every
  package here is a layer.
- **Two contexts, not four.** Splitting per layer (`controller`, `service`, …) is the same failure
  wearing the opposite hat.
- The `what argues against it` field is **non-empty for both**. If it ships empty, the field is
  decorative and the ranking cannot be trusted.
- Confidence should be **medium at best**, not high — the cross-context write is real ambiguity.

## Pass 2 — candidate slices

**Seven slices. The count is the assertion.**

| Kind | Slice | From | Note |
|---|---|---|---|
| command | `orders.place_order` | `OrderController.place` → `OrderService.placeOrder` | |
| command | `orders.cancel_order` | `OrderController.cancel` → `cancelOrder` | Has a real invariant (`SHIPPED` rejected) |
| command | `orders.ship_order` | `OrderController.ship` → `markShipped` | |
| view | `orders.order_list` | `OrderController.list` **and** `OrderController.get` | **One slice, two queries.** Same returned shape (`Order`) over the same model — R2's view rule. Emitting two view slices is the single most likely wrong answer |
| command | `billing.pay_invoice` | `InvoiceController.pay` → `payInvoice` | |
| view | `billing.unpaid_invoices` | `InvoiceController.unpaid` | |
| view | `orders.order_status_report` | `ReportController.ordersByStatus` | **Trap.** Reads the same entity as `order_list` but returns an aggregate shape (`Map<String,Long>`), so it is a *different* read model and a separate slice. Merging it into `order_list` is a false negative |

Also expected:

- `PaymentReminderJob` → **automation** candidate (`billing.payment_reminders`), no API.
- `PaymentGatewayClient` + `dto/` → **translation** candidate (`external_systems/payment_gateway`),
  identified by the foreign snake_case schema crossing the boundary.
- `OrderController` reported as splitting into **four** slices (3 command + 1 view).

## Pass 3 — findings, in rank order

1. **Sole-writer violation (highest payoff).** `Order` is written by `OrderService.placeOrder`,
   `cancelOrder`, `markShipped` **and** `BillingService.payInvoice`. The cross-service write is the
   finding; the three within `OrderService` are not. If the report ranks anything above this, the
   payoff ordering is wrong.
2. **Cohesion.** `OrderController` → 4 slices; `OrderService` → 4 slices (3 command + shared queries).
   Reported as slice counts, **not** as line counts.
3. **Boundary.** `BillingService` reaches into `orders`' entity and repository directly.
4. **Lane.** No sanctioned R5 lane; nearest is service-entity. The ladder's rung 4 is a *later*
   choice, not a recommendation now.

Also expected from `discovery-heuristics.md` §7, because the nearest lane is service-entity:

- **Write-repository query drift** — `OrderRepository`/`InvoiceRepository` finders serving
  `OrderController.list`, `.get`, `ReportController.ordersByStatus` and `InvoiceController.unpaid`.
- **Entity returned from an API** — the controllers return `Order` / `Invoice` directly.

## Pass 4 — ladder

Rung 1 must be a **pure regroup** with no framework adoption — if the proposed first step mentions
`Decider`, `AggregateRoot`, or the event store, the ladder is inverted and the proposal is
unadoptable.

Rung 4 must offer **service-entity first** for this tree — formalise `Order`/`Invoice` into
`entities/`, split the god controllers and services, move the read side off the write repositories —
and must **not** present rung 4 as "adopt event sourcing". Offering only deciders or aggregates is a
failure: it tells state-stored code its only route onto the law is an event store, which is not
true.

## False-positive checks

The report must **not** contain any of these:

- A `payments` or `reporting` bounded context — `PaymentGatewayClient` is an outbound adapter of
  `billing`, and `ReportController` is a view of `orders`. Neither is a context.
- `orders.get_order` as its own view slice (see the `order_list` row).
- A recommendation to adopt Essentials before rungs 1–3.
- Any severity labelled **Blocking**. This code never opted into the law; `slice-discover` ranks by
  payoff and leaves the law's severities to `slice-check`.
- **`lane: service-entity`.** The nearest lane is service-entity; the actual lane is `none`. Collapsing
  the two is the failure mode `discovery-heuristics.md` §3.1 exists to prevent.
- A finding against `Order.getStatus()` / `Invoice.getStatus()` **as a query surface**. These are plain
  JavaBean accessors on a non-Essentials entity; the §7 entity bar applies only once a BC is on the
  lane, and even then it distinguishes by caller.

## Known gaps

- **Aggregate-lane detection is not exercised here.** This fixture has no Essentials aggregate, and
  `tests/fixtures/worked-example/` is decider-style, and `tests/fixtures/aggregate-lane/` covers the
  `AggregateRoot` branch.
- **Service-entity *on-lane* detection is not exercised here** — by design, since this fixture is the
  *nearest* case. `tests/fixtures/service-entity/` covers the on-lane case and `/essentials:slice-check`.
- The pass-0 **redirect** to `slice-check` is exercised against `tests/fixtures/worked-example/`, not here.
