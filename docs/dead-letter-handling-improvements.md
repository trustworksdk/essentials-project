# Dead Letter Handling Improvements

Scoped to how `DurableQueueConsumer` classifies a message-handling failure, and to how visible the
outcome is. Found while building `examples/essentials-webshop-demo`: an automation lost a race, its
message was dead-lettered, and the only trace was one `log.error` line — an order that was never charged
while `/actuator/health` stayed green.

Four items. D1 and D2 are API promises that do not currently hold, D3 is an observability gap, D4 is
documentation. They are independent and can land in any order; D4 is the cheapest and is worth doing
first regardless of what happens to the rest.

---

## The current behaviour, in full

Two consumer implementations carry an identical copy of the rule
(`CentralizedMessageFetcher:468`, `DefaultDurableQueueConsumer:586`):

```java
protected boolean isPermanentError(QueuedMessage queuedMessage, Throwable e) {
    var rootCause = Exceptions.getRootCause(e);
    return consumeFromQueue.getRedeliveryPolicy().isPermanentError(queuedMessage, e) ||
            e instanceof DurableQueueDeserializationException ||
            e instanceof ClassCastException   || rootCause instanceof ClassCastException ||
            e instanceof NoClassDefFoundError || rootCause instanceof NoClassDefFoundError ||
            rootCause instanceof MismatchedInputException ||
            e instanceof IllegalArgumentException || rootCause instanceof IllegalArgumentException;
}
```

and its caller:

```java
if (isPermanentError || message.getTotalDeliveryAttempts() >= policy.getMaximumNumberOfRedeliveries() + 1) {
    log.error("[{}:{}] Marking message as dead letter due to error: {}", …);
```

Three properties follow, and all three surprise people:

1. **Permanent means zero retries.** It bypasses the redelivery policy entirely, rather than shortening it.
2. **The built-in list is OR-ed *after* the policy**, so no policy can remove a type from it.
3. **Only the outermost exception and the deepest root cause are examined**, never the middle of the chain.
   A `@MessageHandler` throw arrives wrapped (`UnitOfWorkException → ReflectionException →
   InvocationTargetException → yours`), so the handler's own exception is the deepest and decides the
   outcome — unless the handler's exception itself carries a cause, in which case the classification
   silently flips to that deeper type.

---

## D1 — An explicit `alwaysRetryOn` cannot win

### Motivation

`MessageDeliveryErrorHandler.builder().alwaysRetryOn(IllegalArgumentException.class)` builds a handler
whose `isPermanentError` returns `false` for that type. The consumer then ORs the built-in list, which
contains `IllegalArgumentException`, and the message is dead-lettered on the first delivery anyway.

The API offers a knob that cannot work for six of the types people most want to configure. Worse, the
two most likely ways to raise one are the framework's own idioms: Kotlin's `require(...)`, and
`FailFast.requireNonNull` / `requireTrue`, which throw `IllegalArgumentException` rather than
`NullPointerException` (`shared/.../FailFast.java:72`) across 2000+ call sites in this repository.

`false` today means two different things — "I have no opinion" and "I say retry" — and the consumer
cannot tell them apart.

### The shape

Give the strategy a three-valued answer, additively:

```java
public enum MessageDeliveryVerdict { PERMANENT_ERROR, RETRY, NO_OPINION }

// on MessageDeliveryErrorHandler, alongside the existing method
default MessageDeliveryVerdict verdict(QueuedMessage queuedMessage, Throwable error) {
    return isPermanentError(queuedMessage, error) ? PERMANENT_ERROR : NO_OPINION;
}
```

Every existing implementation keeps compiling and behaving identically. Only the builder's product
overrides `verdict` to answer `RETRY` for an `alwaysRetryOn` match. Both consumers then read:

```java
var verdict = policy.getDeliveryErrorHandler().verdict(message, e);
boolean permanent = verdict == PERMANENT_ERROR ||
                    (verdict != RETRY && isBuiltInPermanentError(e));
```

