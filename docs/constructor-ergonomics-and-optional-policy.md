# Design: construction ergonomics and the `Optional` policy

**Status:** in force, enforced by ArchUnit, sweep in progress.

This is the *why*. For the per-class before-and-after tables, and for what a consumer has to change,
see **[MIGRATION-NEXT_MAJOR.md](MIGRATION-NEXT_MAJOR.md)** — nothing here is required reading to upgrade.
The rules are stated normatively for contributors in `.claude/rules/code-style.md`; this document is what
those two are derived from.

## The problem

Two API-shape problems had accumulated across the reactor, both of them the kind that never look urgent
in any single review and are painful in aggregate.

**Wide constructors.** 84 constructors took six or more parameters; the worst took 17. Past about five
arguments a call site stops being readable — the reader has to count commas against a signature they
cannot see. Worse, two adjacent parameters of the same type transpose silently: swapping two `String`s
or two `Optional<X>`s compiles, passes review, and fails at runtime somewhere else entirely.

**`Optional` in constructors.** Roughly 100 `Optional` parameters, of which `Optional<MeterRegistry>`
alone accounted for 36. Every one of them was unwrapped on the next line into a nullable field or a
default. The `Optional` bought nothing at the receiving end and cost every caller an `Optional.of(...)`
wrapper — or, more often, an `Optional.empty()` that says "I do not care about this" in the most verbose
way the language offers.

`Optional` was designed as a return type. Using it as a parameter type inverts the contract: instead of
the callee promising "this may be absent", the caller is *obliged* to construct a wrapper for a value it
already has.

## The policy

