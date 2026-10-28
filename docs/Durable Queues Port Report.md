Essentials 0.60.0 · postgresql-queue · Rust port experiment

# Durable Queues Port Report

We ported a focused part of Java Essentials Durable Queues to Rust, checked it against the real Java on one PostgreSQL schema, and benchmarked both. The port met the experiment's goals. The bigger payoff is a list of concrete improvements for the Java implementation, several of them measured.

Branch `durable-queues-phase-0-1` · benchmark run 2026-10-12 · full write-up in `durable-queues-rs/docs/EVALUATION.md`

## Verdict

**Same semantics**Enqueue, claim, retry, delay, dead letters, stuck reset, ordered messages. 27 of Java's 34 queue tests ported or reproduced; 6 out of scope.

**Safe to mix with Java**13 interop tests against real Java 0.60.0: both directions, competing consumers, crash recovery each way.

**Same speed, smaller footprint**With identical SQL and pacing, throughput and latency match. Rust uses about 40× less memory and starts 7× faster.

Recommendation: keep Java as the reference implementation. Rust is an option for footprint-sensitive workers once it has metrics and a minimal admin API. Act on the Java takeaways below regardless.

## Results at a glance

Each consumer ran in a container with 2 CPUs and 1 GiB against one PostgreSQL 17, with a pool of 10 connections, 20 ms polling and a no-op handler. The Rust column polls exactly like Java's fetcher, so both issue the same statements at the same rate. Ranges cover 1 or 10 queues × 8 or 128 consumers.

| Metric | Java | Rust |
| --- | --- | --- |
| Idle memory | 108–125 MiB | 2.4–3.6 MiB |
| Memory under load | 138–425 MiB | 3.3–18 MiB |
| Startup to first message | 484–554 ms | 68–72 ms |
| Latency p50 / p99 | 12–17 / 25–29 ms | 12–14 / 22–29 ms |
| CPU at 5000 msg/s, 128 consumers | 60–66 % | 34–37 % |
| Drain, 10 queues × 128 consumers | 6.6k msg/s | 16.9k msg/s |
| Container image | 496 MiB | 135 MiB |
| Library size (code lines) | ≈4.6k in scope | 2.4k |

Effort for the port was about 7.3 active hours across Phases 0–7, AI-assisted, reconstructed from session timestamps.

## Takeaways for the Java implementation

Ordered by expected payoff. Each one says how strong the evidence is.

Measured benchmarked or explained here Verified in code read in the 0.60.0 source Proposal the fix is untested

1. ### Claim rows by primary key, so stale statistics cannot slow the claim

   Measured

   Problem

   The unordered claim ends with `UPDATE … FROM cte_unordered u WHERE q.id = u.id AND q.queue_name = :queueName`. If the table was analyzed while empty, PostgreSQL thinks it has no rows and joins with a nested loop over every row of the queue. Each claim then costs time proportional to the backlog. A queue that idles empty and then gets a burst is exactly this case.

   Fix

   Pass the claimed ids as an array and drop the redundant `queue_name` re-check. The CTE already filters by queue and `id` is the primary key, so the result is the same.

   ```
   -FROM cte_unordered u-WHERE q.id = u.id-  AND q.queue_name = :queueName+WHERE q.id = ANY(ARRAY(SELECT id FROM cte_unordered)) RETURNING q.*;
   ```

   | Stale statistics, 20 000 queued | Today | Rewritten |
   | --- | --- | --- |
   | One claim, limit 128 (EXPLAIN ANALYZE) | 276 ms | 7 ms |
   | One claim, limit 8 | 27 ms | 6 ms |
   | Drain, 128 consumers (benchmark) | 1 095 msg/s | 6 313 msg/s |
   | Claim per call during that drain | 41.6 ms | 3.3 ms |
   | Fresh statistics, either form | 1.2 ms | 1.2 ms |

   We tried two simpler rewrites (only dropping the `queue_name` check, and `WHERE q.id IN (SELECT …)`). Neither helped; the planner still looped over the queue. The ordered claim (§4.2) has the same join shape and likely benefits too. The benchmark used the Rust node with the patched SQL; Java runs the identical statement.
