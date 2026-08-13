# Aggregate configuration ergonomics

> **Status: implemented.** Phase 0 (merging the two closing-books serializer interfaces into a single
> `ClosingBooksIdSerializer`) came first, because it changes the surface the rest builds on. Phases 1, 2a, 2b, 3 and 4
> then landed in the order 2b → 1 → 4 → 3, putting the silent-failure fix first. **2c (classpath scanning) is
> deliberately not implemented** — see the recommendation in that section.
>
> Deviations from the design as written, all noted in place below: the builder's unit-of-work factory is unconditionally
> required, not "unless a generation repository is given"; `forType(...)`'s tests use local id fixtures rather than
> `types/src/test` ones; the `Optional`-aware `from(...)` overload was added for the `AggregateType` flavour only; and
> the demo landed at 161 lines rather than the estimated ~90.

## Context

`examples/essentials-trading-demo/.../TradingDemoAggregateConfiguration.java` is 268 lines that configure five
aggregate types. Three of those five need nothing but a one-line `StatefulAggregateRepository.from(...)`. The
remaining 240-odd lines exist because two aggregates want snapshots and one wants closing-books generations.

The demo is not doing anything unusual. It is doing the *normal* thing, and the normal thing currently costs:

- three anonymous serializer implementations (two after Phase 0) that all say the same thing twice: `toString()` out,
  `XxxId.of(String)` back;
- a hand-written `InitializingBean` that copies annotation values off aggregate classes into the policy
  registries, because nothing in the framework does it;
- three beans (`PostgresqlClosingBooksGenerationRepository`, `TypedAggregateClosingBooksGenerationAccess`,
  `ClosingBooksCoordinator`) plus a fourth (`ClosingBooksLogicalAggregateRepository`) to stand up one
  closing-books aggregate;
- a duplicated `Optional.map(...).orElseGet(...)` block to build a repository that may or may not have a
  snapshot repository provider.

Every one of these is framework boilerplate that has leaked into application code. This document proposes moving
each into the framework.

### Root causes

**1. The serializer interface has a default for `String` and nothing else.**
`ClosingBooksIdSerializer.stringBased()` exists,
but the overwhelmingly common case in an Essentials application is not a raw `String` — it is a `SingleValueType`
(`TradingAccountId extends CharSequenceType<TradingAccountId>`). There is no factory for that, so every user hand-writes
the same anonymous class. `SingleValueType.fromObject(value, concreteType)` already performs exactly the reflective
construction required (constructor → static `of(...)` → static `from(...)`); it is simply not wired up here.

**2. Policy annotations on aggregate roots are inert.**
`AggregateClosingBooksPolicyBeanPostProcessor` and `AggregateSnapshotPolicyBeanPostProcessor` are `BeanPostProcessor`s.
They only observe **Spring beans**. An aggregate root is not a Spring bean and never should be — a singleton
`TradingAccount` is meaningless. So `@AggregateSnapshotPolicy` and `@AggregateClosingBooksPolicy` on an aggregate class
reach no registry, and the admin API's lifecycle endpoints report nothing.

This is the worst of the four problems because it fails *silently*. The demo works around it with a bespoke
`InitializingBean` and a 9-line comment explaining why it has to exist. Every consumer will hit this and most will not
notice; they will conclude the admin console is broken.

**3. Closing-books wiring is four objects with hand-threaded dependencies.**
The four objects have a fixed assembly order and mostly-derivable arguments, but the user assembles them by hand and
must know which serializer goes where. Notably `TypedAggregateClosingBooksGenerationAccess` — 30 lines of anonymous
class in the demo — carries no information that is not already available from the other three beans.

**4. `StatefulAggregateRepository` has no snapshot-provider-aware factory method.**
There is `from(...)` (no snapshots) and `fromUsingSnapshotRepositoryProvider(...)` (snapshots required). An application
that wants snapshots *when configured* must branch. The demo writes that branch twice, verbatim.

