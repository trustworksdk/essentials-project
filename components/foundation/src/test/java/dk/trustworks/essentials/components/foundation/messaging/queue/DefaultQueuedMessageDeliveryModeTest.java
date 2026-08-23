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

package dk.trustworks.essentials.components.foundation.messaging.queue;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultQueuedMessage#getDeliveryMode()} used to return {@link QueuedMessage.DeliveryMode#NORMAL}
 * unconditionally, contradicting both the persisted {@code delivery_mode} column and
 * {@code MongoDurableQueues}' own implementation, which reports {@code IN_ORDER} correctly.
 * <p>
 * The consequence was that any caller trusting the accessor treated every ordered message as unordered. It was
 * found while adding batched acknowledgement — whose whole safety argument is that ordered messages are excluded,
 * and which the accessor silently defeated. The exclusion now keys off the wrapped {@link Message} instead, so
 * this test guards the accessor for every *other* caller rather than that one.
 */
class DefaultQueuedMessageDeliveryModeTest {

    @Test
    void an_ordered_message_payload_reports_IN_ORDER() {
        var queuedMessage = queuedMessageWrapping(OrderedMessage.of("payload", "key-1", 7L));

        assertThat(queuedMessage.getDeliveryMode()).isEqualTo(QueuedMessage.DeliveryMode.IN_ORDER);
    }

    @Test
    void a_plain_message_payload_reports_NORMAL() {
        var queuedMessage = queuedMessageWrapping(Message.of("payload"));

        assertThat(queuedMessage.getDeliveryMode()).isEqualTo(QueuedMessage.DeliveryMode.NORMAL);
    }

    /**
     * The mode is derived rather than stored, so it cannot drift from the message it describes. A storage
     * implementation reconstructs an {@link OrderedMessage} when it reads {@code IN_ORDER} back from the
     * database, which is what makes the wrapped message authoritative.
     */
    @Test
    void the_mode_is_derived_from_the_message_so_the_two_can_never_disagree() {
        var ordered = queuedMessageWrapping(OrderedMessage.of("payload", "key-1", 0L));
        var normal  = queuedMessageWrapping(Message.of("payload"));

        assertThat(ordered.getMessage()).isInstanceOf(OrderedMessage.class);
        assertThat(ordered.getDeliveryMode()).isEqualTo(QueuedMessage.DeliveryMode.IN_ORDER);
        assertThat(normal.getMessage()).isNotInstanceOf(OrderedMessage.class);
        assertThat(normal.getDeliveryMode()).isEqualTo(QueuedMessage.DeliveryMode.NORMAL);
    }

    private static DefaultQueuedMessage queuedMessageWrapping(Message message) {
        return DefaultQueuedMessage.builder()
                                   .setId(QueueEntryId.of("entry-1"))
                                   .setQueueName(QueueName.of("TestQueue"))
                                   .setMessage(message)
                                   .setAddedTimestamp(OffsetDateTime.now())
                                   .setNextDeliveryTimestamp(OffsetDateTime.now())
                                   .setTotalDeliveryAttempts(0)
                                   .setRedeliveryAttempts(0)
                                   .setDeadLetterMessage(false)
                                   .setBeingDelivered(false)
                                   .build();
    }
}