1. **No public or protected constructor declares an `Optional` parameter.** Absence is expressed as a
   neutral default, a sealed variant, or a builder-resolved nullable — see [The four shapes](#the-four-shapes)
   below.
2. **No public or protected constructor declares more than five parameters.** Above that, the arguments
   are a `XxxDependencies` bundle, a `XxxSettings` record, or a builder for the class itself. Five is a
   judgement call, not a discovered constant; it is roughly where a call site stops being readable
   without an IDE, and the specific number matters far less than having one at all.
3. **A constructor that breaks either rule is deprecated, never deleted.** It stays, marked
   `@Deprecated(forRemoval = true, since = "…")` with a `@deprecated` javadoc tag naming its replacement,
   re-implemented to delegate to the new path.
4. **A `forRemoval` deprecation must name a way out.** The declaring class needs a builder or a compliant
   non-deprecated constructor. A deprecation with no replacement is a dead end, not a migration.

### Where `Optional` stays

The policy is about *construction*, not about `Optional` as such. It remains correct and is unchanged in:

- **return types** — the use it was designed for;
- **builder-setter overloads** — every generated builder offers both `setXxx(T)` and `setXxx(Optional<T>)`,
  precisely so a caller who already holds an `Optional` need not unwrap it;
- **Spring `@Bean` method signatures**, where the container supplies the `Optional` and it is unwrapped on
  the spot.

### Exemption: a record's canonical constructor

A record's canonical constructor is exempt from **both** rules.

From the ceiling, because a record used as a cohesive parameter object is *supposed* to be wide — capping
it would defeat the very refactoring the ceiling exists to encourage. From the `Optional` rule, because a
record component's declared type *is* its accessor's return type, and `Optional` return types are
explicitly permitted. Flagging `record Descriptor(Optional<EventOrder> latestSnapshot)` would demand the
component become nullable, changing `latestSnapshot()` from returning `Optional` to returning `null` —
the opposite of what the policy asks for everywhere else. The canonical constructor has no freedom here:
it takes exactly the components, in order.

A record's *non*-canonical constructors get no exemption; those are ordinary overloads.

## The four shapes

Every conversion in the sweep is one of these. The migration guide has worked before-and-after examples.

| Shape | When | Example |
|---|---|---|
| **Neutral default** | The `Optional` guarded a collaborator that already had a do-nothing mode | `Optional<MeterRegistry>` → `MeasurementTaker`, with `MeasurementTaker.none()` |
| **Sealed variant** | An enum and an `Optional` collaborator were one value, re-validated at runtime | `CdcDeliveryMode` + `Optional<Consumer<…>>` → `CdcDelivery.direct(…)` / `CdcDelivery.inbox(…)` |
| **Builder-resolved nullable** | Genuinely optional configuration with no meaningful neutral object | `PostgresqlAggregateSnapshotRepository.builder().setSnapshotTableName(…)` |
| **Parameter object** | A wide constructor whose arguments group naturally | `WalReplicationTailer(CdcTailerDependencies, CdcTailerSettings, CdcDelivery)` |

The sealed variant is the one worth reaching for when it applies: it does not merely tidy the signature,
it removes a runtime check by making the illegal combination unconstructible. `WalReplicationTailer`'s
`directOnEvents cannot be null in DIRECT delivery mode` guard is gone because the state it guarded can no
longer be expressed.

Two classes deliberately keep a plain **nullable** `MeterRegistry` rather than a `MeasurementTaker`:
`CdcAvailability` and `CdcSlotMetrics` register Micrometer `Gauge`s and `Counter`s, which a timing facade
cannot express. Pass `null` for "no metrics".

## Enforcement

`EssentialsConstructionRules` (in `components/foundation-test`, `…foundation.test.architecture`) defines
the three rules as ArchUnit `ArchRule`s. Two details of how it decides things are worth knowing, because
both are structural rather than annotation-driven:

- **A builder is recognised as a static, non-deprecated `builder(...)` method**, searched on the class and
  then outwards through its enclosing classes. A nested implementation type is routinely constructed
  through a factory on the interface it implements —
  `StatefulAggregateRepository.builder(eventStore)` returns a
  `StatefulAggregateRepository$DefaultStatefulAggregateRepository` — and that is a perfectly good way out
  for a caller even though the method is not on the nested class.
- **A record's canonical constructor is detected by parameter count matching the non-static field count**,
  because ArchUnit's `JavaClass` does not expose record components directly.

The javadoc `@deprecated` tag naming the replacement is a **review checklist item, not an enforced rule** —
bytecode does not carry javadoc. Rule 4 can only check that *some* way out exists, not that it is
documented.

### Frozen, not aspirational

The reactor does not satisfy rules 1 and 2 yet, and a red build for the duration of a multi-phase sweep
would be worth nothing. Both are therefore wrapped in ArchUnit's `FreezingArchRule`, which accepts the
violations recorded in a checked-in store and fails only on **new** ones. `allowStoreUpdate=true` means a
violation that gets fixed is dropped from the store automatically — so the store shrinks, and never grows,
without someone deciding it should. Its size is the progress metric for the sweep.

**The store is the rule's input, not build output, and is committed.** This is easy to get wrong: with
`allowStoreCreation=true` and no store in git, a clean checkout — which is every CI job — writes a fresh
baseline from whatever it happens to find and passes. The guard then enforces nothing, and does so
silently. `freeze.refreeze=true` re-baselines everything and exists only for a deliberate, reviewed
re-baseline; it is never the way past a red build.

### Coverage is decided by classpath

The test that runs the rules — `AbstractEssentialsConstructionErgonomicsTest`, also in `foundation-test` —
imports `dk.trustworks.essentials` from its own test classpath, so a module can only guard what it can
already see. No single module sees everything, so four modules extend it, each with its own
`archunit.properties` and its own committed `archunit_store/`. A subclass is empty by design: what it
guards is decided by its module's POM, not by any code it writes.

| Module | Adds coverage of | Violations |
|---|---|---|
| `components/eventsourced-aggregates` | core chain, PostgreSQL event store, aggregates | 22 |
| `components/spring-boot-starter-postgresql` | PostgreSQL queue + fenced lock | 0 |
| `components/spring-boot-starter-mongodb` | MongoDB queue + fenced lock, `types-springdata-mongo` | 2 |
| `components/spring-boot-starter-postgresql-event-store` | `spring-postgresql-event-store`, own auto-config | 25 (22 shared, 3 unique) |

The starters are the natural vantage points because a starter exists precisely to pull one complete
implementation stack onto a single classpath — no module needed a dependency added for the test's sake.

Where two classpaths overlap, a violation is recorded in **both** stores. That is accepted rather than
worked around: `allowStoreUpdate` clears it from every store that holds it on the next run, whereas
restricting each module's import to "the packages nobody else covers" is a mapping that rots the moment a
dependency moves. The event-store starter is the extreme case — a strict superset of two other stores,
earning its place with the three violations only it can see.

`archunit-junit5` is `<optional>` in `foundation-test`'s POM, because that module ships to consumers and
must not push ArchUnit onto everyone who uses it; each module extending the test declares it test-scoped
itself.

Still unguarded, for want of a classpath that reaches them: `kotlin-eventsourcing`,
`postgresql-document-db`, `types-avro`, `types-spring-web`, `types-springdata-jpa`, the `admin-api-*`
modules and the two admin starters.

## Release mechanics

The rules are phrased as "… **or** is annotated `@Deprecated(forRemoval = true)`". That phrasing is what
lets one rule serve the whole migration without ever being edited:

- **In the bridge release** every offender is deprecated, so the rule holds. Consumers upgrade, change
  nothing, and get deprecation warnings and no errors. Everything is source- and binary-compatible except
  the two behaviour changes the migration guide calls out.
- **At the next major**, the deprecated constructors are removed. The escape clause then has nothing left
  to match and the rule becomes absolute — with no change to the rule and no flag day.

When the last violation is gone from every store, the `FreezingArchRule.freeze(...)` wrappers and the
stores themselves are deleted. The rules underneath are already written to be absolute.

This also fixes the order of work: **deprecate and provide the replacement first, remove later.** Rule 4
exists to stop the two halves being separated — a `forRemoval` deprecation that lands without a builder or
a compliant constructor alongside it fails the build immediately, and that rule is deliberately *not*
frozen, so there is no store to absorb it.

## Risks

**Reshaping a Jackson creator changes the wire format.** Under Jackson 3 a constructor parameter *name* is
part of the JSON contract: J3 reads parameter names from bytecode and uses any constructor as an implicit
properties-based creator. Renaming a parameter, or routing it through a parameter object, can therefore
change how a persisted type deserialises — silently, into `null` fields rather than an error. This is why
`PersistedEvent.DefaultPersistedEvent` and `PersistableEvent.DefaultPersistableEvent` keep their wide
constructors and are **deliberately excluded** from the sweep. Any conversion of a type that is
serialised must be checked against both Jackson flavours before it lands.

**A builder can lose a default.** Moving construction from a constructor to a builder moves every default
from an argument list to field initialisers, and an uninitialised `boolean` field is `false` whether or not
that was the constructor's default. This has already bitten twice, in both directions:
`PostgresqlDurableQueues`' `useOrderedUnorderedQuery` (constructor `true`, builder `false` — a 5.4×
slowdown on mixed backlogs) and its `TransactionalMode` (constructor `FullyTransactional`, builder
`SingleOperationTransaction`). Both are documented in the migration guide's behaviour-changes section.
When converting, diff the *effective* defaults, not the signatures.

