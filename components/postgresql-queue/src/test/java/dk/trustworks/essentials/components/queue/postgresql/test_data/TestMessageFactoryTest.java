/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.queue.postgresql.test_data;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class TestMessageFactoryTest {

    @Test
    void createUnorderedMessages_withSingleQueue_shouldDistributeAllMessages() {
        // Given
        var totalMessages = 5;
        var queueNames    = List.of(QueueName.of("TestQueue"));

        // When
        var result = TestMessageFactory.createUnorderedMessages(totalMessages, queueNames);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result).containsKey(QueueName.of("TestQueue"));

        var messages = result.get(QueueName.of("TestQueue"));
        assertThat(messages).hasSize(5);

        // Verify message content and structure
        for (int i = 0; i < messages.size(); i++) {
            var message = messages.get(i);
            assertThat(message.getPayload()).isEqualTo("Unordered-0-" + i);
            assertThat(message.getMetaData()).isNotNull();
            assertThat(message.getMetaData().get("correlation_id")).isInstanceOf(String.class);
            assertThat(message.getMetaData().get("trace_id")).isInstanceOf(String.class);
        }
    }

    @Test
    void createUnorderedMessages_withMultipleQueues_shouldDistributeEvenly() {
        // Given
        var totalMessages = 10;
        var queueNames = List.of(QueueName.of("Queue1"),
                                 QueueName.of("Queue2"),
                                 QueueName.of("Queue3"));

        // When
        var result = TestMessageFactory.createUnorderedMessages(totalMessages, queueNames);

        // Then
        assertThat(result).hasSize(3);
        assertThat(result.keySet()).containsExactlyInAnyOrder(QueueName.of("Queue1"),
                                                              QueueName.of("Queue2"),
                                                              QueueName.of("Queue3"));

        // Each queue should have 3 or 4 messages (10/3 = 3 remainder 1)
        var totalMessagesInResult = result.values().stream()
                                          .mapToInt(List::size)
                                          .sum();
        assertThat(totalMessagesInResult).isEqualTo(10);

        // First queue gets extra message due to remainder
        assertThat(result.get(QueueName.of("Queue1"))).hasSize(4);
        assertThat(result.get(QueueName.of("Queue2"))).hasSize(3);
        assertThat(result.get(QueueName.of("Queue3"))).hasSize(3);
    }

    @Test
    void createUnorderedMessages_withUnevenDistribution_shouldHandleRemainder() {
        // Given
        var totalMessages = 7;
        var queueNames = List.of(QueueName.of("Queue1"),
                                 QueueName.of("Queue2"),
                                 QueueName.of("Queue3"));

        // When
        var result = TestMessageFactory.createUnorderedMessages(totalMessages, queueNames);

        // Then
        assertThat(result).hasSize(3);

        // 7/3 = 2 remainder 1, so first queue gets 3, others get 2
        assertThat(result.get(QueueName.of("Queue1"))).hasSize(3);
        assertThat(result.get(QueueName.of("Queue2"))).hasSize(2);
        assertThat(result.get(QueueName.of("Queue3"))).hasSize(2);

        var totalMessagesInResult = result.values().stream()
                                          .mapToInt(List::size)
                                          .sum();
        assertThat(totalMessagesInResult).isEqualTo(7);
    }

    @Test
    void createUnorderedMessages_withZeroMessages_shouldReturnEmptyLists() {
        // Given
        var totalMessages = 0;
        var queueNames    = List.of(QueueName.of("TestQueue"));

        // When
        var result = TestMessageFactory.createUnorderedMessages(totalMessages, queueNames);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(QueueName.of("TestQueue"))).isEmpty();
    }

    @Test
    void createOrderedMessages_withSingleQueue_shouldCreateOrderedMessages() {
        // Given
        var totalMessages        = 6;
        var queues               = List.of(QueueName.of("TestQueue"));
        var distinctKeysPerQueue = 2;

        // When
        var result = TestMessageFactory.createOrderedMessages(totalMessages, queues, distinctKeysPerQueue);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result).containsKey(QueueName.of("TestQueue"));

        var messages = result.get(QueueName.of("TestQueue"));
        assertThat(messages).hasSize(6);

        // Verify there are exactly 2 distinct keys
        var distinctKeys = messages.stream().map(OrderedMessage::getKey).distinct().toList();
        assertThat(distinctKeys).containsExactlyInAnyOrder("TestQueue-K0", "TestQueue-K1");

        // Verify message structure and ordering
        for (int i = 0; i < messages.size(); i++) {
            var message = messages.get(i);
            assertThat(message.getPayload()).isEqualTo("Msg-0-" + i);
            assertThat(message.getKey()).startsWith("TestQueue-K");
            assertThat(message.getMetaData()).isNotNull();
            assertThat(message.getMetaData().get("correlation_id")).isInstanceOf(String.class);
            assertThat(message.getMetaData().get("trace_id")).isInstanceOf(String.class);
        }
    }

    @Test
    void createOrderedMessages_shouldDistributeMessagesAcrossKeys() {
        // Given
        var totalMessages        = 6;
        var queues               = List.of(QueueName.of("TestQueue"));
        var distinctKeysPerQueue = 3;

        // When
        var result = TestMessageFactory.createOrderedMessages(totalMessages, queues, distinctKeysPerQueue);

        // Then
        var messages = result.get(QueueName.of("TestQueue"));

        // Group messages by key
        var messagesByKey = new HashMap<String, List<OrderedMessage>>();
        for (var message : messages) {
            messagesByKey.computeIfAbsent(message.getKey(), k -> new ArrayList<>()).add(message);
        }

        assertThat(messagesByKey).hasSize(3);
        assertThat(messagesByKey.keySet()).containsExactlyInAnyOrder("TestQueue-K0",
                                                                     "TestQueue-K1",
                                                                     "TestQueue-K2");

        // Each key should have 2 messages (6/3 = 2)
        messagesByKey.values().forEach(keyMessages ->
                                               assertThat(keyMessages).hasSize(2)
                                      );
    }

    @Test
    void createOrderedMessages_shouldMaintainCorrectOrderNumbers() {
        // Given
        var totalMessages        = 4;
        var queues               = List.of(QueueName.of("TestQueue"));
        var distinctKeysPerQueue = 2;

        // When
        var result = TestMessageFactory.createOrderedMessages(totalMessages, queues, distinctKeysPerQueue);

        // Then
        var messages = result.get(QueueName.of("TestQueue"));

        // Group messages by key and verify order numbers
        var messagesByKey = new HashMap<String, List<OrderedMessage>>();
        for (var message : messages) {
            messagesByKey.computeIfAbsent(message.getKey(), k -> new ArrayList<>()).add(message);
        }

        for (var keyMessages : messagesByKey.values()) {
            keyMessages.sort(Comparator.comparing(OrderedMessage::getOrder));
            for (int i = 0; i < keyMessages.size(); i++) {
                assertThat(keyMessages.get(i).getOrder()).isEqualTo(i);
            }
        }
    }

    @Test
    void createOrderedMessages_withMultipleQueues_shouldDistributeAcrossQueues() {
        // Given
        var totalMessages = 12;
        var queues = List.of(QueueName.of("Queue1"),
                             QueueName.of("Queue2"));
        var distinctKeysPerQueue = 2;

        // When
        var result = TestMessageFactory.createOrderedMessages(totalMessages, queues, distinctKeysPerQueue);

        // Then
        assertThat(result).hasSize(2);

        // Each queue should have 6 messages (12/2 = 6)
        assertThat(result.get(QueueName.of("Queue1"))).hasSize(6);
        assertThat(result.get(QueueName.of("Queue2"))).hasSize(6);

        // Verify keys are queue-specific
        var queue1Messages = result.get(QueueName.of("Queue1"));
        var queue1Keys     = queue1Messages.stream().map(OrderedMessage::getKey).distinct().toList();
        assertThat(queue1Keys).containsExactlyInAnyOrder("Queue1-K0", "Queue1-K1");

        var queue2Messages = result.get(QueueName.of("Queue2"));
        var queue2Keys     = queue2Messages.stream().map(OrderedMessage::getKey).distinct().toList();
        assertThat(queue2Keys).containsExactlyInAnyOrder("Queue2-K0", "Queue2-K1");
    }

    @Test
    void createOrderedMessages_withZeroMessages_shouldReturnEmptyLists() {
        // Given
        var totalMessages        = 0;
        var queues               = List.of(QueueName.of("TestQueue"));
        var distinctKeysPerQueue = 2;

        // When
        var result = TestMessageFactory.createOrderedMessages(totalMessages, queues, distinctKeysPerQueue);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(QueueName.of("TestQueue"))).isEmpty();
    }

    @Test
    void createOrderedMessages_shouldRoundRobinMessagesAcrossKeys() {
        // Given
        var totalMessages        = 5;
        var queues               = List.of(QueueName.of("TestQueue"));
        var distinctKeysPerQueue = 3;

        // When
        var result = TestMessageFactory.createOrderedMessages(totalMessages, queues, distinctKeysPerQueue);

        // Then
        var messages = result.get(QueueName.of("TestQueue"));

        // First 3 messages should go to different keys, then next 2 to first 2 keys
        assertThat(messages.get(0).getKey()).isEqualTo("TestQueue-K0");
        assertThat(messages.get(1).getKey()).isEqualTo("TestQueue-K1");
        assertThat(messages.get(2).getKey()).isEqualTo("TestQueue-K2");
        assertThat(messages.get(3).getKey()).isEqualTo("TestQueue-K0");
        assertThat(messages.get(4).getKey()).isEqualTo("TestQueue-K1");
    }
}