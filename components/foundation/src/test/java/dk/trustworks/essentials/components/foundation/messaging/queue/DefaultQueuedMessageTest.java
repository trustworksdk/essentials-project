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

class DefaultQueuedMessageTest {
    private static final QueueName QUEUE_NAME = QueueName.of("TestQueue");

    @Test
    void an_ordered_message_is_delivered_in_order() {
        var queuedMessage = queuedMessageWith(OrderedMessage.of("a-payload", "key-1", 3));

        assertThat(queuedMessage.getDeliveryMode()).isEqualTo(QueuedMessage.DeliveryMode.IN_ORDER);
    }

    @Test
    void an_unordered_message_is_delivered_normally() {
        var queuedMessage = queuedMessageWith(Message.of("a-payload"));

        assertThat(queuedMessage.getDeliveryMode()).isEqualTo(QueuedMessage.DeliveryMode.NORMAL);
    }

    private static DefaultQueuedMessage queuedMessageWith(Message message) {
        return DefaultQueuedMessage.builder()
                                   .setId(QueueEntryId.random())
                                   .setQueueName(QUEUE_NAME)
                                   .setMessage(message)
                                   .setAddedTimestamp(OffsetDateTime.now())
                                   .build();
    }
}
