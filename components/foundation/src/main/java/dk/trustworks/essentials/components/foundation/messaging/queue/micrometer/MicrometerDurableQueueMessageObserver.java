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

package dk.trustworks.essentials.components.foundation.messaging.queue.micrometer;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import io.micrometer.core.instrument.*;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Counts dead-lettered messages, so an operator can alert on them.
 *
 * <h2>Why this exists separately from the execution-time metrics</h2>
 * Nothing is waiting on a message handler. The HTTP request that produced the event committed long ago, the
 * handler runs on a subscription thread, and the subscription's resume point moves past the failure. A dead
 * letter therefore produced exactly one {@code log.error}, a row in the dead-letter table, and no other signal:
 * no failed test, no failing request, no health change.
 * <p>
 * What already existed was a <em>timer</em>,
 * {@code essentials.messaging.durable_queues.mark_as_dead_letter_message}, which measures how long the marking
 * operation took, is gated behind {@code essentials.metrics.durable-queues.enabled}, and carries no reason. That
 * is not the signal an operator needs.
 * <p>
 * This counter is registered whenever a {@link MeterRegistry} is present, independently of that toggle — a
 * timing switch must not turn an incident counter off.
 *
 * <h2>The metric</h2>
 * {@value #DEAD_LETTERED_COUNTER_NAME}, incremented once per dead letter, tagged:
 * <ul>
 *   <li>{@value #QUEUE_NAME_TAG} — the queue</li>
 *   <li>{@value #MESSAGE_PAYLOAD_TYPE_TAG} — the payload's type name, so one poison message type is visible
 *       among many</li>
 *   <li>{@value #REASON_TAG} — {@code permanent_error} or {@code redeliveries_exhausted}. These fail for
 *       different reasons and are usually fixed by different people</li>
 * </ul>
 * Tag cardinality is bounded by the number of queues times the number of payload types, both of which are
 * properties of the application rather than of its traffic.
 */
public final class MicrometerDurableQueueMessageObserver implements DurableQueueMessageObserver {
    /** The counter an operator should alert on. */
    public static final String DEAD_LETTERED_COUNTER_NAME = "essentials.messaging.durable_queues.dead_lettered";

    public static final String QUEUE_NAME_TAG           = "queue_name";
    public static final String MESSAGE_PAYLOAD_TYPE_TAG = "message_payload_type";
    public static final String REASON_TAG               = "reason";
    public static final String MODULE_TAG               = "Module";

    private static final String UNKNOWN_PAYLOAD_TYPE = "unknown";

    private final MeterRegistry meterRegistry;
    private final List<Tag>     commonTags;

    /**
     * @param meterRegistry the registry to record into
     * @param moduleTag     an optional value for the {@value #MODULE_TAG} tag, matching
     *                      {@link DurableQueuesMicrometerInterceptor}'s convention; may be {@code null}
     */
    public MicrometerDurableQueueMessageObserver(MeterRegistry meterRegistry, String moduleTag) {
        this.meterRegistry = requireNonNull(meterRegistry, "No meterRegistry instance provided");
        this.commonTags = moduleTag != null ? List.of(Tag.of(MODULE_TAG, moduleTag)) : List.of();
    }

    @Override
    public void messageDeadLettered(QueuedMessage message, Throwable cause, MessageDeliveryOutcome outcome) {
        var tags = new ArrayList<>(commonTags);
        tags.add(Tag.of(QUEUE_NAME_TAG, message.getQueueName().toString()));
        tags.add(Tag.of(MESSAGE_PAYLOAD_TYPE_TAG, payloadTypeOf(message)));
        tags.add(Tag.of(REASON_TAG, reasonOf(outcome)));
        meterRegistry.counter(DEAD_LETTERED_COUNTER_NAME, tags).increment();
    }

    /**
     * The payload type is read from the message's metadata rather than by deserializing it: a message can be
     * dead-lettered precisely <em>because</em> its payload will not deserialize, and an observer that throws
     * while reporting that would be reporting nothing.
     */
    private static String payloadTypeOf(QueuedMessage message) {
        try {
            var payloadType = message.getMessage() != null ? message.getMessage().getPayload() : null;
            return payloadType != null ? payloadType.getClass().getName() : UNKNOWN_PAYLOAD_TYPE;
        } catch (Throwable e) {
            return UNKNOWN_PAYLOAD_TYPE;
        }
    }

    private static String reasonOf(MessageDeliveryOutcome outcome) {
        if (outcome == null) {
            return UNKNOWN_PAYLOAD_TYPE;
        }
        return switch (outcome) {
            case PERMANENT_ERROR -> "permanent_error";
            case REDELIVERIES_EXHAUSTED -> "redeliveries_exhausted";
            case RETRY -> "retry";
        };
    }

    @Override
    public String toString() {
        return "MicrometerDurableQueueMessageObserver";
    }
}
