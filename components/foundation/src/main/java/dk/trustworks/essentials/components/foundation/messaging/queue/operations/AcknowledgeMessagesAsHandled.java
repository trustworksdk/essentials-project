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

package dk.trustworks.essentials.components.foundation.messaging.queue.operations;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Mark several messages as acknowledged in one operation - this deletes them from the Queue.<br>
 * Note this method MUST be called within an existing {@link UnitOfWork} IF
 * using {@link TransactionalMode#FullyTransactional}<br>
 * Operation also matches {@link DurableQueuesInterceptor#intercept(AcknowledgeMessagesAsHandled, InterceptorChain)}
 * <p>
 * This exists because the per-message acknowledgement is the dominant per-message cost in the queue, and the
 * cost is the transaction rather than the statement. Acknowledging one message at a time measured
 * <strong>16.5x</strong> more expensive on drain time than acknowledging a batch <em>in a raw-SQL harness</em>.
 * Measured at <strong>16.5x</strong> on drain time in a raw-SQL harness — but that does <b>not</b>
 * reproduce through the component, where it measures <strong>1.02x</strong> on a backlog drain and
 * <strong>1.00x</strong> in steady state with a worse p99. The acknowledgement transaction is simply not the
 * bottleneck once a connection pool and a real consumer are in the picture. See
 * {@code docs/durable-queues-measurements.md} §2, "Claims withdrawn under measurement".
 * <p>
 * A
 * batching implementation therefore needs a way to
 * acknowledge a group of {@link QueueEntryId}s inside <em>one</em> {@link UnitOfWork}, which is what this
 * operation provides.
 *
 * @see DurableQueues#acknowledgeMessagesAsHandled(Collection)
 */
public final class AcknowledgeMessagesAsHandled {
    public final List<QueueEntryId> queueEntryIds;

    /**
     * Create a new builder that produces a new {@link AcknowledgeMessagesAsHandled} instance
     *
     * @return a new {@link AcknowledgeMessagesAsHandledBuilder} instance
     */
    public static AcknowledgeMessagesAsHandledBuilder builder() {
        return new AcknowledgeMessagesAsHandledBuilder();
    }

    /**
     * Mark the messages as acknowledged - this operation deletes them from the Queue<br>
     * Note this method MUST be called within an existing {@link UnitOfWork} IF
     * using {@link TransactionalMode#FullyTransactional}
     *
     * @param queueEntryIds the unique ids of the Messages to acknowledge. Must not be empty - an empty
     *                      acknowledgement is a caller bug rather than a no-op worth issuing a statement for,
     *                      and silently accepting it hides a batching implementation that has lost its buffer
     */
    public AcknowledgeMessagesAsHandled(Collection<QueueEntryId> queueEntryIds) {
        requireNonNull(queueEntryIds, "No queueEntryIds provided");
        requireFalse(queueEntryIds.isEmpty(), "queueEntryIds must not be empty");
        this.queueEntryIds = List.copyOf(queueEntryIds);
    }

    /**
     * @return the unique ids of the Messages to acknowledge
     */
    public List<QueueEntryId> getQueueEntryIds() {
        return queueEntryIds;
    }

    @Override
    public String toString() {
        return "AcknowledgeMessagesAsHandled{" +
                "queueEntryIds=" + queueEntryIds.size() +
                '}';
    }
}
