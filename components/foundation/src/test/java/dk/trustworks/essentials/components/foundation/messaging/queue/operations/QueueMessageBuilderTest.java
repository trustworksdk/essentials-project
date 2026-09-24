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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueueMessageBuilderTest {
    private static final QueueName QUEUE = QueueName.of("TestQueue");

    @Test
    void an_ordered_message_keeps_its_key_and_order() {
        // The builder used to split the message into payload + metadata and rebuild a plain Message, which
        // silently turned an OrderedMessage into an unordered one — no error, just messages delivered out of
        // order. Every EventProcessor forwarding path goes through here.
        var ordered = OrderedMessage.of("a-payload", "Order-123", 42);

        var queued = QueueMessage.builder()
                                 .setQueueName(QUEUE)
                                 .setMessage(ordered)
                                 .build();

        assertThat(queued.getMessage()).isInstanceOf(OrderedMessage.class);
        var actual = (OrderedMessage) queued.getMessage();
        assertThat(actual.getKey()).isEqualTo("Order-123");
        assertThat(actual.getOrder()).isEqualTo(42);
        assertThat(actual.getPayload()).isEqualTo("a-payload");
    }

    @Test
    void a_plain_message_is_carried_through_unchanged() {
        var metaData = MessageMetaData.of("trace_id", "abc");
        var message  = Message.of("a-payload", metaData);

        var queued = QueueMessage.builder()
                                 .setQueueName(QUEUE)
                                 .setMessage(message)
                                 .build();

        assertThat(queued.getMessage().getPayload()).isEqualTo("a-payload");
        assertThat(queued.getMessage().getMetaData()).isEqualTo(metaData);
    }

    @Test
    void a_payload_and_metadata_still_compose_into_a_message() {
        var queued = QueueMessage.builder()
                                 .setQueueName(QUEUE)
                                 .setPayload("a-payload")
                                 .setMetaData(MessageMetaData.of("trace_id", "abc"))
                                 .build();

        assertThat(queued.getMessage().getPayload()).isEqualTo("a-payload");
        assertThat(queued.getMessage().getMetaData().get("trace_id")).isEqualTo("abc");
    }
}
