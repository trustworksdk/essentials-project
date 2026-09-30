# `multi-lane` — gate 14's Blocking branches, and a clean control beside them

A synthetic Java project (`com.acme.multi`) with four bounded contexts. Three of them each isolate one
Blocking row of `/essentials:slice-check` gate 14; the fourth is a clean single-lane BC that must stay
clean although its neighbours are not. It exists because lane detection is **per BC**: a finding in
one bounded context must never bleed into another.

Run:

```
/essentials:slice-check essentials-plugin/tests/fixtures/multi-lane
```

`scripts/slice-lint.py tests/fixtures/multi-lane --require-schema` (from `essentials-plugin/`) exits **1 with exactly one
finding**, ML-5 below; that is intended.

The machine-readable oracle is `expected.yaml` next to this file (findings, non-findings, skipped
gates, lanes, with `path:line` and an anchor per entry). This guide describes the same expectations
for a human; when the two disagree, fix both.
`uv run --script tests/fixtures/check-expected.py` (from `essentials-plugin/`) checks `expected.yaml` against the tree and that no source carries an oracle label.

Eval: `evals/slice-check-multi-lane/` — graders generated from `expected.yaml`; how to run and read it: `evals/README.md`.

## Layout

```
src/main/java/com/acme/multi/
  DeciderWiring.java     the ONE EventStreamDeciderAndAggregateTypeConfigurator for the application
  ledger/                per-slice decider (open_account) AND aggregates/ (post_entry) over one stream
  catalog/               entities/ AND an event-store append (reprice_product)
  payments/              one real lane (decider); its manifests declare two
  inventory/             CONTROL: a clean service-entity BC
```

## Lane detection (gate 14)

| BC | Code signals | Declared `lane:` | Expected |
|---|---|---|---|
| `ledger` | `OpenAccountDecider` implements `EventStreamDecider`; `aggregates/Account.java` exists | `open_account: decider`, `post_entry: aggregate` | **Blocking** — two write designs over the `LedgerAccounts` stream. Name both signals and **pick no lane** |
| `catalog` | `entities/` exists; `RepriceProductHandler` injects `EventStore` and appends to an `AggregateType` | both `service-entity` | **Blocking** — the BC is drifting off the service-entity lane. The manifests agree with each other, so the declared-lane cross-check does **not** fire |
| `payments` | two deciders; no `aggregates/`, no `entities/` | `request_payment: decider`, `capture_payment: aggregate` | Detected lane **decider**; the declared-lane cross-check is **Blocking** and names which slice sits on which side |
| `inventory` | `entities/` exists; no event store anywhere in the BC | `service-entity` | **Fine** — one line saying so |

## Findings — all must appear

| # | Severity | Gate | Where | What |
|---|---|---|---|---|
| ML-1 | **Blocking** | 14 | `ledger/use_cases/open_account/OpenAccountDecider.java` + `ledger/aggregates/` | Decider style and aggregate style in one BC. Both signals named, both files cited |
| ML-2 | **Blocking** | 14 | `catalog/use_cases/reprice_product/RepriceProductHandler.java` (the `appendToStream` call) + `catalog/entities/` | `entities/` plus an `EventStore`/`AggregateType` reference |
| ML-3 | **Blocking** | 14 (declared-lane cross-check) | `payments/use_cases/capture_payment/slice.yaml` vs `request_payment/slice.yaml` | Two declared lanes in one BC, although the code has one |
| ML-4 | **Blocking** | 14 (declared-lane cross-check) | `ledger/use_cases/post_entry/slice.yaml` vs `open_account/slice.yaml` | Corroborates ML-1; reporting it inside ML-1 counts |
| ML-5 | **Should-fix** | 14 (tier vs lane) | `ledger/use_cases/post_entry/slice.yaml` — `tier: aggregate` | A write style in the `tier` field. Repair: `tier: cqrs-es` + `lane: aggregate`. `scripts/slice-lint.py` reports it, so the command takes it verbatim |

## Traps — the report must contain NONE of these

