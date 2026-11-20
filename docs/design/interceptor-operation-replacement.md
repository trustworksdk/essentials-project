# Interceptors that change what they intercept

Status: being implemented (steps in *Steps*).

## Problem

An interceptor should be able to change the input of the operation it intercepts before the rest of the chain and the
default behaviour see it: enrich a payload, decrypt it on the way in, encrypt it on the way out, rewrite metadata.
Some interceptor families already allow this, some do not, and the ones that do not fail silently: an interceptor
that builds a new `Message` has nowhere to put it, so the handler gets the original.

The blocker is rarely the immutability of `Message` itself. Immutable value objects are fine as long as there is
somewhere to put the replacement and the code downstream reads from that place. In the families that fail, the
default behaviour reads a local variable captured before the chain was built, so even a mutable operation would be
ignored.

## Inventory

Five interceptor families, all built on `shared`'s interceptor concept (`Interceptor`, `InterceptorChain`,
`InterceptorOrder`), with two families carrying their own chain type.

| Family | Chain | Operation(s) | Input replaceable today? | Why not |
|---|---|---|---|---|
| `DurableQueuesInterceptor` (foundation) | `InterceptorChain` | `QueueMessage`, `QueueMessages`, `ConsumeFromQueue`, ... | **Yes** for enqueue: `QueueMessage.setMessage`, `QueueMessages.setMessages`, `ConsumeFromQueue.setRedeliveryPolicy`; the default behaviour reads `operation.getMessage()` after the chain ran (`PostgresqlDurableQueues:993`, `MongoDurableQueues:499`) | — |
| | | `HandleQueuedMessage` | **No** | `public final QueuedMessage message`, no setter; all three default behaviours call the handler with the captured local (`DefaultDurableQueueConsumer:422`, `CentralizedMessageFetcher:432`, `ShardOwnedDurableQueues:736`) |
| `MessageHandlerInterceptor` (foundation, Inbox/Outbox `PatternMatchingMessageHandler`) | `InterceptorChain` | `InvokeMessageHandlerMethod` | **No** | `public final` fields; default behaviour invokes the method with captured `payload`/`message` (`PatternMatchingMessageHandler:296-301`); the method to invoke is resolved from the payload type *before* the chain is built |
| `EventStoreInterceptor` (postgresql-event-store) | `EventStoreInterceptorChain` | `AppendToStream`, `FetchStream`, `LoadEventsByGlobalOrder` | **Yes**: `setEventsToAppend`, `setEventOrderRange`, `setTenant`, ...; default behaviour reads the operation (`PostgresqlEventStore:315-321`, `:419-425`) | — |
| | | `LoadEvent`, `LoadEvents`, `LoadEventsCausedBy`, `LoadLastPersistedEventRelatedTo` | No (query keys only, `public final`) | Not needed for payload work — results are what gets decrypted, and results are already replaceable |
| `CommandBusInterceptor` (reactive) | `CommandBusInterceptorChain` | none — the command is passed as `Object` | **No** | `DefaultCommandBusInterceptorChain.command` is `final`; invokers pass the captured local to `interceptSend…` (`AbstractCommandBus:228`, `:242`, `LocalCommandBus:89`, `DurableLocalCommandBus:236`); the handler is matched *before* the chain; `DurableLocalCommandBus`'s default behaviour calls `commandHandler.handle(command)` with the captured command, not the chain's (`:241`) |
| `MessageQueueInterceptor` (postgresql-queue-shard-owned) | `InterceptorChain` | `EnqueueMessages` | **Yes**: `setMessages` | — |
| | | `HandleMessage` | **No** | Shipped in 0.60.0 as a `record`; default behaviour uses captured locals (`PostgresqlMessageQueue:1485-1491`) |

Results are replaceable everywhere already: every interceptor method returns the chain's result, so a read-side
interceptor can return a decrypted `Optional<QueuedMessage>`, `List<PersistedEvent>` or `Stream<PersistedEvent>`.
What is missing there is only the means to build the replacement value cheaply (see *Value-object helpers*).

## What blocks replacement, in order of importance

1. **Captured locals.** Invoker lambdas and default behaviours close over the variables the call site had before
   the chain existed. This is the root cause; fixing the operation types alone changes nothing.
2. **Dispatch happens before the chain.** `PatternMatchingMessageHandler` chooses the `@MessageHandler` method from
   the payload's class, and the command bus chooses the `CommandHandler` from the command's class, both before any
   interceptor runs. A replacement of a *different type* (an `EncryptedPayload` decrypted into `OrderPlaced`) would
   be delivered to the method or handler chosen for the old type.
