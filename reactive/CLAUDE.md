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
| `AbstractCommandBus` | Maintains `commandHandlers` set + `ConcurrentMap` type→handler cache (cleared on add/remove); runs interceptor chain via `CommandBusInterceptorChain.newInterceptorChain` |
| `LocalCommandBus` | Concrete `AbstractCommandBus`; `sendAndDontWait` = fire-and-forget via `Mono.fromCallable` on `boundedElastic`; delayed send via single-thread `ScheduledExecutorService` |
| `AnnotatedCommandHandler` | Reflects `@Handler`/`@CmdHandler` methods at construction; caches command-type→`Method`; `canHandle` uses `isAssignableFrom`; invokes via `Method.invoke`, sneaky-throws target exception |
| `ReactiveHandlersBeanPostProcessor` | `DestructionAwareBeanPostProcessor`; auto-registers all `EventHandler`/`CommandHandler` beans post-init; uses `@AsyncEventHandler` annotation to choose sync vs async registration; skips `ROLE_INFRASTRUCTURE` beans |
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
- `AnnotatedCommandHandler.getExceptionLogLevel(Exception)` — override to suppress or change log level per exception type

## Gotchas

- **Async subscriber concurrency = 1 per subscriber** — `flatMap(..., 1)` keeps per-handler ordering; events are not processed in parallel within one subscriber
- **Sync subscribers on caller thread** — exceptions propagate synchronously back to `publish()` caller; async subscriber exceptions go to `OnErrorHandler`
- **Overflow retry uses `LockSupport.parkNanos`** — blocking retry on the calling thread with exponential backoff (max 1s); after `overflowMaxRetries` → `EventPublishOverflowException` via `OnErrorHandler`
- **`FAIL_NON_SERIALIZED` never counts toward retry limit** — retried indefinitely until it succeeds (Reactor thread-safety signal)
- **Command-type cache cleared on every add/remove** — `commandTypeToCommandHandlerCache.clear()` on `addCommandHandler`/`removeCommandHandler`; safe but causes re-resolution burst on hot paths
- **`LocalCommandBus.sendAndDontWait` is non-durable** — fire-and-forget on `boundedElastic`; no persistence, no retry on JVM crash
- **`ReactiveHandlersBeanPostProcessor` resolves `EventBus`/`CommandBus` lazily** — first handler bean post-processed triggers `getBeansOfType`/`getBean`; ensure buses are defined as beans before handlers to avoid circular issues
- **`AnnotatedCommandHandler` uses `isAssignableFrom`** — handles command subclasses; multiple matching methods → `MultipleCommandHandlersFoundException` at resolution time, not registration time
- **`@Handler` and `@CmdHandler` both work** on `AnnotatedCommandHandler`; only `@Handler` works on `AnnotatedEventHandler`
