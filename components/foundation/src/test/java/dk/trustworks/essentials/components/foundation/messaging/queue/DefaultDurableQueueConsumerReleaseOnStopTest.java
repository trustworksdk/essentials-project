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

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A message {@link DefaultDurableQueueConsumer}'s poll claimed after the consumer began stopping never reaches a
 * handler, so it is released with {@link DurableQueues#releaseUndeliveredMessage(QueueEntryId, Duration)} - no
 * redelivery attempt charged - instead of staying marked as being delivered until the stuck-message reset.
 */
class DefaultDurableQueueConsumerReleaseOnStopTest {
    private static final QueueName    QUEUE_NAME = QueueName.of("TestQueue");
    private static final QueueEntryId MESSAGE_ID = QueueEntryId.of("message-1");

    @Test
    void a_message_claimed_after_the_consumer_began_stopping_is_released_without_charging_an_attempt() {
        var handledMessages = new CopyOnWriteArrayList<QueuedMessage>();
        var consumeFromQueue = ConsumeFromQueue.builder()
                                               .setQueueName(QUEUE_NAME)
                                               .setConsumerName("TestConsumer")
                                               .setParallelConsumers(1)
                                               .setPollingInterval(Duration.ofMillis(10))
                                               .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 1))
                                               .setQueueMessageHandler(handledMessages::add)
                                               .build();
        var queuedMessage = mock(QueuedMessage.class);
        when(queuedMessage.getId()).thenReturn(MESSAGE_ID);

        var consumerRef   = new AtomicReference<DefaultDurableQueueConsumer<?, ?, ?>>();
        var durableQueues = mock(DurableQueues.class);
        // The consumer stops while its poll is claiming the message - the window the release closes
        when(durableQueues.getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class))).thenAnswer(invocation -> {
            consumerRef.get().stop();
            return Optional.of(queuedMessage);
        });

        var dependencies = DurableQueueConsumerDependencies.<DurableQueues, UnitOfWork, UnitOfWorkFactory<UnitOfWork>>builder()
                                                           .setDurableQueues(durableQueues)
                                                           .setRemoveDurableQueueConsumer(consumer -> {
                                                           })
                                                           .setPollingIntervalMs(consumeFromQueue.getPollingInterval().toMillis())
                                                           .build();
        var consumer = new DefaultDurableQueueConsumer<>(consumeFromQueue, dependencies) {
        };
        consumerRef.set(consumer);

        consumer.start();
        try {
            verify(durableQueues, timeout(5_000)).releaseUndeliveredMessage(MESSAGE_ID, Duration.ZERO);
            verify(durableQueues, never()).retryMessage(any(QueueEntryId.class), any(), any(Duration.class));
            assertThat(handledMessages).as("the message never reaches the handler").isEmpty();
        } finally {
            consumer.stop();
        }
    }
}
