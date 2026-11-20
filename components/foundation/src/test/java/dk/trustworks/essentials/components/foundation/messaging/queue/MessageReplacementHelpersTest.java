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

import dk.trustworks.essentials.components.foundation.messaging.queue.operations.HandleQueuedMessage;
import org.junit.jupiter.api.Test;

import java.time.*;

import static org.assertj.core.api.Assertions.*;

/**
 * The value-object helpers interceptors use to replace what an operation carries:
 * {@link Message#withPayload(Object)}, {@link Message#withMetaData(MessageMetaData)}, their {@link OrderedMessage}
 * overrides, {@link QueuedMessage#withMessage(Message)} and {@link HandleQueuedMessage#setMessage(QueuedMessage)}
 */
class MessageReplacementHelpersTest {
    private static final QueueName QUEUE_NAME = QueueName.of("TestQueue");

    @Test
    void withPayload_keeps_the_metadata_and_leaves_the_original_unchanged() {
        var metaData = MessageMetaData.of("trace_id", "t-1");
        var original = Message.of("encrypted", metaData);

        var replaced = original.withPayload(42);

        assertThat(replaced).isExactlyInstanceOf(Message.class);
        assertThat(replaced.getPayload()).isEqualTo(42);
        assertThat(replaced.getMetaData()).isEqualTo(metaData);
        assertThat(original.getPayload()).isEqualTo("encrypted");
    }

    @Test
    void withMetaData_keeps_the_payload() {
        var original = Message.of("payload", MessageMetaData.of("a", "1"));
        var newMetaData = MessageMetaData.of("b", "2");

        var replaced = original.withMetaData(newMetaData);

        assertThat(replaced.getPayload()).isEqualTo("payload");
        assertThat(replaced.getMetaData()).isEqualTo(newMetaData);
        assertThat(original.getMetaData()).isEqualTo(MessageMetaData.of("a", "1"));
    }

    @Test
    void an_ordered_message_keeps_its_key_and_order_through_both_helpers() {
        var original = OrderedMessage.of("payload", "key-1", 7, MessageMetaData.of("a", "1"));

        OrderedMessage withPayload  = original.withPayload("decrypted");
        OrderedMessage withMetaData = original.withMetaData(MessageMetaData.of("b", "2"));

        assertThat(withPayload.getKey()).isEqualTo("key-1");
        assertThat(withPayload.getOrder()).isEqualTo(7);
        assertThat(withPayload.getPayload()).isEqualTo("decrypted");
        assertThat(withPayload.getMetaData()).isEqualTo(MessageMetaData.of("a", "1"));
        assertThat(withMetaData.getKey()).isEqualTo("key-1");
        assertThat(withMetaData.getOrder()).isEqualTo(7);
        assertThat(withMetaData.getPayload()).isEqualTo("payload");
    }

    @Test
    void the_message_helpers_reject_null() {
        var original = Message.of("payload");

        assertThatThrownBy(() -> original.withPayload(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> original.withMetaData(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withMessage_replaces_only_the_message() {
        var original = queuedMessageWith(OrderedMessage.of("encrypted", "key-1", 3));

        var replaced = original.withMessage(((OrderedMessage) original.getMessage()).withPayload("decrypted"));

        assertThat(replaced.getMessage().getPayload()).isEqualTo("decrypted");
        assertThat(replaced.getPayload()).isEqualTo("decrypted");
        assertThat(replaced.getMetaData()).isEqualTo(original.getMetaData());
        assertThat((Object) replaced.getId()).isEqualTo(original.getId());
        assertThat((Object) replaced.getQueueName()).isEqualTo(original.getQueueName());
        assertThat(replaced.getAddedTimestamp()).isEqualTo(original.getAddedTimestamp());
        assertThat(replaced.getTotalDeliveryAttempts()).isEqualTo(original.getTotalDeliveryAttempts());
        assertThat(replaced.getRedeliveryAttempts()).isEqualTo(original.getRedeliveryAttempts());
        assertThat(replaced.getDeliveryMode()).isEqualTo(QueuedMessage.DeliveryMode.IN_ORDER);
        assertThat(replaced).isEqualTo(original);
        assertThat(replaced.hashCode()).isEqualTo(original.getId().hashCode());
        assertThat(original.getPayload()).isEqualTo("encrypted");
    }

    @Test
    void markForRedeliveryIn_on_the_replacement_acts_on_the_original() {
        var original = queuedMessageWith(Message.of("payload"));
        var replaced = original.withMessage(Message.of("other"));

        replaced.markForRedeliveryIn(Duration.ofSeconds(5));

        assertThat(original.isManuallyMarkedForRedelivery()).isTrue();
        assertThat(original.getRedeliveryDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(replaced.isManuallyMarkedForRedelivery()).isTrue();
    }

    @Test
    void replacing_twice_does_not_stack_decorators() {
        var original = queuedMessageWith(Message.of("payload"));

        var twice = original.withMessage(Message.of("first")).withMessage(Message.of("second"));

        assertThat(twice.getPayload()).isEqualTo("second");
        assertThat(twice.toString()).containsOnlyOnce("MessageReplacedQueuedMessage");
    }

    @Test
    void handleQueuedMessage_setMessage_replaces_the_message() {
        var original  = queuedMessageWith(Message.of("encrypted"));
        var operation = new HandleQueuedMessage(original, msg -> {});
        var replaced  = original.withMessage(Message.of("decrypted"));

        operation.setMessage(replaced);

        assertThat(operation.getMessage()).isSameAs(replaced);
        assertThat(operation.message).isSameAs(replaced);
    }

    @Test
    void handleQueuedMessage_setMessage_rejects_another_message_or_null() {
        var operation = new HandleQueuedMessage(queuedMessageWith(Message.of("payload")), msg -> {});

        assertThatThrownBy(() -> operation.setMessage(queuedMessageWith(Message.of("payload"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same id");
        assertThatThrownBy(() -> operation.setMessage(null)).isInstanceOf(IllegalArgumentException.class);
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