**A parameter object can widen an API it was meant to narrow.** A `XxxDependencies` bundle becomes public
surface of its own, subject to the same stable-API guarantee as the class it serves. Introducing one is a
design decision, not a mechanical refactor — if the grouping is arbitrary, the bundle is worse than the
wide constructor it replaced.

**Frozen violations decay into permanent ones.** A freeze store makes a red build green, which is exactly
the property that lets it become a place violations go to be forgotten. The counter-pressure is that the
store is committed and reviewable: a diff that *adds* to it cannot happen without the rule failing first,
and a phase that converts something shows up as lines removed.

## What is left

18 distinct classes still hold a recorded violation, across the four stores. The concentration is in
`postgresql-event-store` — CDC, the subscription manager, and the persistence strategy — plus:

- **`MongoDurableQueues`** (2 violations: a 16-parameter `DurableQueuedMessage` constructor and the
  7-parameter main one), which was **missed by the sweep its PostgreSQL counterpart went through**.
  `PostgresqlDurableQueues`' wide constructors are all deprecated with builders alongside; the MongoDB
  ones are not, and the migration guide has no per-module entry for them. This is a real gap in the
  bridge release, not a test artifact — it surfaced only when the MongoDB starter brought those classes
  under a guard for the first time.
- **`DefaultAggregateLifecycleConfigurationValidator`** and **`CdcHealthIndicator`**, both in the
  event-store starter's own auto-configuration, likewise newly visible.

`PersistedEvent.DefaultPersistedEvent` and `PersistableEvent.DefaultPersistableEvent` are in the stores but
are not work items — see Risks.

## See also

- [MIGRATION-NEXT_MAJOR.md](MIGRATION-NEXT_MAJOR.md) — consumer-facing before-and-after, per module
- `.claude/rules/code-style.md` — the rules as normative contributor guidance
- `components/foundation-test/src/main/java/…/architecture/EssentialsConstructionRules.java` — the rules
- `components/foundation-test/src/main/java/…/architecture/AbstractEssentialsConstructionErgonomicsTest.java` — the harness
