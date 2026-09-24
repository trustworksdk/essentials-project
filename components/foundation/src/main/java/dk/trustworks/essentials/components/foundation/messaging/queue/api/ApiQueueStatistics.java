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

package dk.trustworks.essentials.components.foundation.messaging.queue.api;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.observability.QueueStatistics;

import java.time.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What is known about one queue, from the two sources that know different things.
 *
 * <h2>The split is the point</h2>
 * {@link #depth()} comes from the queue storage and is <b>cluster-wide</b>: every instance sees the same numbers.
 * {@link #instance()} comes from this JVM's {@code QueueStatisticsRegistry} and covers <b>only the deliveries
 * this instance performed</b>, resetting when it restarts.
 * <p>
 * They are deliberately not merged into one flat set of figures. Presenting per-instance throughput under a
 * cluster-wide heading is how an operator concludes a queue is stalled while three other pods are draining it —
 * the same trap {@code ApiSubscription.runningInThisInstance} exists to avoid for subscriptions.
 * <p>
 * {@link Depth#oldestReadyMessageAgeMillis()} and {@link Depth#messagesBeingDelivered()} are what turn "0 handled on
 * this instance" from ambiguous into either "nothing to do" or "stalled", and they are the whole reason for
 * joining the two halves rather than exposing the registry alone.
 *
 * @param queueName the queue
 * @param depth     cluster-wide, from the queue storage
 * @param instance  this JVM only, or {@code null} when this instance has delivered nothing from the queue
 */
public record ApiQueueStatistics(QueueName queueName,
                                 Depth depth,
                                 InstanceDelivery instance) {

    public ApiQueueStatistics {
        requireNonNull(queueName, "No queueName provided");
        requireNonNull(depth, "No depth provided");
    }

    /**
     * Cluster-wide: what the queue is holding, as stored.
     *
     * @param queuedMessages          non-dead-letter messages queued
     * @param deadLetterMessages      dead-letter messages queued
     * @param messagesBeingDelivered  how many of {@code queuedMessages} are currently out with a consumer
     * @param oldestReadyMessageAgeMillis how long the oldest ready-for-delivery message has been waiting, or
     *                                {@code null} when nothing is ready. A large value with
     *                                {@code messagesBeingDelivered == 0} is the signature of a stalled queue
     */
    public record Depth(long queuedMessages,
                        long deadLetterMessages,
                        long messagesBeingDelivered,
                        Long oldestReadyMessageAgeMillis) {
    }

    /**
     * This JVM only: what this instance's consumers did.
     *
     * @param statisticsSince        when this instance started counting — a restart resets it
     * @param messagesHandled        delivered and acknowledged by this instance
     * @param messagesRetried        failed deliveries that were redelivered
     * @param messagesDeadLettered   deliveries that ended as a dead letter
     * @param redeliveryRequests     handler-requested redeliveries, which are not failures
     * @param averageHandlerDurationMillis mean handler duration, or {@code null} when nothing was handled
     * @param maxHandlerDurationMillis     slowest handler invocation, or {@code null} when nothing was handled
     * @param lastHandledAt          when the most recent message was handled, or {@code null}
     * @param lastFailureAt          when the most recent failure occurred, or {@code null}
     * @param lastFailureReason      the most recent failure's type and message, or {@code null}
     */
    public record InstanceDelivery(Instant statisticsSince,
                                   long messagesHandled,
                                   long messagesRetried,
                                   long messagesDeadLettered,
                                   long redeliveryRequests,
                                   Long averageHandlerDurationMillis,
                                   Long maxHandlerDurationMillis,
                                   Instant lastHandledAt,
                                   Instant lastFailureAt,
                                   String lastFailureReason) {
    }

    /**
     * Join the cluster-wide counts with this instance's statistics.
     *
     * @param counts    the cluster-wide counts, from {@code DurableQueues.getQueuedMessageCountsFor}
     * @param statistics this instance's statistics, or {@code null} when it has delivered nothing from the queue
     * @param now       the instant to measure {@link Depth#oldestReadyMessageAgeMillis()} against
     * @return the joined view
     */
    public static ApiQueueStatistics from(QueuedMessageCounts counts, QueueStatistics statistics, Instant now) {
        requireNonNull(counts, "No counts provided");
        requireNonNull(now, "No now provided");
        return new ApiQueueStatistics(
                counts.queueName(),
                new Depth(counts.numberOfQueuedMessages(),
                          counts.numberOfQueuedDeadLetterMessages(),
                          counts.numberOfMessagesBeingDelivered(),
                          counts.oldestReadyMessageTimestamp() != null
                          ? Duration.between(counts.oldestReadyMessageTimestamp(), now).toMillis()
                          : null),
                statistics != null
                ? new InstanceDelivery(statistics.statisticsSince(),
                                       statistics.delivery().messagesHandled(),
                                       statistics.outcomes().messagesRetried(),
                                       statistics.outcomes().messagesDeadLettered(),
                                       statistics.outcomes().redeliveryRequests(),
                                       toMillis(statistics.delivery().averageHandlerDuration()),
                                       toMillis(statistics.delivery().maxHandlerDuration()),
                                       statistics.delivery().lastHandledAt(),
                                       statistics.outcomes().lastFailureAt(),
                                       statistics.outcomes().lastFailureReason())
                : null);
    }

    /**
     * Durations cross the contract as milliseconds, matching {@code ApiSubscriptionStatistics} — the admin surface
     * presents the two side by side, and an ISO-8601 duration string next to a number would be two idioms for one
     * quantity.
     *
     * @param duration the duration, or {@code null}
     * @return the duration in milliseconds, or {@code null} if no duration was given
     */
    static Long toMillis(Duration duration) {
        return duration != null ? duration.toMillis() : null;
    }
}