2. ### Poll again when a poll fills every free slot

   MeasuredVerified in code

   Problem

   The centralized fetcher runs on `scheduleAtFixedRate`, one poll per interval, claiming at most the free slots. So one consumer handles at most `parallelConsumers / pollingInterval` messages per second: 400/s with 8 consumers and 20 ms. Above that the backlog grows without bound. At 1000 msg/s offered, Java handled 400/s and left about 18 000 messages behind.

   Evidence

   The Rust loop with this change handled 4 100 msg/s of 5 000 offered with the same 8 slots, and drained a backlog at 4 900/s against Java's 400/s. Latency at 100 msg/s stayed within 3 ms of fixed-rate polling.

   Fix

   When a poll returned as many messages as there were free slots, schedule the next poll as soon as a slot frees instead of waiting for the next tick. Until then, document the ceiling next to `parallelConsumers`.
3. ### Stop polling empty queues at full rate

   MeasuredProposal

   Problem

   Without LISTEN/NOTIFY, every consumed queue is polled every 20 ms with two statements: the ordered claim, then the unordered claim because the first found nothing. That is 100 statements per second per queue per node with nothing to do. With 10 queues, a single idle node sends about 1 000 claim statements a second.

   Evidence

   In `pg_stat_statements` at 100 msg/s on 10 queues, Java ran the ordered claim 20 138 times and it returned 0 rows every time. That was a quarter of the database time spent on claims.

   Fix

   Back off on empty polls by default (the existing polling optimizer only runs with LISTEN/NOTIFY). Separately, consider one statement covering both claims, or skipping the ordered claim for queues that never receive ordered messages.
4. ### Use virtual threads for consumer workers

   MeasuredProposal

   Problem

   Each consumer creates `Executors.newScheduledThreadPool(parallelConsumers)`, one platform thread per slot. With 10 queues × 128 consumers that is 1 280 threads. Java used 425 MiB and ran CPU-bound at 199 % of its 2 CPUs while draining at 6.6k msg/s. Rust drained the same backlog at 16.9k msg/s on 49 % CPU and 16 MiB.

   Fix

   Run handlers on virtual threads (Java 25 is the baseline), and profile where the drain's CPU goes: JSON mapping, unit-of-work setup and thread hand-offs are the likely costs. Whether virtual threads close the gap is untested.
5. ### Fix `getDeliveryMode()` for ordered messages

   Verified in code

   Problem

   `DefaultQueuedMessage.getDeliveryMode()` returns `DeliveryMode.NORMAL` unconditionally (line 111), even when `getMessage()` is an `OrderedMessage` with the correct key and order. Ordering itself works; a handler asking for the delivery mode gets the wrong answer.

   Fix

   Return `IN_ORDER` when the message is an `OrderedMessage`, or carry the row's `delivery_mode` through. One line, plus a test. An interop test (`java_reports_every_queued_message_as_normal`) pins the current behaviour.
6. ### Smaller fixes

   Verified in code
   - `last_delivery_error` stores the stack trace of a `UnitOfWorkException` that wraps the handler's exception. Storing the root cause first would make dead letters easier to read.
   - HikariCP keeps all 10 connections open even when idle, because `minimumIdle` defaults to the pool size. Recommend a lower `minimumIdle` for queue-only services, which add up quickly against `max_connections`.

## What did not change between languages

The difficult parts were the same in both languages: the claim predicate, `>` versus `>=` on the retry limit, timestamp precision, the clock source and the query planner. They needed a written behavioural contract and tests against the real Java either way. Where throughput differed, the cause was the polling algorithm or the threading model, never the language itself.

**3**Rust bugs, all caught by tests within their phase

**1**Java bug (delivery mode)

**1**shared SQL weakness (stale statistics)

**98**Rust tests, all passing

Method: `durable-queues-rs/bench/README.md`. Raw data: `bench/results/20261012T064824Z/`. Claim-rewrite test: EXPLAIN ANALYZE in a scratch PostgreSQL 17, then the benchmark's saturation runs with the patched statement (not committed). Single run per configuration on one host. Treat differences under about 10 % as noise.