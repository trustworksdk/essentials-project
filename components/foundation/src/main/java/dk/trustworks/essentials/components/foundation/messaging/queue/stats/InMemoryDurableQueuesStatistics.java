/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.components.foundation.messaging.queue.stats;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@link DurableQueuesStatistics} served from a {@link DurableQueuesStatisticsRegistry} in this JVM's heap, fed by
 * a {@link DurableQueueMessageObserver}.
 *
 * <h2>Why this replaces the trigger</h2>
 * The previous implementation collected statistics in the database: an {@code AFTER DELETE ... FOR EACH ROW}
 * trigger on the queue table inserted one statistics row per acknowledged message, inside the queue's own
 * transaction. Measured at <b>2.80×</b> on acknowledgement throughput (see
 * {@code docs/durable-queues-redesign-measurements.md} §14), and paid by every deployment that turned statistics
 * on, so that one admin endpoint could report an average. It also had defects the cost alone would not justify
 * fixing around: a {@code purgeQueue} of 100 000 rows counted 100 000 delivered messages each with a latency
 * measured to the moment of the purge; the statistics component ran {@code CREATE TRIGGER} against a table it does
 * not own, making "enable statistics" a schema migration; and the plpgsql function name was unqualified, so two
 * instances in one schema silently fought over it.
 * <p>
 * Collecting in Java costs nothing on the acknowledgement transaction, is dialect-neutral (so it is shared by every
 * {@code DurableQueues} implementation rather than re-authored per SQL dialect), and works unchanged over a queue
 * that stores its messages in more than one table.
 *
 * <h2>What the numbers mean</h2>
 * <b>Per JVM, and since this instance started.</b> On a multi-instance deployment each instance reports the
 * deliveries it performed, so a low figure is not a slow queue and a zero is not a stall. Nothing is persisted, so
 * a restart resets them. For cluster-wide or historical answers, aggregate the Micrometer meters — which is what
 * the durable statistics table was really being used to approximate.
 */
public final class InMemoryDurableQueuesStatistics implements DurableQueuesStatistics {

    private final DurableQueuesStatisticsRegistry registry;

    public InMemoryDurableQueuesStatistics() {
        this(new DurableQueuesStatisticsRegistry());
    }

    public InMemoryDurableQueuesStatistics(DurableQueuesStatisticsRegistry registry) {
        this.registry = requireNonNull(registry, "No registry provided");
    }

    /**
     * Register this with the {@link DurableQueues} implementation - nothing is collected until it is.
     */
    public DurableQueueMessageObserver observer() {
        return registry.asObserver();
    }

    public DurableQueuesStatisticsRegistry registry() {
        return registry;
    }

    @Override
    public Optional<QueueStatistics> getQueueStatistics(QueueName queueName) {
        return registry.statisticsFor(queueName);
    }

    /**
     * Best-effort by construction: answers only for a message <b>this instance</b> recently finished delivering,
     * since that is all an in-memory ring can hold. Empty otherwise.
     * <p>
     * That is still strictly more than the trigger-based version managed — it stored {@code delivery_latency} as an
     * {@code INTERVAL} and read it back with {@code getInt}, which pgjdbc rejects, so this read threw for every id.
     * Nothing caught it because the method had no caller in the reactor and the one statistics test asserted
     * {@code isPresent()} on the queue-level aggregate.
     */
    @Override
    public Optional<QueuedStatisticsMessage> getQueueStatisticsMessage(QueueEntryId id) {
        return registry.statisticsForMessage(id);
    }
}
