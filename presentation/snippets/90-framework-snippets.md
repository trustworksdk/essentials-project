# Framework-Level Snippets

The snippets in this file are not extracted from the trading demo — they are the framework's own
consumer-facing examples, quoted from the LLM documentation. Re-check them against the cited source
before the talk; `extract.sh` cannot verify them automatically.

## UnitOfWork — one transaction across event store, repository and queue

Source: `LLM/LLM.md` § UnitOfWork Pattern

```java
unitOfWorkFactory.usingUnitOfWork(uow -> {
    repository.save(order);
    eventStore.appendToStream(type, id, events);
    durableQueues.queueMessage(queueName, msg, uow);
    // Auto-commit on success, rollback on exception
});
```

Slide point: three different kinds of write, one commit. In the demo the command bus opens this for you,
which is why no `@CmdHandler` in the codebase carries `@Transactional`.

## DurableQueues — a competing-consumer handler

Source: `LLM/LLM-foundation.md` § Pattern Matching Handler

```java
var consumer = durableQueues.consumeFromQueue(
    ConsumeFromQueue.builder()
        .setQueueName(QueueName.of("OrderProcessing"))
        .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff()
            .setRedeliveryDelay(Duration.ofMillis(200))
            .setMaximumNumberOfRedeliveries(5)
            .build())
        .setParallelConsumers(3)
        .setQueueMessageHandler(new PatternMatchingQueuedMessageHandler() {
            @MessageHandler
            void handle(ProcessOrderCommand cmd) { }

            @Override
            protected void handleUnmatchedMessage(QueuedMessage msg) { }
        })
        .build());
```

Slide point: at-least-once delivery, redelivery policy, dead-letter queue, competing consumers — and the
queue lives in the same database as the data the handler writes.

## FencedLock — fencing tokens, not just mutual exclusion

Source: `LLM/LLM-foundation.md` § Usage

```java
// Synchronous
try (FencedLock lock = lockManager.acquireLock(LockName.of("MyLock"))) {
    performCriticalWork();
}

// Asynchronous — PREFERRED for long-running work
lockManager.acquireLockAsync(LockName.of("ScheduledTask"), new LockCallback() {
    @Override
    public void lockAcquired(FencedLock lock) {
        long fenceToken = lock.getCurrentToken();
        startPeriodicProcessing(fenceToken);
    }
});
```

Slide point: `getCurrentToken()` is the part that matters. It is monotonic, so a stalled holder that wakes
up carrying an old token can be rejected — a plain mutex cannot do that. Kleppmann's fencing pattern.

## Outbox — no dual write

Source: `LLM/LLM-foundation.md` § Outbox

```
Database Update + Outbox.sendMessage() → UnitOfWork.commit()
                              ↓
                        DurableQueues → Relay → Kafka
```

```java
// Atomic with the database update
unitOfWorkFactory.usingUnitOfWork(() -> {
    Order order = orderRepository.save(new Order(...));
    kafkaOutbox.sendMessage(new OrderCreatedEvent(order.getId()));
});
```

Slide point: the message is committed with the data or not at all. The relay to Kafka happens afterwards,
which is what makes delivery at-least-once and downstream idempotency mandatory.

## EventStreamDecider — the functional alternative to an aggregate

Source: `LLM/LLM-eventsourced-aggregates.md` § EventStreamDecider

```java
interface EventStreamDecider<COMMAND, EVENT> {
    Optional<EVENT> handle(COMMAND command, List<EVENT> events);
    boolean canHandle(Class<?> command);
}
```

Tested with no infrastructure at all:

```java
@Test
void shouldBeIdempotent() {
    var scenario = new GivenWhenThenScenario<>(new CreateOrderDecider());

    scenario
        .given(new OrderCreated(orderId, customerId))
        .when(new CreateOrder(orderId, customerId))
        .thenExpectNoEvent();
}
```

Slide point: a command plus the event history in, an optional event out. `Optional.empty()` *is* the
idempotent no-op — the same behaviour `Instrument.rename` implements with an `if` and an early return.
Pair this slide with `05-Instrument.java` so the audience sees both lanes side by side, then say which one
the demo uses and why the choice is not being revisited.

## Pattern selection table

Source: `LLM/LLM-eventsourced-aggregates.md` § Pattern Selection

| Pattern | State | Testing | Best for |
|---|---|---|---|
| `EventStreamDecider` | Immutable, event stream | Given-When-Then | Event modelling, functional, slicing |
| `Decider` | Immutable, external | Result types | Typed errors |
| Modern `AggregateRoot` | Mutable, internal | Unit tests | OOP, Spring Boot — **what the demo uses** |
| `FlexAggregate` | Immutable, internal | Functional | Explicit event control |
