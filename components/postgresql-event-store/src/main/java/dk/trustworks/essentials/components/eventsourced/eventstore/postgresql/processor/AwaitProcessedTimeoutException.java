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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueuedMessageCounts;

import java.time.Duration;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Thrown by {@link AbstractEventProcessor#awaitProcessed(AggregateType, GlobalEventOrder, Duration)} (and signalled by
 * {@link AbstractEventProcessor#awaitProcessedAsync(AggregateType, GlobalEventOrder, Duration)}) when the processor had
 * not processed the event within the timeout. The message - and the accessors - say how far the processor got: its
 * subscription's resume point, why the event does not count as processed yet, and how many messages its queue holds.
 */
public final class AwaitProcessedTimeoutException extends RuntimeException {
    private final String                        processorName;
    private final AggregateType                 aggregateType;
    private final GlobalEventOrder              globalEventOrder;
    private final Duration                      timeout;
    private final Optional<GlobalEventOrder>    resumeFromAndIncluding;
    private final String                        reason;
    private final Optional<QueuedMessageCounts> queuedMessageCounts;

    /**
     * @param processorName          the processor that was awaited
     * @param aggregateType          the aggregate type of the awaited event
     * @param globalEventOrder       the global event order of the awaited event
     * @param timeout                the timeout that elapsed
     * @param resumeFromAndIncluding the processor's resume point for the aggregate type when the timeout elapsed, if known
     * @param reason                 why the event did not count as processed when the timeout elapsed
     * @param queuedMessageCounts    the processor's queue when the timeout elapsed, if known
     */
    AwaitProcessedTimeoutException(String processorName,
                                          AggregateType aggregateType,
                                          GlobalEventOrder globalEventOrder,
                                          Duration timeout,
                                          Optional<GlobalEventOrder> resumeFromAndIncluding,
                                          String reason,
                                          Optional<QueuedMessageCounts> queuedMessageCounts) {
        super(msg("Processor '{}' had not processed the '{}' event with global-event-order {} within {}: {}. Resume point (next global-event-order to handle): {}. Queue: {}",
                  processorName,
                  aggregateType,
                  globalEventOrder,
                  timeout,
                  reason,
                  requireNonNull(resumeFromAndIncluding, "No resumeFromAndIncluding provided").map(Object::toString).orElse("unknown - the processor's subscription isn't active in this JVM"),
                  requireNonNull(queuedMessageCounts, "No queuedMessageCounts provided").map(counts -> msg("{} queued message(s) and {} dead-letter message(s) in '{}'",
                                                                                                           counts.numberOfQueuedMessages(),
                                                                                                           counts.numberOfQueuedDeadLetterMessages(),
                                                                                                           counts.queueName()))
                                                                                       .orElse("unknown")));
        this.processorName = processorName;
        this.aggregateType = aggregateType;
        this.globalEventOrder = globalEventOrder;
        this.timeout = timeout;
        this.resumeFromAndIncluding = resumeFromAndIncluding;
        this.reason = reason;
        this.queuedMessageCounts = queuedMessageCounts;
    }

    public String getProcessorName() {
        return processorName;
    }

    public AggregateType getAggregateType() {
        return aggregateType;
    }

    public GlobalEventOrder getGlobalEventOrder() {
        return globalEventOrder;
    }

    public Duration getTimeout() {
        return timeout;
    }

    /**
     * @return the processor's resume point (the next {@link GlobalEventOrder} its subscription handles) when the timeout
     * elapsed - empty if the processor's subscription for the aggregate type isn't active in this JVM
     */
    public Optional<GlobalEventOrder> getResumeFromAndIncluding() {
        return resumeFromAndIncluding;
    }

    /**
     * @return why the event did not count as processed when the timeout elapsed
     */
    public String getReason() {
        return reason;
    }

    /**
     * @return the number of queued and dead-letter messages in the processor's queue (an {@code EventProcessor}'s Inbox,
     * a {@code ViewEventProcessor}'s durable queue) when the timeout elapsed, if known
     */
    public Optional<QueuedMessageCounts> getQueuedMessageCounts() {
        return queuedMessageCounts;
    }
}