## What generalizes, and where it lives

The question worth settling before any code: which of these are framework concerns, and which are legitimately
application-specific?

| # | Proposal | Belongs in | Rationale |
|---|---|---|---|
| 1 | Serializer factories | `components/eventsourced-aggregates` (core, no Spring) | Pure function of the id type. Zero application knowledge required. Nothing about `TradingAccountId ↔ String` is domain-specific. |
| 2a | `EssentialsAggregateDeclarations` type | `components/eventsourced-aggregates` (core) | The `AggregateType → implementation class` pair is already the framework's own vocabulary — `AggregateClosingBooksGenerationAccess` and `AggregateSnapshotRepositoryProvider` are both keyed on exactly that pair. |
| 2b | Registration + optional scanning | `components/spring-boot-starter-postgresql-event-store` | Needs `ApplicationContext` lifecycle and property binding. |
| 3 | `ClosingBooksSetup` builder | `components/eventsourced-aggregates` (core) | Assembly of four framework objects. The only application input is the id types and the aggregate class. |
| 3b | Contributing setups to the access provider | starter | Spring wiring only. |
| 4 | `StatefulAggregateRepository` builder | `components/eventsourced-aggregates` (core) | Existing static factories already live on the interface; this is one more of the same. |

Everything in the "core" rows is plain Java with no Spring dependency, which matters: `eventsourced-aggregates` is
usable outside Spring, and the builders must not change that. (`eventsourced-aggregates` does already carry Spring as a
`provided` dependency for the two `BeanPostProcessor`s, so 2a could technically live anywhere — but the declaration type
itself should stay Spring-free so non-Spring users get the same registration path.)

What stays application-specific and is **not** in scope: the `ClosingBooksDecisionPolicy` itself, the
`TypedClosingBooksNextGenerationFactory` carry-forward logic, and the choice of `ClosingBooksStreamIdGenerator` format
where a non-default is wanted. Those are genuine domain decisions.

### Compatibility

Phase 0 is **breaking at the source level** — it deletes two interfaces. It is only acceptable because closing books has
not shipped to users yet: the two interfaces exist in no released artifact, so nothing external can reference them. It
carries no wire-format or persisted-data change (see Phase 0 below). Once closing books is released, a change of that
shape would have to wait for a new major.

Every change from Phase 1 onwards is **additive**: new factory methods, new types, new overloads. No existing signature
changes and no existing behaviour changes. This satisfies the "breaking changes only in a new major" rule, and means
those phases can ship independently and in any order.

## Phase 0 — One serializer interface (implemented)

**Module:** `components/eventsourced-aggregates`, package `...aggregates.closingbooks`.

`ClosingBooksLogicalAggregateIdSerializer<ID>` and `ClosingBooksStreamIdSerializer<STREAM_ID>` were the same shape — a
bidirectional `T ↔ String` mapping — and have been replaced by a single `ClosingBooksIdSerializer<ID>`:

```java
public interface ClosingBooksIdSerializer<ID> {
    String serialize(ID id);

    ID deserialize(String persistedId);

    default String serializeLogicalAggregateId(LogicalAggregateId<ID> logicalAggregateId) {
        return serialize(logicalAggregateId.value());
    }

    default LogicalAggregateId<ID> deserializeLogicalAggregateId(String persistedLogicalAggregateId) {
        return new LogicalAggregateId<>(deserialize(persistedLogicalAggregateId));
    }

    static ClosingBooksIdSerializer<String> stringBased();
}
```

The `LogicalAggregateId` wrapping that the logical-id variant used to perform — the one difference between the two
interfaces, and the original reason for leaving them apart — survives as the two `default` methods. It is now *derived*
rather than reimplemented per serializer: an implementation describes how one id value maps to and from its persisted
form, and nothing else. That is a strictly smaller obligation than before, where the logical-id variant made every
implementer unwrap and re-wrap by hand (each of the demo's two logical-id serializers did exactly
`logicalAggregateId.value().toString()` and `new LogicalAggregateId<>(XxxId.of(persisted))`).