3. **Operations carry output state, not just input.** `HandleQueuedMessage.acknowledgedWithHandler` is set by an
   inner interceptor and read by the consumer on the *original* operation object after the chain returns
   (`DefaultDurableQueueConsumer:441`, `CentralizedMessageFetcher:454-465`). Any design that replaces the operation
   object with a copy for the inner part of the chain loses that state: the consumer reads `null` and takes the
   wrong acknowledge path.
4. **No copy helpers on the immutable carriers.** `Message` (a `Pair`), `OrderedMessage` (adds `key`/`order`),
   `QueuedMessage` (an interface with three implementations) and `PersistedEvent` all have to be rebuilt by hand,
   and a hand rebuild of an `OrderedMessage` as `Message.of(...)` silently drops its ordering key.
5. **`HandleMessage` is a shipped record.** A record cannot gain a mutable component, and turning it into a class
   breaks record deconstruction patterns and `Record`-typed code.

## Options considered

**A. The operation is the mutable envelope (recommended).** Every operation that carries a payload exposes a setter
for it. An interceptor replaces the payload *in place* on the operation it was given, then calls `proceed()`. The
default behaviour and every later interceptor read the operation, never a captured local. The payload values
themselves stay immutable; only the slot on the operation changes.

- It is the pattern the code base already uses for six operations (`QueueMessage`, `QueueMessages`,
  `ConsumeFromQueue`, `AppendToStream`, `FetchStream`, `LoadEventsByGlobalOrder`, `EnqueueMessages`). Users who
  write a queue or event-store interceptor already know it.
- Operation identity is preserved, so output state such as `acknowledgedWithHandler` keeps working (blocker 3).
- Outer interceptors that inspect the operation after `proceed()` returns (tracing, timing) see the final state,
  which is what they log today for the operations that already have setters.
- No change to any interceptor interface signature.

**B. `proceed(replacementOperation)` on every chain.** A functional variant: the chain holds the current operation,
an interceptor hands a new one to `proceed(...)`, and the chain passes it on.

- Works for records and other immutable operations, including `HandleMessage`.
- Needs every chain factory call site to switch to an operation-aware default behaviour, and a new factory (an
  overload of `newInterceptorChainForOperation` taking `Function<OPERATION, RESULT>` instead of
  `Supplier<RESULT>` makes existing method-reference arguments ambiguous for third-party callers).
- Copies lose output state (blocker 3) unless every copy shares a state holder with its original.
- Two ways to do the same thing for the operations that already have setters.

A is chosen. B's one advantage, records, applies to a single operation (`HandleMessage`), handled separately below.

## The contract

To be written into `Interceptor`'s Javadoc, `LLM/LLM-shared.md` (`InterceptorChain`) and each family's LLM doc:

1. **Change the input in place, then proceed.** An interceptor that changes what the operation carries calls the
   operation's setter before `proceed()`. Payload values are immutable: build a new one (`message.withPayload(…)`),
   never mutate one.
2. **Change the output by returning something else.** Read-side transformation (decrypting what was loaded) is done
   on the value `proceed()` returned.