| # | Must **not** be reported | Why |
|---|---|---|
| ML-T1 | Any gate 14 finding on `inventory` | Lanes are per BC. `catalog`'s drift and `ledger`'s conflict say nothing about `inventory`, which has the same shape as `catalog` minus the event store — that difference is the point of the control |
| ML-T2 | `payments/routing/` as a lane signal, or under gate 17 | `routing/` is evidence *about* a BC, never an input to detection, and it is required on the decider lane. The marker is not sealed and declares only the id |
| ML-T3 | `tier: aggregate` or any `lane:` value as lane **evidence** | Detection reads code. ML-5 is a tier finding, not a signal; a lane finding citing a manifest field reasons in the circle gate 14 forbids |
| ML-T4 | `PostEntry` not implementing `AccountCommand` (gate 17) | `ledger` is Blocking, so no lane was picked; applying decider-lane routing rules there means gate 14 quietly chose one |
| ML-T5 | `ledger/routing/` as an aggregate-lane vestige (gate 17) | Same reason, from the other side |
| ML-T6 | `writes: [Account]` in two `ledger` slices (gate 4) | Several command slices writing one aggregate inside one BC is the design on every lane |
| ML-T7 | `DeciderWiring` under gate 2 or 9, or "no configurator in `config/`" | One configurator per **application**: it collects every BC's `EventStreamAggregateTypeConfiguration` and decider beans. Each BC's `config/` carries its type configuration and one `@Bean` per decider, which is what gate 9 checks. A configurator per BC would register every decider once per BC and fail the first command with `MultipleCommandHandlersFoundException` |
| ML-T8 | `catalog/entities/Products` under gate 15 or 18 | The write repository sits beside its entity; it is Essentials' DocumentDB repository, not Spring Data |
| ML-T9 | `StockItem.setVersionValue`/`setLastUpdated` under gate 5 or 16 | The `JavaVersionedEntity` persistence contract, not a setter bypassing `adjust()`'s guard |
| ML-T10 | `request_payment`'s `lane: decider` | It declares the lane the code has; the wrong side of ML-3 is `capture_payment` |

## Skipped — named as skipped, never silently absent

- **Gate 18** — no Spring Data repository in the project.
- **Gate 10** for `inventory` — the service-entity lane has no versioned read model.

## Tolerated

- A "no test" finding on any slice: every manifest says `tests.*.present: false` and the fixture has
  no test tree.
- Rating `capture_payment`'s lane as stale (Should-fix) beside ML-3.
- Pointing out that `catalog/CLAUDE.md` says "no history" while `reprice_product` keeps one.

## API provenance

Every Essentials symbol here compiles against the 0.60 source:

| Symbol | Source |
|---|---|
| `EventStreamDecider.handle` / `canHandle(Class<?>)` | `components/eventsourced-aggregates/src/main/java/dk/trustworks/essentials/components/eventsourced/aggregates/eventstream/EventStreamDecider.java` |
| `EventStreamAggregateTypeConfiguration` (6-component record) | `…/eventstream/EventStreamAggregateTypeConfiguration.java:62` |
| `EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, configs, deciders)` | `…/eventstream/adapters/EventStreamDeciderAndAggregateTypeConfigurator.java:137` (registers one adapter per aggregate type, `:207`) |
| `AggregateRoot`, `StatefulAggregateRepository.from`, `reflectionBasedAggregateRootFactory` | as in `tests/fixtures/aggregate-lane/` |
| `EventStore.appendToStream(AggregateType, ID, List<?>)` | `components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/EventStore.java:139` |
| `EventBus.publish(Object)` | `reactive/src/main/java/dk/trustworks/essentials/reactive/EventBus.java:69` |
| `DelegatingDocumentDbRepository`, `DocumentDbRepositoryFactory.createForStringId(Class)` | `components/postgresql-document-db/src/main/kotlin/dk/trustworks/essentials/components/document_db/DocumentDbRepository.kt:735`, `:554` |
| `DocumentDbRepository.save(entity, Long)`, `update(entity)`, `getById`, `existsById` | `DocumentDbRepository.kt:198`, `:209`, `:274`, `:283` |
| `JavaVersionedEntity`, `Version.ZERO_VALUE` / `NOT_SAVED_YET_VALUE` (`@JvmField`) | `JavaVersionedEntity.kt:25`, `Version.kt:57-61` |

## What it does not cover

- **Kotlin.** The lane rows are language-independent; `worked-example` is the Kotlin tree.
- **Gate 17's decider-lane failure rows** (a missing `routing/`, a command not implementing its marker,
  a sealed marker). `payments` is their clean case only.
- **`--fix-manifests`.** ML-3 and ML-5 are the manifest repairs it would make; this fixture does not
  run it.