Persisted form is unchanged. The old `stringBased()` serialized via `LogicalAggregateId.toString()`, which is
`value.toString()`; the new one serializes the value directly, producing the same string. Existing
`aggregate_generations` rows and generation stream ids stay readable.

Call sites updated: `PostgresqlClosingBooksGenerationRepository` (constructor parameter and the two column mappings),
`TypedAggregateClosingBooksGenerationAccess.logicalAggregateIdSerializer()`, and
`ClosingBooksLogicalAggregateRepository` (constructor parameter).

**Effect on the demo:** the two logical-id serializers — previously duplicated verbatim between the generation
repository and the generation access — collapse into a single private `tradingAccountIdSerializer()` helper shared by
both, which is what having one interface makes possible. The stream-id serializer stays for now; Phase 1 removes both.

**Why before Phase 1:** every phase below touches this surface. Phase 1 would otherwise have to add `of(...)` and
`forType(...)` twice and test them twice, and Phase 3's builder would need two id-serializer setters carrying two types
that mean the same thing.

## Phase 1 — Serializer factories

**Module:** `components/eventsourced-aggregates`, package `...aggregates.closingbooks`.

With Phase 0 done, the factories are written once, on `ClosingBooksIdSerializer`:

```java
static <ID> ClosingBooksIdSerializer<ID> of(Function<ID, String> serialize,
                                            Function<String, ID> deserialize);

static <ID> ClosingBooksIdSerializer<ID> forType(Class<ID> idType);
```

`forType(...)` resolves a strategy once, at construction time, and fails fast with an actionable message if it cannot:

| Id type | Serialize | Deserialize |
|---|---|---|
| `String` | identity | identity |
| `UUID` | `toString()` | `UUID.fromString(...)` |
| `enum` | `name()` | `Enum.valueOf(...)` |
| `SingleValueType` with a `CharSequence`/`String` value | `toString()` | `SingleValueType.fromObject(persisted, type)` |
| `SingleValueType` with a non-string value (`Long`, `Integer`, `BigDecimal`, `UUID`, …) | `value().toString()` | parse the string into the declared value type, then `SingleValueType.fromObject(parsedValue, type)` |
| anything else | `toString()` | reflective `of(String)` / `from(String)` / `(String)` constructor, via `Reflector` |

The non-string `SingleValueType` row is the one that needs real work and is the reason not to simply call
`SingleValueType.from(persistedValue, idType)` directly: for a `LongType`-backed id, `fromObject` would look for a
`Long`-arg constructor and be handed a `String`. The value type is recoverable by resolving the `SingleValueType`
generic parameter (`GenericType.resolveGenericTypeOnSuperClass(...)`, already used by
`StatefulAggregateRepository.from(...)` for the same purpose), so this is a known technique in the codebase rather than
new machinery.

Failing fast at `forType(...)` call time rather than at first deserialize matters: the first deserialize happens during a
generation resolve, potentially long after startup.

**Effect on the demo:** the two remaining anonymous classes (roughly 20 lines, including the
`tradingAccountIdSerializer()` helper Phase 0 introduced) collapse to
`ClosingBooksIdSerializer.forType(TradingAccountId.class)` and
`ClosingBooksIdSerializer.forType(TradingAccountGenerationId.class)`.

**Tests:** unit only, no Docker. One case per row of the table above, plus round-trip properties
(`deserialize(serialize(x)).equals(x)`) over the existing test id fixtures in `types/src/test` — `CustomerId`
(`CharSequenceType`), `OrderId` (`LongType`), and an enum and a `UUID` case. Plus the failure case: a type with no usable
construction path produces a message naming the type and the three shapes that were searched for.

## Phase 2 — Aggregate declarations and policy registration