3. **Everything downstream reads the operation.** Later interceptors receive the same operation object; the default
   behaviour reads it after the chain reached it. A chain built in this code base never hands a captured local to
   the default behaviour. (This is the implementer's half of the contract and the part the tests below guard.)
4. **Only what reaches the target has to fit the target.** Where the target was chosen before the chain
   (message-handler method, command handler), interceptors may replace the payload with a value of *any* type, and
   later interceptors may replace it again: an `EncryptedPayload` may become a `byte[]` and then an `OrderPlaced`.
   The type is checked once, by the default behaviour, immediately before it invokes the matched method or handler.
   If the value that arrives there is not accepted by that target, the invocation fails with a
   `ClassCastException` naming the type that arrived and the type that was expected. The target is not
   re-resolved: it was chosen from the payload as it was before the chain ran, so a decrypting interceptor in these
   two families must produce a value of the type the dispatch was made for. Decryption that has to change which
   handler runs belongs below dispatch, in a `DurableQueuesInterceptor` on `HandleQueuedMessage`.
5. **Ordering is `@InterceptorOrder`.** A decrypting interceptor must run before any interceptor that reads the
   payload; an encrypting one after any that enriches it. The order number is how this is expressed; document the
   numbers used by built-in interceptors so users can place theirs around them.

## Per-family changes

### shared

- Javadoc only: the contract above on `Interceptor` and `InterceptorChain`. No new API in `shared`.

### foundation — value-object helpers

- `Message#withPayload(Object)` and `Message#withMetaData(MessageMetaData)` returning a new instance of the same
  shape. `OrderedMessage` overrides both to keep `key` and `order`. A user subclass of `Message` that does not
  override them gets a plain `Message` back — documented on the methods.
- `QueuedMessage#withMessage(Message)` as a default method returning a delegating decorator
  (`MessageReplacedQueuedMessage`, package-private) that overrides `getMessage()`/`getPayload()`/`getMetaData()` and
  delegates everything else, including the mutating `markForRedeliveryIn`, to the original. Its `equals` delegates
  to the original's id; documented that `instanceof DefaultQueuedMessage` checks see the decorator. Main code has no
  such checks today outside the implementations' own `equals`.

### foundation — `DurableQueuesInterceptor` / `HandleQueuedMessage`

- `HandleQueuedMessage`: drop `final` from `public QueuedMessage message`, add `setMessage(QueuedMessage)` (non-null,
  same `getId()` as the original — a replacement may change content, never identity). Removing `final` from an
  instance field is binary compatible (JLS 13.4.9), and existing interceptors that read `operation.message` keep
  reading the current value.
- `DefaultDurableQueueConsumer`, `CentralizedMessageFetcher`, `ShardOwnedDurableQueues`: the default behaviour calls
  the handler with `operation.getMessage()`. Acknowledge, retry and dead-letter paths keep using the original
  queued message's id, which is unchanged by construction.
- Already conforming (verify only, via the contract tests): `QueueMessage`, `QueueMessages`,
  `QueueMessageAsDeadLetterMessage`, in `PostgresqlDurableQueues`, `MongoDurableQueues` and `ShardOwnedDurableQueues`.

### foundation — `MessageHandlerInterceptor` / `InvokeMessageHandlerMethod`

- Drop `final` from `message` and `messagPayload`, add `getMessage()`, `getPayload()` and
  `setMessage(Message)`, which sets both fields together so they cannot drift. The setter checks only for null; it
  accepts a payload of any type (contract rule 4).
- `PatternMatchingMessageHandler.invokeMethod`: the default behaviour checks that `operation.getPayload()` is an
  instance of `methodToInvoke`'s first parameter type, throws `ClassCastException` if not, and otherwise invokes
  the method with `operation.getPayload()` and `operation.getMessage()`.
- `ClassCastException` is deliberate. The mismatch is deterministic — the same interceptors will produce the same
  type on every redelivery — and `ClassCastException` is on the queue consumer's built-in permanent-error list, so
  the message dead-letters on the first attempt instead of burning its redeliveries (`LLM/LLM-foundation.md`, *The
  built-in permanent-error list*). A user who has `alwaysRetryOn(ClassCastException.class)` gets retries, as for
  any other `ClassCastException`. The Javadoc states both.
- Later, deprecate direct field access in favour of the getters, for removal in the next major.

### postgresql-event-store — `EventStoreInterceptor`

- Input side already conforms. Add contract tests only.
- `PersistedEvent#withEvent(EventJSON)` and `#withMetaData(EventMetaDataJSON)`, so a read-side interceptor can
  return a decrypted event without calling the twelve-argument `PersistedEvent.from(...)`.
- Document the interaction with `FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream`: it publishes
  the `AggregateEventStream` the append returned, so in-transaction subscribers see whatever an encrypting
  interceptor ordered inside it left in the result. The order number of that built-in has to be part of the
  documented ordering (contract rule 5).

### reactive — `CommandBusInterceptor`

There is no operation object; the chain is the per-call envelope (`command()` is its `operation()`), so the setter
goes there.

- `CommandBusInterceptorChain`: add `default void setCommand(Object command)` throwing
  `UnsupportedOperationException` (keeps third-party implementations of the interface compiling), implemented in
  `DefaultCommandBusInterceptorChain` by dropping `final` from `command`.
- Invokers in `AbstractCommandBus.send`/`sendAsync`, `LocalCommandBus.sendAndDontWait` and
  `DurableLocalCommandBus` pass `chain.command()`, not the captured command, to `interceptSend…`.
- `DurableLocalCommandBus`'s default behaviour calls `commandHandler.handle(...)` with the chain's command, not its
  captured local. For `sendAndDontWait` the chain carries the queued *message*, so the setter there replaces the
  command message; decrypting a queued command is done at the durable-queue layer instead.
- Handler fit (contract rule 4): `setCommand` accepts any non-null command. The default behaviour checks
  `matchedCommandHandler().canHandle(command.getClass())` immediately before calling `handle`, and throws
  `ClassCastException` if the handler cannot take what arrived. The handler is not re-resolved, so
  `matchedCommandHandler()` means the same thing throughout the chain.

### postgresql-queue-shard-owned — `MessageQueueInterceptor`

- `EnqueueMessages` already conforms.
- `HandleMessage` stays a record in this round and becomes a final class with a `setPayload(byte[], int)` setter
  in the next major (decision 2). Until then, users of the shard-owned engine through
  `postgresql-queue-shard-owned-adapter` get payload replacement from the adapter's `HandleQueuedMessage`, which this
  plan fixes, and users of the raw engine transform bytes in their codec. The conversion goes on the next-major
  migration list when that release is planned; its migration note is "record deconstruction patterns on
  `HandleMessage` must become accessor calls".

## Backward compatibility

- No interceptor interface changes signature; no existing method is removed.
- New members are additive: setters on operations, a default method on `CommandBusInterceptorChain`, helpers on
  `Message`, `OrderedMessage`, `QueuedMessage`, `PersistedEvent`.
- Dropping `final` from public instance fields is binary compatible and source compatible for readers. It allows
  direct assignment, which is why the setters are the documented way and the fields get deprecated later.
- Behaviour change, deliberate: an existing interceptor that *already* tried to replace something — for example by
  reflection, or by mutating a mutable payload in place — keeps working; one that read `operation.message` *after*
  `proceed()` now sees the replacement an inner interceptor made, instead of the original. Called out in the release
  notes.
- Chains that third parties build themselves with `newInterceptorChainForOperation` are unaffected: the contract's
  rule 3 is theirs to follow if they want replacement.

## Tests

One contract test per family, each with the same three cases, so the families cannot drift apart again:

1. An interceptor replaces the payload; the handler, the persistence call or the next interceptor receives the
   replacement.
2. Two interceptors both replace; the second sees the first's replacement and the target sees the second's.
3. Output state survives: for `HandleQueuedMessage`, an inner interceptor that calls
   `acknowledgedWithHandler(true)` is still seen by the consumer after a replacing outer interceptor ran.

Plus, per dispatch-before-chain family (message handler, command bus):

- An intermediate interceptor may produce a value of a different type as long as a later one restores a type the
  target accepts; the target is invoked normally.
- A value of the wrong type that reaches the target fails with `ClassCastException`, and in a queue consumer that
  dead-letters the message on the first attempt.

For the durable queues, the contract test has to run against all three implementations (PostgreSQL, MongoDB,
shard-owned adapter) — the default behaviour is written three times.

## Steps

1. Value-object helpers (`Message`, `OrderedMessage`, `QueuedMessage`) and `HandleQueuedMessage.setMessage`, with
   unit tests. (`PersistedEvent`'s helpers moved to step 5, the only step that uses them.)
2. The three consumer default behaviours read `HandleQueuedMessage.getMessage()`; contract test against the three queue
   implementations.
3. `InvokeMessageHandlerMethod` setter and `PatternMatchingMessageHandler`; contract test.
4. `CommandBusInterceptorChain.setCommand` and the four invoker sites; contract test.
5. Event store: contract tests for the existing setters, ordering documentation for the built-in interceptors.
6. Docs: `Interceptor` Javadoc, `LLM/LLM-shared.md`, `LLM-foundation.md`, `LLM-reactive.md`,
   `LLM-postgresql-event-store.md`, a trap entry for "a `MessageHandlerInterceptor` or `CommandBusInterceptor` cannot change which handler runs —
   what reaches the handler must be the type it was dispatched for"
   in `LLM/LLM-traps.md` (next free `ESS-NNN`), plugin version bump, release-notes entry.

Steps 2–5 touch disjoint modules and can run in parallel once step 1 is in.

## Decisions

1. **Type-changing replacement where dispatch already happened** (message-handler method, command handler):
   interceptors may change the type freely; only the value that reaches the target is checked, immediately before
   the target is invoked. The target is not re-resolved. (Contract rule 4.)
2. **`HandleMessage` in the shard-owned engine:** converted from a record to a class in the next major; left as it
   is until then.
