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

package dk.trustworks.essentials.components.foundation.messaging.queue.observability;

import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;

import java.time.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An immutable snapshot of what one queue's deliveries have done <b>in this JVM</b>.
 * <p>
 * <b>Scope is this JVM only.</b> Unlike the queued and dead-letter message counts, which come from the queue table
 * and are therefore shared by every instance of the application, these figures cover the deliveries this instance
 * performed. A caller that presents both must say which is which: per-instance figures under a cluster-wide heading
 * are how an operator concludes a queue is stalled while three other pods drain it.
 * <p>
 * This is <em>not</em> the pre-0.60 {@code QueueStatistics(queueName, fromTimestamp, totalMessagesDelivered,
 * avgDeliveryLatencyMs, firstDelivery, lastDelivery)} reshaped. That record's {@code avgDeliveryLatencyMs} was
 * measured from enqueue, which is a different quantity from handler duration, and keeping the name while changing
 * the meaning would be worse than changing the name.
 *
 * @param queueName       the queue these statistics cover
 * @param statisticsSince when this instance started counting — a restart resets it
 * @param delivery        what succeeded
 * @param outcomes        what did not
 */
public record QueueStatistics(QueueName queueName,
                              Instant statisticsSince,
                              Delivery delivery,
                              Outcomes outcomes) {

    public QueueStatistics {
        requireNonNull(queueName, "No queueName provided");
        requireNonNull(statisticsSince, "No statisticsSince provided");
        requireNonNull(delivery, "No delivery provided");
        requireNonNull(outcomes, "No outcomes provided");
    }

    /**
     * Successful deliveries.
     *
     * @param messagesHandled       messages delivered and acknowledged by this instance
     * @param averageHandlerDuration mean handler duration over {@code messagesHandled}, or {@code null} when none
     * @param maxHandlerDuration    the slowest single handler invocation, or {@code null} when none
     * @param lastHandledAt         when the most recent message was handled, or {@code null} when none
     */
    public record Delivery(long messagesHandled,
                           Duration averageHandlerDuration,
                           Duration maxHandlerDuration,
                           Instant lastHandledAt) {
    }

    /**
     * Deliveries that did not succeed.
     * <p>
     * {@code lastFailureReason} is rendered text, never a retained {@link Throwable} — holding one would pin its
     * whole stack trace, and through it whatever the frames reference, for the lifetime of the registry entry.
     * It renders the <em>root</em> cause: a handler throw always reaches the observer wrapped in the framework's
     * own exceptions, so the outermost type would distinguish nothing.
     *
     * @param messagesRetried            failed deliveries that will be redelivered
     * @param redeliveryRequests         handler-requested redeliveries, which are not failures
     * @param messagesDeadLettered       deliveries that ended as a Poison-Message/Dead-Letter-Message
     * @param lastFailureAt              when the most recent failure occurred, or {@code null} when none
     * @param lastFailureReason          the most recent failure's type and message, or {@code null} when none
     */
    public record Outcomes(long messagesRetried,
                           long redeliveryRequests,
                           long messagesDeadLettered,
                           Instant lastFailureAt,
                           String lastFailureReason) {
    }
}