This is the phase that fixes a silent-failure bug, so it is the highest value of the four.

### 2a. The declaration type (core)

```java
public record AggregateDeclaration(AggregateType aggregateType,
                                   Class<?> aggregateImplementationType) { }

public final class EssentialsAggregateDeclarations {
    public static EssentialsAggregateDeclarationsBuilder builder();
    public List<AggregateDeclaration> declarations();
}
```

Usage:

```java
@Bean
EssentialsAggregateDeclarations tradingAggregates() {
    return EssentialsAggregateDeclarations.builder()
                                          .declare(TRADING_ACCOUNTS,  TradingAccount.class)
                                          .declare(INSTRUMENT_PRICES, InstrumentPrice.class)
                                          .declare(SETTLEMENTS,       Settlement.class)
                                          .declare(TRADES,            Trade.class)
                                          .declare(INSTRUMENTS,       Instrument.class)
                                          .build();
}
```

Follows the project builder convention (`builder()` → `build()`), with `declare(...)` rather than `setXxx(...)` because it
is repeatable and additive rather than a property setter.

### 2b. Registration (starter)

An `AggregateDeclarationPolicyRegistrar` in `spring-boot-starter-postgresql-event-store` that takes
`List<EssentialsAggregateDeclarations>`, both policy registries, and on `afterPropertiesSet()`:

- reads `@AggregateSnapshotPolicy` off each declared implementation class via `AnnotationUtils.findAnnotation` and, when
  present, registers an `AggregateSnapshotPolicyDescriptor`;
- does the same for `@AggregateClosingBooksPolicy` → `AggregateClosingBooksPolicyDescriptor`;
- resolves the descriptor's aggregate-type string as: the annotation's own `aggregateType()` attribute when non-blank,
  otherwise the `AggregateType` from the declaration. (Both annotations already carry `aggregateType()`; today it is the
  only way a `BeanPostProcessor`-registered descriptor gets one.)

This is *exactly* what the demo's `tradingAccountPolicyRegistrations` bean does by hand, generalized.

**Ordering — the one real risk in this plan.** `DefaultAggregateLifecycleConfigurationValidator` implements
`SmartInitializingSingleton` and validates the registry contents in `afterSingletonsInstantiated()`. If the registrar were
also a `SmartInitializingSingleton`, ordering would depend on singleton registration order, and a registrar that ran second
would let validation pass over an empty registry — reintroducing the silent failure this phase exists to remove.

Fix: make the registrar an `InitializingBean`. **That alone is what orders it**, and the reason is a phase boundary
rather than a dependency: `afterPropertiesSet()` runs while singletons are still being created, and *every*
`SmartInitializingSingleton` callback fires only after that phase completes. So the registries are always populated
before the validator validates, whatever order the two beans were registered in.

The registrar is also injected as an unused parameter of the existing `aggregateLifecycleConfigurationValidator(...)`
`@Bean` method, but — verified during implementation, by removing the parameter and watching the tests stay green — that
is belt and braces, not the mechanism. It keeps the dependency visible in the wiring and preserves correctness if the
validator ever moves its checks into its constructor. The plan originally credited the parameter with providing the
ordering; it does not.

**Tests that lock this in:**

- an `ApplicationContextRunner` case that declares an aggregate whose annotation is deliberately invalid
  (`defaultPolicy = TIME_BOUNDARY` with `timeBoundary = NONE`) and asserts the context **fails to start**. If
  registration ever regresses to running after validation, the validator sees an empty registry and startup succeeds —
  so the test must assert the failure, not the success. Note this needs a `TypedClosingBooksNextGenerationFactory` bean
  and `ON_ACCESS` to get past the two checks the validator performs *before* the time-boundary one;
- its mirror image: the same invalid aggregate, *undeclared*, starts up fine — which is the silent failure being removed;
- a `SmartInitializingSingleton` observer bean asserting it sees the registered policy when its callback fires, i.e. the
  phase-boundary invariant itself rather than one symptom of it.