### Which built-ins stay unconditional

`RETRY` should not be able to override a failure that can never succeed, or the queue head-blocks
forever. Proposed split:

| Type | Overridable by `RETRY`? | Why |
|---|---|---|
| `DurableQueueDeserializationException`, `MismatchedInputException` | **No** | The stored bytes will not parse on the hundredth attempt either |
| `NoClassDefFoundError` | **No** | A missing class is a deployment fault, not a transient one |
| `IllegalArgumentException` (incl. `NumberFormatException`) | **Yes** | The house guard idiom; frequently thrown about data that may be valid later |
| `ClassCastException` | **Yes** | Usually a genuine bug, but a cast against a projection that has not caught up is legitimately transient |

### Tests

- `MessageDeliveryErrorHandlerBuilderTest` — extend with verdict assertions; it currently pins only the
  two-valued behaviour.
- `DurableQueuesIT` (`foundation-test`) — one case per row of the table above, so both the PostgreSQL and
  MongoDB implementations are covered from the shared base.
- `EventProcessorIT` — end to end: a handler throwing `IllegalArgumentException` under a policy that
  explicitly retries it must be redelivered, not dead-lettered.

### Effort

Small. Two production files plus the builder, one enum, and the tests above.

---

## D2 — `alwaysRetryOn` does not mean "no matter how many times"

### Motivation

Its javadoc says the handler "will keep retrying message redelivery **no matter how many times** message
handling experiences an exception". It does not: even once D1 makes `RETRY` win the classification, the
second half of the caller's condition — `attempts >= maximumNumberOfRedeliveries + 1` — still
dead-letters the message.

So the name and the doc promise unbounded redelivery while the code caps it.

### Two ways to resolve it, and this needs a decision

**Option A — honour the promise.** A `RETRY` verdict bypasses the attempt cap. Matches the javadoc and
the method name. Risk: a message can be retried forever, which is invisible unless D3 lands with it, and
a permanently-failing message at the head of an ordered queue blocks everything behind it.

**Option B — keep the cap, fix the documentation.** `alwaysRetryOn` means "never *classified* permanent",
nothing more, and a separate `retryIndefinitelyOn(...)` is added later if anybody asks. Safer, but leaves
a method whose name over-promises.

Recommendation: **B now, A only if somebody has the use case.** Unbounded retry plus ordered delivery is
how a queue stops moving, and nobody has asked for it. If A is chosen, it needs a mandatory escalating
`WARN` (every 10th attempt, say) so a stuck message is loud rather than silent.

---

## D3 — A dead letter is invisible

### Motivation

Nothing is waiting on a message handler. The HTTP request that produced the event committed long ago, the
handler runs on a subscription thread, and the subscription's resume point moves past the failure. A dead
letter therefore produces exactly one `log.error`, a row in the dead-letter table, and no other signal:
no failed test, no failing request, no health change.

What exists today is a *timer* — `essentials.messaging.durable_queues.mark_as_dead_letter_message` — which
measures how long the marking operation took, is gated behind `essentials.metrics.durable-queues.enabled`,
and carries no reason. It is not the signal an operator needs.

### The shape

Three pieces, in increasing order of intrusiveness:

1. **A counter, always recorded when a `MeterRegistry` is present** — one increment per dead letter, tagged
   with `queue_name`, `message_payload_type` and a new `reason` tag (`permanent_error` |
   `redeliveries_exhausted`). Independent of the execution-time toggle, because a timing switch should not
   turn an incident counter off. This is the piece that makes alerting possible at all.
2. **The log line carries the classification** — which of the two branches fired, the attempt count, and the
   root-cause type, so the ERROR line answers "why" without a debugger.
3. **An optional health indicator**, off by default, reporting dead-letter counts per queue as
   `DOWN`/degraded above a configurable threshold. `CdcHealthIndicator` is the precedent. Off by default
   because a dead letter is a business-process incident rather than an availability one, and a demo app
   should not go unhealthy for one poison message.

