# Essentials 0.60.1 — Release Notes

0.60.1 is a patch release for the durable queues. It fixes claim performance after a queue table has drained,
fixes a delivery-mode bug and makes stored delivery errors readable in `postgresql-queue`. In the shard-owned
engine it fixes four failure-path bugs and makes attempt counts survive a change of owner.

There are no API removals, no schema changes and no change to the persisted message format. A few behaviours
change in ways a test or a dashboard can notice; they are listed first.

## 1. Behaviour changes — read this first

### 1.1 `QueuedMessage.getDeliveryMode()` returns `IN_ORDER` for ordered messages (`postgresql-queue`)

`DefaultQueuedMessage.getDeliveryMode()` returned `NORMAL` for every message, including an `OrderedMessage`.
It now returns `IN_ORDER` whenever `getMessage()` is an `OrderedMessage`. Ordering itself was never affected. Code
that branched on the delivery mode now sees the correct value.

Fixing this exposed a latent crash. Resurrecting an ordered dead letter in `PostgresqlDurableQueues` would have
thrown `ClassCastException`, because its log line cast the wrong object. That code had never run, because the mode
was always `NORMAL`. It is fixed in the same release.

### 1.2 Stored delivery errors start with the root cause (`postgresql-queue`, `springdata-mongo-queue`)

A handler's exception reaches the queue wrapped, typically as
`UnitOfWorkException → ReflectionException → InvocationTargetException → yours`. The stored
`last_delivery_error` therefore began with `UnitOfWorkException`. When the exception is wrapped, the stored text
now starts with one line, `Root cause: <type>: <message>`, followed by the complete, unchanged stack trace. An
exception that is not wrapped is stored exactly as before.

Anything that parses the first line of `getLastDeliveryError()` will see the new summary line.

### 1.3 Shard-owned: `markForRedeliveryIn(delay)` honours the delay and never dead-letters

Through the shard-owned adapter, `QueuedMessage.markForRedeliveryIn(delay)` used to be treated as a failure. The
requested delay was ignored in favour of the policy's backoff, and enough requests dead-lettered a healthy message.
It now redelivers after `delay`. The attempt still counts, as a manual redelivery counts in
`PostgresqlDurableQueues`, but the request never dead-letters the message and is not reported as a handler
failure. Engine users get the same outcome by throwing the new `spi.RedeliveryRequested(delay)`.

### 1.4 Shard-owned: a handler `Error` counts as a failed attempt

An `AssertionError`, or any other non-critical `Error`, thrown by a handler used to free its slot without recording
an attempt. The message came back forever and never reached the dead-letter table. It now runs through the
redelivery policy like any other failure. Critical errors (`VirtualMachineError`, `LinkageError`) still propagate
uncounted, as they do in `postgresql-queue`.

### 1.5 Shard-owned: attempt counts survive a change of owner

Attempt counts were kept only in memory, so every new owner started a message's count from 1. The bulk increment
a new owner made on takeover, meant to stop a message that crashes the JVM from looping forever, was never read
back. It also charged every queued row an attempt on every takeover, including clean ones.

- The first failure under a new owner continues from the count stored on the row.
- A clean handover (the previous owner stopped and released the shard) charges nothing, so deploys and rebalances
  no longer cost queued messages attempts.
- A takeover after a crash or a lost lease charges one attempt only to the rows the previous owner could have had
  in flight: at most `parallelConsumers + ackBatchSize` rows per unordered shard, and the first
  `ackBatchSize + 1` rows of each key on the ordered lane.
- A message that has taken its owner down `maxAttempts` times is dead-lettered at takeover, with an error saying
  no failure was ever recorded for it. On the ordered lane its key is blocked behind it, as for any dead letter.

The `takeoverAttemptBumps` metric therefore drops to zero for clean handovers and to a small number for crashes.

## 2. Fixes

### 2.1 Claims stay fast after a queue table drains (`postgresql-queue`)

When a queue table drains to empty and is then analyzed, PostgreSQL records that it has no rows. When the next
burst arrives, the claim's `UPDATE … FROM cte WHERE q.id = cte.id` was planned as a nested loop over the whole
queue, so each claim took time proportional to the backlog: 185 ms instead of 4 ms per claim at 20 000 queued
messages. Both claim statements now select their rows by primary-key array (`WHERE q.id = ANY(ARRAY(…))`).
`PostgresqlDurableQueuesClaimPlanIT` recreates the stale statistics and guards the plan shape.

The ordered claim's per-key ordering check can still degrade under the same conditions. If ordered queues regularly
drain and refill, keep the table's statistics fresh; see "Stale statistics on a draining queue table" in
`LLM/LLM-postgresql-queue.md`.

### 2.2 Shard-owned: a superseded owner cannot record a failure

Retry and dead-letter writes now carry the same lease check as acknowledgements. An owner that has lost a shard no
longer pushes out the visibility of, or dead-letters, a message its successor is delivering. The successor
redelivers it.

## 3. Documentation

- The `CentralizedMessageFetcher`'s throughput ceiling, `parallelConsumers / pollingInterval` per queue, is now
  documented next to the polling settings.
- The `essentials` Claude Code plugin is updated to `0.60.0-6` with the documentation above.
