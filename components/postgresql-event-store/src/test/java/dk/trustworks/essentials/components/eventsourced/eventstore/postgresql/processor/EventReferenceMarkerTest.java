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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.api.ApiQueuedMessage;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin API recognises an event processor's event-reference message by its metadata marker, and has to repeat the
 * marker's key rather than reference it - naming this module's types from {@code foundation} is a package cycle. This
 * is what keeps the two from drifting apart.
 */
class EventReferenceMarkerTest {

    @Test
    void the_admin_api_recognises_the_marker_the_event_processor_writes() {
        assertThat(ApiQueuedMessage.EVENT_REFERENCE_METADATA_KEY)
                .isEqualTo(AbstractEventProcessor.EventReferenceOrderedMessage.EVENT_REFERENCE_METADATA_KEY);

        var reference = new AbstractEventProcessor.EventReferenceOrderedMessage(AggregateType.of("Payments"),
                                                                                "payment-1042",
                                                                                EventOrder.of(2));
        var api = ApiQueuedMessage.from(DefaultQueuedMessage.builder()
                                                            .setId(QueueEntryId.random())
                                                            .setQueueName(QueueName.of("Inbox:CaptureFlow"))
                                                            .setMessage(reference)
                                                            .setAddedTimestamp(OffsetDateTime.now())
                                                            .setNextDeliveryTimestamp(OffsetDateTime.now())
                                                            .setDeliveryTimestamp(OffsetDateTime.now())
                                                            .setTotalDeliveryAttempts(1)
                                                            .setRedeliveryAttempts(0)
                                                            .build());

        assertThat(api.referencedAggregateType()).isEqualTo("Payments");
        assertThat(api.orderedMessageKey()).isEqualTo("payment-1042");
        assertThat(api.orderedMessageOrder()).isEqualTo(2L);
    }
}
