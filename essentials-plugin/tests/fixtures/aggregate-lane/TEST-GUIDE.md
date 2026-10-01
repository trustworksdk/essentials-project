# `aggregate-lane` — the §R5 aggregate write style

A synthetic Java bounded context (`com.example.billing`) on the **aggregate lane**: one aggregate type
per BC, reached through a repository wrapper, with per-slice command handlers that delegate to it,
plus a view and an automation reading the aggregate's stream.

It gives `/essentials:slice-discover`'s pass-0 lane detection something to run its `AggregateRoot`
branch against — the other fixtures cover **decider-style** and **none-of-the-above** — and does the
same for the `aggregate` row of `/essentials:slice-check` gate 14. Its view also carries the gate 6
query-discriminator cases: value-pinned, multi-parameter, an optional filter, and one bound only
across two handlers.

The machine-readable oracle is `expected.yaml` next to this file; this guide describes the same
expectations for a human. When the two disagree, fix both.
`uv run --script tests/fixtures/check-expected.py` (from `essentials-plugin/`) checks `expected.yaml` against the tree and that no source carries an oracle label.

Eval: `evals/slice-check-aggregate-lane/` — graders generated from `expected.yaml`; how to run and read it: `evals/README.md`.

## Layout

```
src/main/java/com/example/billing/
  aggregates/Invoice.java             the consistency boundary — extends AggregateRoot
  aggregates/Invoices.java            repository wrapper + the AggregateType constant
  events/                             sealed parent + two variants
  types/InvoiceId.java
  use_cases/issue_invoice/            CLEAN creation slice
  use_cases/pay_invoice/              FINDING — see below
  views/invoice_list/                 ViewEventProcessor + DocumentDB read model; FINDING F4
  automations/recurring_billing/      EventProcessor + TodoList; sends IssueInvoice
  config/BillingConfiguration.java
```

No `routing/` and no `use_cases/_shared/` — correct for this lane, and readers must not report their
absence.

## What each consumer should conclude

| Consumer | Expected |
|---|---|
| `slice-check` gate 14 | Lane = **`aggregate`**, detected from `<bc>/aggregates/` existing. Not `decider` (no per-slice deciders — a `ViewEventProcessor` or `EventProcessor` is not one), not `service-entity` (no `entities/`), not Blocking (one signal). All four manifests declare `lane: aggregate`: fine, one line |
| `slice-discover` pass 0 | **Redirect to `slice-check`** — manifests exist. It must not analyse |
| `slice-map` | Four slices in one BC, each manifest showing `tier: cqrs-es` with `lane: aggregate`; `Invoice` as the write target of both command slices. Edges: `issue_invoice` →`InvoiceIssued`→ `invoice_list`, `recurring_billing`; `pay_invoice` →`InvoicePaid`→ the same two; `recurring_billing` →`IssueInvoice`→ `issue_invoice`. That last edge closes a **cycle** (`issue_invoice → recurring_billing → issue_invoice`): chains must stop at the repeated node |

## Findings (must be reported)

| # | Where | What |
|---|---|---|
| **F1** | `use_cases/pay_invoice/PayInvoiceHandler.java` | The domain rule is **in the handler**: it decides whether the invoice may be paid. `Invoice.pay()` already guards this, so the aggregate is no longer the sole enforcer and any other caller bypasses the handler's check. Expected against `rules/slice-design.md` § The aggregate's own bar |
| **F2** | `use_cases/pay_invoice/slice.yaml` | `invariants[].enforcedBy: "PayInvoiceHandler"` — the manifest **records** the leak. On this lane the enforcer must be the aggregate method. A reader that checks `enforcedBy` against the lane catches F1 from the manifest alone, without reading a method body |
| **F3** | `use_cases/pay_invoice/` | No test of any kind (`tests.unit.present: false`), on the one slice that carries a rule |
| **F4** | `views/invoice_list/slice.yaml` — `"/api/billing/invoices?paid=true&minAmount="` | Gate 6, **Should-fix**. No handler serves this endpoint: `paid=true` is bound only by `paidInvoices()` and `minAmount` only by `byAmount()`. A check that unions the bound parameters across the slice passes it; the rule is **per handler** (`commands/slice-check.md` gate 6, `manifest-guide.md` §3) |
| **F5** | `views/invoice_list/slice.yaml` | A view slice with no test — Should-fix per `rules/slice-design.md` § Reporting severities |

## Traps (must NOT be reported)

