# reactive

In-process event bus + command bus for JVM-local messaging. Maven: `essentials-reactive`.

## Package Structure

| Package | Contents |
|---------|----------|
| `dk.trustworks.essentials.reactive` | `EventBus`, `LocalEventBus`, `EventHandler`, `AnnotatedEventHandler`, `Handler`, `OnErrorHandler` |
| `dk.trustworks.essentials.reactive.command` | `CommandBus`, `LocalCommandBus`, `AbstractCommandBus`, `CommandHandler`, `AnnotatedCommandHandler`, `CmdHandler`, `SendAndDontWaitErrorHandler` + exception types |
| `dk.trustworks.essentials.reactive.command.interceptor` | `CommandBusInterceptor`, `CommandBusInterceptorChain` |
| `dk.trustworks.essentials.reactive.spring` | `ReactiveHandlersBeanPostProcessor`, `AsyncEventHandler` annotation |

## Key Classes

| Class | Internal role |
|-------|---------------|
| `LocalEventBus` | `Sinks.Many` multicast with `onBackpressureBuffer`; single shared `Flux` fan-out to per-subscriber `flatMap` chains; sync subscribers called inline on publisher thread |
| `AnnotatedEventHandler` | Wraps `PatternMatchingMethodInvoker` + `SingleArgumentAnnotatedMethodPatternMatcher`; dispatches to most-specific `@Handler` method; unmatched events silently ignored |
| `AbstractCommandBus` | Maintains `commandHandlers` (`CopyOnWriteArraySet`, registration order) + `ConcurrentMap` type→handler cache; add/remove change both under `commandHandlerRegistrationLock`'s write lock, a cache miss resolves under its read lock; WARNs on add when `findCommandTypesHandledByMoreThanOneCommandHandler` finds an overlap; runs interceptor chain via `CommandBusInterceptorChain.newInterceptorChain` |
| `LocalCommandBus` | Concrete `AbstractCommandBus`; `sendAndDontWait` = fire-and-forget via `Mono.fromCallable` on `boundedElastic`; delayed send via single-thread `ScheduledExecutorService` |
| `AnnotatedCommandHandler` | Reflects `@Handler`/`@CmdHandler` methods at construction; caches command-type→`Method`; `canHandle` uses `isAssignableFrom`; invokes via `Method.invoke`, sneaky-throws target exception |
| `ReactiveHandlersBeanPostProcessor` | `DestructionAwareBeanPostProcessor`; auto-registers all `EventHandler`/`CommandHandler` beans post-init; uses `@AsyncEventHandler` annotation to choose sync vs async registration; skips `ROLE_INFRASTRUCTURE` beans |
| `CommandContextPropagator` | SPI: called on the **sending** thread for `sendAsync`/`sendAndDontWait`, returns the invocation that restores captured context around the handler on the Reactor worker. `AbstractCommandBus.addContextPropagator`; first added = outermost. `foundation` supplies `CausationCommandContextPropagator` |
| `CommandBusInterceptorChain` | Chain-of-responsibility; interceptors sorted by `@Order`; separate intercept methods for `send`/`sendAsync`/`sendAndDontWait` |

## Test Structure

- No Docker/Testcontainers needed — pure unit tests
- `LocalEventBusTest` — uses `Awaitility` to assert async delivery; creates/stops bus per test in `@AfterEach`
- `AnnotatedEventHandlerTest` / `AnnotatedCommandHandlerTest` — reflection dispatch and type matching
- `LocalCommandBusTest` — interceptor ordering, error paths, `sendAndDontWait` fire-and-forget
- `DefaultCommandBusInterceptorChainTest` — chain ordering and proceed semantics
- `ReactiveHandlersBeanPostProcessorTest` — Spring context wiring with `ReactiveHandlersConfiguration`

## Extension Points

