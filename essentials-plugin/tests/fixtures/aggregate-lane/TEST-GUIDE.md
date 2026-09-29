# `aggregate-lane` — the §R5 aggregate write style

A synthetic Java bounded context (`com.acme.billing`) on the **aggregate lane**: one aggregate type
per BC, reached through a repository wrapper, with per-slice command handlers that delegate to it.

It gives `/essentials:slice-discover`'s pass-0 lane detection something to run its `AggregateRoot`
branch against — the other fixtures cover **decider-style** and **none-of-the-above** — and does the
same for the `aggregate` row of `/essentials:slice-check` gate 14.

## Layout

```
src/main/java/com/acme/billing/
  aggregates/Invoice.java        the consistency boundary — extends AggregateRoot
  aggregates/Invoices.java       repository wrapper + the AggregateType constant
  events/                        sealed parent + two variants
  types/InvoiceId.java
  use_cases/issue_invoice/       CLEAN creation slice
  use_cases/pay_invoice/         FINDING — see below
  config/BillingConfiguration.java
```

No `routing/` and no `use_cases/_shared/` — correct for this lane, and readers must not report their
absence.

## What each consumer should conclude

| Consumer | Expected |
|---|---|
| `slice-check` gate 14 | Lane = **`aggregate`**, detected from `<bc>/aggregates/` existing. Not `decider` (no per-slice deciders), not `service-entity` (no `entities/`), not Blocking (only one of the three signals present) |
| `slice-discover` pass 0 | **Redirect to `slice-check`** — manifests exist. It must not analyse |
| `slice-map` | Two command slices, one BC, `tier: aggregate` badge, `Invoice` as the write target of both |

## Findings (must be reported)

| # | Where | What |
|---|---|---|
| **F1** | `use_cases/pay_invoice/PayInvoiceHandler.java` | The domain rule is **in the handler**: it decides whether the invoice may be paid. `Invoice.pay()` already guards this, so the aggregate is no longer the sole enforcer and any other caller bypasses the handler's check. Expected against `rules/slice-design.md` § The aggregate's own bar |
| **F2** | `use_cases/pay_invoice/slice.yaml` | `invariants[].enforcedBy: "PayInvoiceHandler"` — the manifest **records** the leak. On this lane the enforcer must be the aggregate method. A reader that checks `enforcedBy` against the lane catches F1 from the manifest alone, without reading a method body |
| **F3** | `use_cases/pay_invoice/` | No test of any kind (`tests.unit.present: false`), on the one slice that carries a rule |

## Traps (must NOT be reported)

| # | Where | Why it looks wrong but is not |
|---|---|---|
| **T1** | `Invoice.isPaid()` | A public getter on an aggregate is not a violation. State is still written only by `@EventHandler` methods; exposing a read is how a handler's idempotency guard is written on other lanes. A reader that flags "public accessor on aggregate" fires here wrongly |
| **T2** | `IssueInvoiceHandler` — `if (invoices.isInvoiceMissing(...))` | This *is* an `if` in a handler, but it is **idempotency**, not a domain rule: the command bus delivers at least once. Distinguishing it from F1 is the point of having both slices — a check that keys on "handler contains `if`" cannot tell them apart and must not be written that way |
| **T3** | `BillingConfiguration` being nearly empty | Correct on this lane. No decider configurator and no `AggregateType` registration belong here — the constant lives on `Invoices` |
| **T4** | `Invoice` having two constructors | The single-argument one is the rehydration constructor Essentials requires; it applies nothing by design |

## What it does not cover

- **No view or automation slice.** Both are lane-independent here (a projection over the event stream
  looks the same on the decider lane), so they are covered by the other fixtures.
- **Java only.** There is deliberately no Kotlin aggregate-lane fixture, matching the deliberate
  absence of `templates/kotlin/command_aggregate/`: `slice-check` treats `aggregates/` in a Kotlin BC
  as **Advisory** interop rather than a supported shape.
- **Nothing here compiles.** As with every fixture in this repo, there is no build. The imports are
  real and every Essentials symbol is in `references/slice/api-provenance.md`, but that is
  documentary provenance, not compilation.
- **No multi-lane trap.** A BC holding two of {deciders, `aggregates/`, `entities/`} must be
  Blocking; that case still has no fixture.
