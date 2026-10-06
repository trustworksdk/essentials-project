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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ApiQueuedMessageTest {

    @Test
    void a_message_referring_to_a_persisted_event_names_the_aggregate_type_key_and_order_even_without_its_payload() {
        var api = ApiQueuedMessage.from(queued(eventReference(AggregateType.of("Payments"), "payment-1042", 2)));

        assertThat(api.payload()).as("payload is still role-gated").isNull();
        assertThat(api.referencedAggregateType()).isEqualTo("Payments");
        assertThat(api.orderedMessageKey()).isEqualTo("payment-1042");
        assertThat(api.orderedMessageOrder()).isEqualTo(2L);
    }

    @Test
    void an_ordered_message_carrying_its_own_payload_has_a_key_and_order_but_refers_to_no_event() {
        var api = ApiQueuedMessage.from(queued(OrderedMessage.of("a-command", "order-1", 7)));

        assertThat(api.orderedMessageKey()).isEqualTo("order-1");
        assertThat(api.orderedMessageOrder()).isEqualTo(7L);
        assertThat(api.referencedAggregateType()).isNull();
    }

    /**
     * Recognised by the processor's marker, as the processor recognises it - not by the payload's type, which this
     * package must not name: {@code AggregateType}'s package depends on this one
     */
    @Test
    void an_ordered_message_carrying_an_aggregate_type_without_the_event_reference_marker_refers_to_no_event() {
        var api = ApiQueuedMessage.from(queued(OrderedMessage.of(AggregateType.of("Payments"), "payment-1042", 2)));

        assertThat(api.referencedAggregateType()).isNull();
        assertThat(api.orderedMessageOrder()).isEqualTo(2L);
    }

    @Test
    void an_unordered_message_has_none_of_them() {
        var api = ApiQueuedMessage.from(queued(Message.of(AggregateType.of("Payments"))));

        assertThat(api.orderedMessageKey()).isNull();
        assertThat(api.orderedMessageOrder()).isNull();
        assertThat(api.referencedAggregateType()).isNull();
    }

    private static OrderedMessage eventReference(AggregateType aggregateType, String aggregateId, long eventOrder) {
        return OrderedMessage.of(aggregateType, aggregateId, eventOrder,
                                 MessageMetaData.of(ApiQueuedMessage.EVENT_REFERENCE_METADATA_KEY, "true"));
    }

    private static QueuedMessage queued(Message message) {
        return DefaultQueuedMessage.builder()
                                   .setId(QueueEntryId.random())
                                   .setQueueName(QueueName.of("Inbox:CaptureFlow"))
                                   .setMessage(message)
                                   .setAddedTimestamp(OffsetDateTime.now())
                                   .setNextDeliveryTimestamp(OffsetDateTime.now())
                                   .setDeliveryTimestamp(OffsetDateTime.now())
                                   .setTotalDeliveryAttempts(1)
                                   .setRedeliveryAttempts(0)
                                   .build();
    }
}