- `EventHandler` — single-method interface; implement directly or extend `AnnotatedEventHandler`
- `CommandHandler` — implement `canHandle(Class<?>)` + `handle(Object)` directly, or extend `AnnotatedCommandHandler`
- `CommandBusInterceptor` — all three default methods (`interceptSend`, `interceptSendAsync`, `interceptSendAndDontWait`); implement any subset; ordering via `@Order`
- `OnErrorHandler` — async subscriber error callback; receives subscriber, event, exception
- `SendAndDontWaitErrorHandler` — fire-and-forget error callback on `LocalCommandBus`
- `AnnotatedCommandHandler.getExceptionLogLevel(Exception)` — override to suppress or change log level per exception type. Default DEBUG since 0.61 (was ERROR + stack, then rethrow → every failure logged twice, per retry on a durable bus; ~6 000 stack traces/2 min in the trading demo). The sender reports it: `send` throws, `SendAndDontWaitErrorHandler` logs ERROR, durable queue keeps `last_delivery_error` + ERROR on dead letter

## Gotchas

- **Async subscriber concurrency = 1 per subscriber** — `flatMap(..., 1)` keeps per-handler ordering; events are not processed in parallel within one subscriber
- **Sync subscribers on caller thread** — exceptions propagate synchronously back to `publish()` caller; async subscriber exceptions go to `OnErrorHandler`
- **Overflow retry uses `LockSupport.parkNanos`** — blocking retry on the calling thread with exponential backoff (max 1s); after `overflowMaxRetries` → `EventPublishOverflowException` via `OnErrorHandler`
- **`FAIL_NON_SERIALIZED` never counts toward retry limit** — retried indefinitely until it succeeds (Reactor thread-safety signal)
- **Command-type cache cleared on every add/remove** — `commandTypeToCommandHandlerCache.clear()` on `addCommandHandler`/`removeCommandHandler`; safe but causes re-resolution burst on hot paths. The clear and a cache-miss resolution are ordered by `commandHandlerRegistrationLock` (write vs read) — without it a resolution in flight during a registration stayed cached with the pre-registration answer. Pinned by `LocalCommandBusConcurrentRegistrationTest`
- **Duplicate-handler WARN only sees declared types** — `CommandHandler.handledCommandTypes()` (default empty) supplies the candidate types, `canHandle` decides; an overlap on a type no handler declares is not found. Runs outside the lock after the add; its own failure logs ERROR and never fails the registration. Pinned by `LocalCommandBusDuplicateCommandHandlerTest`
- **`sendAndDontWait`/`sendAsync` run the handler on `boundedElastic`, not the caller** — `publishOn` fuses with `Mono.fromCallable` and pulls the callable onto the worker (only `send` runs on the caller). ThreadLocal/ScopedValue context is lost unless carried by a `CommandContextPropagator`; propagation must be captured at send (`propagateContext(...)` on the sending thread), never inside the callable
- **`sendAndDontWait`'s `SendAndDontWaitErrorHandler` runs after the interceptor chain unwinds (since 0.60.1)** — the innermost step records the handler's exception and rethrows it, so a `UnitOfWork` interceptor rolls back; the bus then calls the error handler once with that recorded original (not an interceptor's wrapper). Calling it inside the chain committed partial writes whenever it didn't rethrow. A failure with nothing recorded (interceptor's own) bypasses it. `DurableLocalCommandBus` (foundation) mirrors this; pinned by `LocalCommandBusSendAndDontWaitErrorTest` + foundation's `SendAndDontWaitHandlerFailureRollbackIT`
- **`LocalCommandBus.sendAndDontWait` is non-durable** — fire-and-forget on `boundedElastic`; no persistence, no retry on JVM crash
- **`ReactiveHandlersBeanPostProcessor` resolves `EventBus`/`CommandBus` lazily** — first handler bean post-processed triggers `getBeansOfType`/`getBean`; ensure buses are defined as beans before handlers to avoid circular issues
- **`AnnotatedCommandHandler` uses `isAssignableFrom`** — handles command subclasses; multiple matching methods → `MultipleCommandHandlersFoundException` at resolution time, not registration time
- **`@Handler` and `@CmdHandler` both work** on `AnnotatedCommandHandler`; only `@Handler` works on `AnnotatedEventHandler`