### Tests

`DurableQueuesIT` for the counter (both reasons), and a foundation unit test for the log-line
content. The health indicator gets its own IT in `spring-boot-starter-postgresql`.

### Effort

Medium. Piece 1 touches `RecordExecutionTimeDurableQueueInterceptor` (or a sibling interceptor, if mixing
counters into a class named for execution time is unwelcome) plus both consumers, which is where the reason
is known.

---

## D4 — The interaction is undocumented

### Motivation

Nothing tells a handler author that `FailFast.requireNonNull` — the idiom the whole repository uses for
argument validation — turns a message into a dead letter on first delivery when used inside a
`@MessageHandler`. It is not in `LLM/LLM-foundation.md`, not in `LLM/LLM-postgresql-queue.md`, and not in
the foundation `CLAUDE.md`. Every consumer writing an `EventProcessor` will eventually hit it, and the
failure leaves no trace beyond one log line.

### The shape

Documentation only, no code:

- **`LLM/LLM-foundation.md`** — in the DurableQueues/RedeliveryPolicy section: the built-in permanent list,
  that `IllegalArgumentException` is on it, and that `FailFast` and Kotlin `require(...)` both raise it. A
  short "validating inside a handler" recipe: use a retryable exception when the condition may become true
  later, `IllegalArgumentException` only when the message can never be processed.
- **`LLM/LLM-postgresql-queue.md`** — the same list, next to the redelivery-policy documentation.
- **`EventProcessor` / `ViewEventProcessor` javadoc** — one paragraph where handler authors actually read.
- **`components/foundation/CLAUDE.md`** — the contributor-side note, including the root-cause-unwrapping
  subtlety from property 3 above.
- **Root `CLAUDE.md`** — one line in Critical Gotchas, because it crosses modules.

### Effort

Small, and it is the item with the best ratio: it removes the surprise even if D1–D3 never happen.

---

## Compatibility

The stable-API rule applies (root `CLAUDE.md`): breaking changes only in a new major, additive in
patch/minor. All of the above is additive:

- D1 adds an enum and a `default` method. Every existing `MessageDeliveryErrorHandler` — including the
  three inner classes and any consumer implementation — keeps its behaviour, because the default
  implementation maps `true`/`false` onto `PERMANENT_ERROR`/`NO_OPINION`.
- **`alwaysRetry()` deliberately keeps meaning `NO_OPINION`, not `RETRY`.** It is the builder default, so
  promoting it to `RETRY` would silently make deserialization failures retry forever in every existing
  application. Only the explicit `alwaysRetryOn(...)` list yields `RETRY`.
- D2 option B is a documentation change; option A would be a behaviour change and belongs in a major.
- D3 adds signal only.

## Sequencing

| Step | Content | Gate |
|---|---|---|
| 1 | D4 — documentation | none; do it now |
| 2 | D1 — the verdict enum, both consumers, the overridable/unconditional split | decision on the split table |
| 3 | D3 piece 1 and 2 — counter and richer log line | none |
| 4 | D2 — resolve the naming/promise mismatch per the decision below | decision A or B |
| 5 | D3 piece 3 — optional health indicator | demand |

## Open questions

1. **D2: option A or B?** Recommendation B — keep the cap, fix the doc, add `retryIndefinitelyOn` only on
   demand.
2. **D1: is the overridable/unconditional split right?** Specifically, should `ClassCastException` be
   overridable? It is nearly always a bug, but "projection not caught up yet" makes it transient in
   practice.
3. **D3: should a dead letter ever affect health?** Proposed off by default; worth confirming that is the
   right default for a production deployment rather than just for a demo.
4. **Should the middle of the cause chain be examined** (property 3), rather than only the outermost
   exception and the deepest root cause? It would make classification predictable regardless of whether a
   handler attaches a cause — but it would also change the outcome for existing applications whose
   handlers wrap an `IllegalArgumentException` around something else, so it is a major-version change at
   best.
