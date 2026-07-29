# kotlin-eventsourcing

Kotlin-native functional event sourcing using Decider/Evolver patterns. Maven: `kotlin-eventsourcing`. Status: WORK-IN-PROGRESS / experimental API.

## Package Structure

| Package | Contents |
|---------|----------|
| `dk.trustworks.essentials.components.kotlin.eventsourcing` | Core interfaces: `Decider`, `Evolver`, `AggregateTypeConfiguration`, `EventOutOfOrderException` |
| `dk.trustworks.essentials.components.kotlin.eventsourcing.adapters` | Spring/infrastructure wiring: `DeciderCommandHandlerAdapter`, `DeciderAndAggregateTypeConfigurator` |
| `dk.trustworks.essentials.components.kotlin.eventsourcing.test` | In-module test harness: `GivenWhenThenScenario` and assertion exceptions |

## Key Classes

| Class | Role |
|-------|------|
| `Decider<CMD, EVENT>` | Interface: `handle(cmd, events) -> EVENT?`. One event out or null (idempotent); throw on invariant violation. `canHandle` guards dispatch. |
| `Evolver<EVENT, STATE>` | `fun interface`: `applyEvent(event, state?) -> STATE?`. Left-fold over event stream. Companion holds `applyEvents`, `extractEvents`, `extractEventsAsList` helpers. |
| `AggregateTypeConfiguration` | Data class binding `AggregateType` + id type + serializer + `DeciderSupportsAggregateTypeChecker` + id resolvers. Registered as Spring Bean. |
| `DeciderSupportsAggregateTypeChecker` | `fun interface` deciding if a `Decider` works with a given `AggregateType`. Concrete impl: `HandlesCommandsThatInheritsFromCommandType` — uses `GenericType.resolveGenericTypeForInterface` to introspect Decider's CMD type arg. |
| `DeciderCommandHandlerAdapter` | Bridges `Decider` → `CommandHandler`. On `handle`: loads event stream via `eventStore.fetchStream`, deserializes events, calls `decider.handle`, appends resulting event. Resolves aggregate-id from command first, falls back to event if null. |
| `DeciderAndAggregateTypeConfigurator` | Spring init bean: registers all `AggregateTypeConfiguration`s with `ConfigurableEventStore`, wraps all `Decider`s in `DeciderCommandHandlerAdapter` and registers on `CommandBus`. Fails fast if decider matches 0 or >1 aggregate types. |
| `EventOutOfOrderException` | Thrown by view/processor code when event arrives with unexpected `eventOrder`. |
| `GivenWhenThenScenario<CMD, EVENT>` | Pure in-memory test harness for `Decider`s. Chainable: `given(...).when_(cmd).then_(event)` / `thenExpectNoEvent()` / `thenAssert { }` / `thenFailsWithException` / `thenFailsWithExceptionType`. No EventStore, no Spring. |
| `CommandAggregateIdResolver` | Typealias `(cmd: Any) -> Any?`. May return null for create-style commands (id extracted from emitted event instead). |
| `EventAggregateIdResolver` | Typealias `(event: Any) -> Any`. Never null; used as fallback when command carries no id. |

## Test Structure

- One test file: `GivenWhenThenScenarioTest` — pure unit tests, no Docker, no DB.
- Pattern: instantiate `GivenWhenThenScenario(MyDecider())`, chain `given/when_/then_`.
- `GivenWhenThenScenario` lives in `src/main` (package `...test`) so consumers can use it in their own test suites without a test-scoped dep.
- All assertion failures are subclasses of `AssertionException` (not JUnit-specific).

## Extension Points

- `Decider<CMD, EVENT>` — implement per command type (one command → one decider recommended).
- `Evolver<EVENT, STATE>` — implement for state reconstruction; wire manually or compose with `Evolver.applyEvents`.
- `DeciderSupportsAggregateTypeChecker` — `fun interface`; implement custom matching logic if `HandlesCommandsThatInheritsFromCommandType` doesn't fit your command hierarchy.
- `CommandAggregateIdResolver` / `EventAggregateIdResolver` — lambdas; supply in `AggregateTypeConfiguration`.

## Gotchas

- `Decider.handle` receives the **full raw event stream** (all past events for aggregate), not a reconstructed state object. Deciders must fold/search the list themselves or delegate to an `Evolver`.
- Idempotency contract: duplicate command → return `null`, never throw. Violation breaks at-least-once processing.
- `DeciderCommandHandlerAdapter.canHandle` matches on **exact command class** (not `isAssignableFrom`). Each concrete command type needs its own `Decider` registered.
- `DeciderAndAggregateTypeConfigurator` throws `IllegalStateException` at startup if any `Decider` matches 0 or >1 `AggregateTypeConfiguration`s — misconfigured checker surfaces immediately.
- `Evolver.extractEvents` / `extractEventsAsList` use `reified` type params → must be called from inline/Kotlin context; not callable from Java.
- `Evolver.applyEvents` calls `!!` on fold result — throws `NullPointerException` if evolver returns `null` for the final state. Ensure evolver never returns null after all events applied.
- `GivenWhenThenScenario` is single-use (state accumulates in fields). Instantiate fresh per scenario.
- `thenFailsWithException` compares by class AND message string equality (no custom comparator yet — see TODO in source).
- Aggregate-Id security: never accept raw user input as aggregate-id without validation; used in SQL string concatenation in event store.