### 2c. Optional classpath scanning (follow-up, not first cut)

```properties
essentials.eventstore.aggregates.scan-packages=dk.trustworks.essentials.examples.trading
```

Backed by `ClassPathScanningCandidateComponentProvider` with an `AnnotationTypeFilter` for each of the two policy
annotations, contributing an additional `EssentialsAggregateDeclarations` to the same registrar. An aggregate found by
scanning must carry `aggregateType()` on its annotation, since there is no declaration to fall back to; one that does not
is a startup error naming the class.

**Recommendation: ship 2a + 2b first, 2c later, and keep the map as the documented path.** Reasons:

- the explicit map is needed regardless — Phase 3's builder consumes the same `(AggregateType, impl class)` pair, and the
  `AggregateType` constants already live next to the configuration;
- a scan that silently misses a class fails in exactly the same shape as the bug being fixed;
- scanning cost and native-image friendliness are both real considerations for a framework that currently requires
  neither.

Scanning is a genuine convenience for large applications, so it should exist — just not as the only mechanism.

## Phase 3 — `ClosingBooksSetup` builder

**Module:** `components/eventsourced-aggregates`, package `...aggregates.closingbooks`.

```java
public class ClosingBooksSetup<LOGICAL_ID, STREAM_ID> {
    public static <L, S> ClosingBooksSetupBuilder<L, S> builder(AggregateType aggregateType,
                                                                Class<?> aggregateImplementationType);

    public ClosingBooksGenerationRepository<LOGICAL_ID>             generationRepository();
    public ClosingBooksCoordinator<LOGICAL_ID>                      coordinator();
    public TypedAggregateClosingBooksGenerationAccess<LOGICAL_ID>   generationAccess();

    public <EVENT_TYPE, AGGREGATE extends StatefulAggregate<STREAM_ID, EVENT_TYPE, AGGREGATE>>
    ClosingBooksLogicalAggregateRepository<LOGICAL_ID, STREAM_ID, EVENT_TYPE, AGGREGATE>
    logicalAggregateRepository(StatefulAggregateRepository<STREAM_ID, EVENT_TYPE, AGGREGATE> delegate);
}
```

Builder setters, following the `setXxx(...)` convention:

| Setter | Required | Default |
|---|---|---|
| `setLogicalAggregateIdType(Class<LOGICAL_ID>)` | one of these two | — |
| `setLogicalAggregateIdSerializer(...)` | one of these two | — |
| `setStreamIdType(Class<STREAM_ID>)` | one of these two | — |
| `setStreamIdSerializer(...)` | one of these two | — |
| `setUnitOfWorkFactory(...)` | yes, unless `setGenerationRepository` given | — |
| `setGenerationRepository(...)` | no | `PostgresqlClosingBooksGenerationRepository` on the given UoW factory |
| `setGenerationRepositoryTableName(Optional<String>)` | no | repository default |
| `setStreamIdGenerator(...)` | no | `(type, id, gen) -> id.value() + "#" + gen` |
| `setClock(Clock)` | no | `Clock.systemUTC()` |
| `setMeterRegistry(Optional<MeterRegistry>)` | no | `Optional.empty()` |

The `*IdType` setters route through Phase 1's `forType(...)`, so Phase 3 depends on Phase 1 for its ergonomics (it still
works without it via the explicit-serializer setters). Both `*IdSerializer` setters take a `ClosingBooksIdSerializer`
after Phase 0 — the two setters stay separate because they name two distinct roles, not two types.

Two things worth calling out:

- **`generationAccess()` is derived, never written by the user.** `TypedAggregateClosingBooksGenerationAccess` needs only
  the aggregate type, the implementation class, the generation repository and the logical-id serializer — the builder holds
  all four. The demo's 30-line anonymous class disappears entirely.