| # | Where | Why it looks wrong but is not |
|---|---|---|
| **T1** | `Invoice.isPaid()` | A public getter on an aggregate is not a violation. Its only caller is a command handler; the view reads its own read model, never the aggregate. A reader that flags "public accessor on aggregate" fires here wrongly |
| **T2** | `IssueInvoiceHandler` — `if (invoices.isInvoiceMissing(...))` | This *is* an `if` in a handler, but it is **idempotency**, not a domain rule: the command bus delivers at least once. Distinguishing it from F1 is the point of having both slices — a check that keys on "handler contains `if`" cannot tell them apart and must not be written that way |
| **T3** | `BillingConfiguration` being nearly empty | Correct on this lane. No decider configurator and no `AggregateType` registration belong here — the constant lives on `Invoices` |
| **T4** | `Invoice` having two constructors | The single-argument one is the rehydration constructor Essentials requires; it applies nothing by design |
| **T5** | `?paid=true` ↔ `@GetMapping(params = "paid=true")` | A value-pinned discriminator, bound in that handler. Neither a missing endpoint nor an undeclared mapping |
| **T6** | `?minAmount=&maxAmount=` ↔ `@GetMapping(params = {"minAmount", "maxAmount"})` | A multi-parameter discriminator: one handler binds both |
| **T7** | `list()`'s `@RequestParam(defaultValue = "100") int limit` | An optional filter with a default selects no mapping, so it is not an endpoint and needs no `?limit` entry |
| **T8** | `InvoiceListAPI`'s three `@GetMapping` methods | A **view** slice is scoped by the read model it owns, not by endpoint count |
| **T9** | `InvoiceListProjection` importing `aggregates.Invoices` | It names `Invoices.AGGREGATE_TYPE` — the stream it subscribes to — and nothing else. Gate 8's four clauses do not cover a same-BC `aggregates/` import, and a constant is not a query surface (§ The aggregate's own bar, point 2). **It would be a finding if the view called any method on `Invoices`** |
| **T10** | `RecurringBillingProcessor` importing `use_cases.issue_invoice.IssueInvoice` | The §R4 carve-out: the type is only constructed and handed to `getCommandBus().sendAndDontWait(…)` |
| **T11** | Gate 10 on the view or the automation | Every handler that writes versioned state takes `OrderedMessage` and passes `getOrder()` to the `long` overload of `save`/`update` |
| **T12** | Gate 14 multi-lane on `billing` | Processors are not deciders; the lane stays single |
| **T13** | `PayInvoiceAPI.PayInvoiceRequest` as an adapter (gate 6) | It carries only `paidMinor`; the id arrives by path. Assembly, not a mirror of the command |
| **T14** | `InvoiceListView`'s `public` `@Id` field | A Java DocumentDB entity's `@Id` field must be public — the reflection layer reads the field |
| **T15** | `recurring_billing`'s `consumes` under gate 11(b) | It lists exactly the two event types its handlers take |

## Skipped

Gates 15 and 16 (service-entity lane only) and 18 (no Spring Data repository) are named as skipped.

## API provenance

The sources compile against the 0.60 reactor. The symbols the view and automation add:

| Symbol | Source |
|---|---|
| `ViewEventProcessor(ViewEventProcessorDependencies)` | `components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/processor/ViewEventProcessor.java:92` |
| `EventProcessor(EventProcessorDependencies)` | `…/processor/EventProcessor.java:228` |
| `getProcessorName`, `reactsToEventsRelatedToAggregateTypes`, `onSubscriptionsReset`, `getCommandBus()` | `…/processor/AbstractEventProcessor.java:432`, `:439`, `:401`, `:509` |
| `OrderedMessage.getOrder()` (`long`) | `components/foundation/src/main/java/dk/trustworks/essentials/components/foundation/messaging/queue/OrderedMessage.java:220` |
| `CommandBus.sendAndDontWait(C, Duration)` | `reactive/src/main/java/dk/trustworks/essentials/reactive/command/CommandBus.java:248` |
| `DocumentDbRepository.save(entity, Long)`, `update(entity, Long)`, `findById`, `existsById`, `deleteAll`, `queryBuilder`, `condition` | `components/postgresql-document-db/src/main/kotlin/dk/trustworks/essentials/components/document_db/DocumentDbRepository.kt:198`, `:225`, `:267`, `:283`, `:388`, `:452`, `:485` |
| `Condition.eq/gte/lte(path, value, DbType)`; chained conditions are ANDed | `…/document_db/postgresql/Query.kt:183`, `:223`, `:203`; `:384` |
| `QueryBuilder.limit`, `find()` | `Query.kt:944`, `:1029` |
| `CharSequenceType.value()` returns `CharSequence` (so ids go to `String` via `toString()`) | `types/src/main/java/dk/trustworks/essentials/types/CharSequenceType.java:77` |

## What it does not cover

- **Java only.** There is deliberately no Kotlin aggregate-lane fixture, matching the deliberate
  absence of `templates/kotlin/command_aggregate/`: `slice-check` treats `aggregates/` in a Kotlin BC
  as **Advisory** interop rather than a supported shape.
- **No build.** The tree carries sources only; nothing in CI compiles it.
- **The multi-lane Blocking rows** live in `tests/fixtures/multi-lane/`.
