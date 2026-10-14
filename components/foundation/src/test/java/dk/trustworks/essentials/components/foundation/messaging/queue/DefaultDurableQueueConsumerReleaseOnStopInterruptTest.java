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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The release {@link DefaultDurableQueueConsumer} makes for a message its poll claimed after the consumer began stopping
 * runs with the thread's interrupt flag cleared, and a release that throws is only logged: the stuck-message reset
 * returns the message.
 */
class DefaultDurableQueueConsumerReleaseOnStopInterruptTest {
    private static final QueueName    QUEUE_NAME = QueueName.of("TestQueue");
    private static final QueueEntryId MESSAGE_ID = QueueEntryId.of("message-1");

    @Test
    void the_release_of_an_undelivered_message_runs_with_the_interrupt_flag_cleared() throws InterruptedException {
        var handledMessages          = new CopyOnWriteArrayList<QueuedMessage>();
        var durableQueues            = mock(DurableQueues.class);
        var interruptedDuringRelease = new AtomicReference<Boolean>();
        var released                 = new CountDownLatch(1);
        when(durableQueues.releaseUndeliveredMessage(MESSAGE_ID, Duration.ZERO)).thenAnswer(invocation -> {
            interruptedDuringRelease.set(Thread.currentThread().isInterrupted());
            released.countDown();
            return Optional.empty();
        });
        var consumer = consumerWhosePollClaimsAfterStop(durableQueues, handledMessages);

        consumer.start();
        try {
            assertThat(released.await(5, TimeUnit.SECONDS)).as("the message is released").isTrue();
            assertThat(interruptedDuringRelease.get()).as("the interrupt flag is cleared while the release is written").isFalse();
            assertThat(handledMessages).as("the message never reaches the handler").isEmpty();
        } finally {
            consumer.stop();
        }
    }

    @Test
    void a_release_that_throws_is_only_logged_and_never_falls_back_to_a_retry() throws InterruptedException {
        var handledMessages = new CopyOnWriteArrayList<QueuedMessage>();
        var durableQueues   = mock(DurableQueues.class);
        var released        = new CountDownLatch(1);
        when(durableQueues.releaseUndeliveredMessage(MESSAGE_ID, Duration.ZERO)).thenAnswer(invocation -> {
            released.countDown();
            throw new RuntimeException("Simulated release failure");
        });
        var consumer = consumerWhosePollClaimsAfterStop(durableQueues, handledMessages);

        consumer.start();
        try {
            assertThat(released.await(5, TimeUnit.SECONDS)).as("the release was attempted").isTrue();
            assertThatCode(consumer::stop).as("the consumer still stops cleanly").doesNotThrowAnyException();
            verify(durableQueues, never()).retryMessage(any(QueueEntryId.class), any(), any(Duration.class));
            assertThat(handledMessages).as("the message never reaches the handler").isEmpty();
        } finally {
            consumer.stop();
        }
    }

    /**
     * A consumer whose poll stops the consumer while it is claiming a message - the window the release closes - and,
     * like a real stop, leaves the interrupt flag set on the polling thread
     */
    private static DefaultDurableQueueConsumer<?, ?, ?> consumerWhosePollClaimsAfterStop(DurableQueues durableQueues,
                                                                                         List<QueuedMessage> handledMessages) {
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

        var consumerRef = new AtomicReference<DefaultDurableQueueConsumer<?, ?, ?>>();
        when(durableQueues.getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class))).thenAnswer(invocation -> {
            consumerRef.get().stop();
            // stop() interrupts the delivery threads; set the flag explicitly so the test does not depend on when that happens
            Thread.currentThread().interrupt();
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
        return consumer;
    }
}