- **The default stream-id generator becomes framework-owned.** `id + "#" + generation` is what the demo uses and is a
  reasonable default; promoting it means a user who does not care never names it. A user who does care still sets it, and
  anyone with existing persisted stream ids in another format must keep setting it — that is why it is a documented default
  rather than a silent one.

### Spring wiring

One user bean instead of three:

```java
@Bean
ClosingBooksSetup<TradingAccountId, TradingAccountGenerationId> tradingAccountClosingBooks(
        HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
        Optional<MeterRegistry> meterRegistry) {
    return ClosingBooksSetup.<TradingAccountId, TradingAccountGenerationId>builder(TRADING_ACCOUNTS, TradingAccount.class)
                            .setLogicalAggregateIdType(TradingAccountId.class)
                            .setStreamIdType(TradingAccountGenerationId.class)
                            .setUnitOfWorkFactory(unitOfWorkFactory)
                            .setMeterRegistry(meterRegistry)
                            .build();
}
```

For the admin API to keep working, the setups' `generationAccess()` must reach
`AggregateClosingBooksGenerationAccessProvider`. Today `AggregateLifecycleApiConfiguration` builds
`CachingAggregateClosingBooksGenerationAccessProvider` from
`ObjectProvider<TypedAggregateClosingBooksGenerationAccess<?>>`. Extend that `@Bean` to also take
`ObjectProvider<ClosingBooksSetup<?, ?>>` and concatenate `setup.generationAccess()` into the accessor list. Existing
applications that register accessors directly are unaffected.

The `ClosingBooksLogicalAggregateRepository` stays a user bean — it needs the user's `StatefulAggregateRepository` — but
becomes a one-liner: `setup.logicalAggregateRepository(tradingAccountStreamRepository)`.

**Tests:** unit tests for the builder (defaults applied, missing-required-setter messages, derived `generationAccess()`
carries the right aggregate type and implementation class) reusing the existing
`closingbooks/InlineUnitOfWorkFactories.java` doubles, so no Docker. One IT proving a builder-assembled setup rolls a
generation end-to-end against Postgres — the existing `PostgresqlClosingBooksGenerationRepositoryIT` is the model.

## Phase 4 — `StatefulAggregateRepository` builder

**Module:** `components/eventsourced-aggregates`, package `...aggregates.stateful`.

Two pieces, and the first is worth having even if the second is deferred.

**4a. The missing overload** — absorbs the branch the demo writes twice:

```java
static <CONFIG extends AggregateEventStreamConfiguration, ID, EVENT_TYPE, AGGREGATE_IMPL_TYPE extends StatefulAggregate<ID, EVENT_TYPE, AGGREGATE_IMPL_TYPE>>
StatefulAggregateRepository<ID, EVENT_TYPE, AGGREGATE_IMPL_TYPE> from(ConfigurableEventStore<CONFIG> eventStore,
                                                                      AggregateType aggregateType,
                                                                      StatefulAggregateInstanceFactory aggregateRootInstanceFactory,
                                                                      Class<AGGREGATE_IMPL_TYPE> aggregateImplementationType,
                                                                      Optional<AggregateSnapshotRepositoryProvider> snapshotRepositoryProvider);
```

Roughly five lines of implementation: present → delegate to `fromUsingSnapshotRepositoryProvider`, empty → delegate to the
existing `from`. Removes both duplicated blocks in the demo immediately.
all
**4b. The builder** — better surface as the option set grows (the interface already carries six `from`-family overloads
distinguished only by argument list, which is the usual signal that a builder is overdue):

```java
StatefulAggregateRepository.builder(eventStore)
                           .setAggregateType(TRADING_ACCOUNTS)
                           .setAggregateImplementationType(TradingAccount.class)
                           .setAggregateSnapshotRepositoryProvider(snapshotRepositoryProvider)  // Optional-aware
                           .build();
```

Defaults: `aggregateRootInstanceFactory` → `reflectionBasedAggregateRootFactory()`; `aggregateIdType` → resolved from the
implementation type's generic parameters, exactly as the existing `from(...)` overload does, with
`setAggregateIdType(...)` available when that resolution fails; snapshot provider → empty. Either `setAggregateType(...)`
or `setEventStreamConfiguration(...)` must be given.

**Tests:** unit tests asserting the builder produces a repository equivalent to each existing `from(...)` overload, and
that an empty `Optional` provider yields a repository with no snapshot repository attached.

## Effect on the demo

`TradingDemoAggregateConfiguration` was 268 lines; it is **161** after all the phases, with no anonymous classes and no
`InitializingBean`. The estimate of ~90 was optimistic: it counted the bean bodies but not the ~30 lines of imports, the
license header, or the javadoc explaining the two non-obvious beans. The functional shrink is the real number — five
repository beans, one declarations bean, one closing-books setup, and two beans that publish parts of that setup.

The three simple repositories also moved to `StatefulAggregateRepository.builder(...)`, which is line-neutral against
`from(...)` but drops the explicit `reflectionBasedAggregateRootFactory()` argument, since that is the builder's default.

Original line spans:

| Removed | Replaced by |
|---|---|
| `tradingAccountPolicyRegistrations`, L76–103 | one `EssentialsAggregateDeclarations` bean (Phase 2) |
| `tradingAccountGenerationRepository`, L105–121 | derived by `ClosingBooksSetup` (Phase 3) |
| `tradingAccountClosingBooksGenerationAccess`, L123–157 | derived by `ClosingBooksSetup` (Phase 3) |
| `tradingAccountClosingBooksCoordinator`, L159–173 | derived by `ClosingBooksSetup` (Phase 3) |
| two `Optional.map(...).orElseGet(...)` blocks, L179–190 and L255–266 | one `from(..., Optional)` call each (Phase 4a) |
| three anonymous serializers, L110–120, L144–154, L200–210 | one shared helper (Phase 0), then `forType(...)` (Phase 1) |

The serializer spans sit *inside* the bean spans above them, so the rows overlap and do not sum.

That the demo shrinks is the point, but it is also the honest test of the design: if a builder cannot express what the
demo does today, it is not ready.

## Documentation

The consumer-facing docs must move with the code, or the boilerplate stays in circulation:

- `LLM/LLM-eventsourced-aggregates.md` — the new factories, builders, and the declaration mechanism, with the
  declaration bean shown as *the* way to make policy annotations take effect.
- `components/eventsourced-aggregates/CLAUDE.md` — extend the "Extension Points" table; the gotchas list should gain the
  fact that annotations on aggregate roots require a declaration (replacing the current silent trap).
- `README.md` — the closing-books section, if it shows the four-bean assembly.

## Verification

- `mvn test -pl components/eventsourced-aggregates -am` — all new core unit tests, no Docker.
- `mvn verify -pl components/spring-boot-starter-postgresql-event-store -am` — registration ordering and the
  fails-to-start assertion.
- `mvn -Pjackson2 test` — Phase 1's reflective construction touches no serialization, but `SingleValueType` reflection is
  shared with the Jackson flavors; cheap to confirm.
- Build and run the trading demo against the migrated configuration, and confirm the admin console's closing-books
  statistics and lifecycle endpoints still report the policies — that is the behaviour Phase 2 is protecting.

## Out of scope

- `ClosingBooksStatefulAggregateRepository` (the `String`-stream-id variant). It has a three-argument constructor and no
  observed boilerplate problem.
- Anything touching `ClosingBooksDecisionPolicy`, `BuiltInClosingBooksPolicyEvaluator`, or the scheduled-scan path.
  Those are configuration semantics, not assembly ergonomics.
- Auto-registering `ClosingBooksLogicalAggregateRepository` as a bean. It depends on a user-supplied
  `StatefulAggregateRepository`; deriving it would mean the framework guessing at bean identity across four type
  parameters.
