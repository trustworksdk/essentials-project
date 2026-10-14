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
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Every queue write {@link DefaultDurableQueueConsumer} makes from a thread that {@code stop()} may have interrupted
 * runs with the thread's interrupt flag cleared - see {@link ConsumerQueueWrites}:
 * <ul>
 *     <li>the release of a message its poll claimed after the consumer began stopping - and a release that throws is
 *     only logged: the stuck-message reset returns the message</li>
 *     <li>the acknowledgement, retry and dead-letter mark after a handler that caught the interrupt re-set the flag</li>
 * </ul>
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
        when(durableQueues.releaseUndeliveredMessage(MESSAGE_ID, Duration.ZERO)).thenAnswer(recordInterruptFlag(interruptedDuringRelease, released, Optional.empty()));
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
            // The release's countDown runs before it throws: give the consumer's catch block time to run before the check
            verify(durableQueues, after(500).never()).retryMessage(any(QueueEntryId.class), any(), any(Duration.class));
            assertThat(consumer.isStarted()).as("the consumer is stopped").isFalse();
            verify(durableQueues, times(1).description("the stopped consumer polls no more after the failed release"))
                    .getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class));
            assertThat(handledMessages).as("the message never reaches the handler").isEmpty();
        } finally {
            consumer.stop();
        }
    }

    @Test
    void the_acknowledgement_after_a_handler_re_set_the_interrupt_flag_runs_with_the_flag_cleared_and_restores_it() throws InterruptedException {
        var durableQueues        = mock(DurableQueues.class);
        var interruptedDuringAck = new AtomicReference<Boolean>();
        var interruptedAfterAck  = new AtomicReference<Boolean>();
        var acknowledged         = new CountDownLatch(1);
        when(durableQueues.acknowledgeMessageAsHandled(MESSAGE_ID)).thenAnswer(recordInterruptFlag(interruptedDuringAck, acknowledged, true));
        when(durableQueues.getMessageObserver()).thenReturn(new DurableQueueMessageObserver() {
            @Override
            public void messageHandled(QueuedMessage message, Duration handlerDuration) {
                interruptedAfterAck.set(Thread.currentThread().isInterrupted());
            }
        });
        var consumer = consumerDelivering(durableQueues, queuedMessage(), message -> Thread.currentThread().interrupt());

        consumer.start();
        try {
            assertThat(acknowledged.await(5, TimeUnit.SECONDS)).as("the message is acknowledged").isTrue();
            assertThat(interruptedDuringAck.get()).as("the interrupt flag is cleared while the acknowledgement is written").isFalse();
            await().atMost(Duration.ofSeconds(5)).until(() -> interruptedAfterAck.get() != null);
            assertThat(interruptedAfterAck.get()).as("the handler's interrupt flag is restored after the acknowledgement").isTrue();
        } finally {
            consumer.stop();
        }
    }

    @Test
    void a_manual_redelivery_request_after_a_handler_re_set_the_interrupt_flag_is_written_with_the_flag_cleared() throws InterruptedException {
        var durableQueues          = mock(DurableQueues.class);
        var interruptedDuringRetry = new AtomicReference<Boolean>();
        var retried                = new CountDownLatch(1);
        when(durableQueues.getMessageObserver()).thenReturn(DurableQueueMessageObserver.none());
        when(durableQueues.retryMessage(eq(MESSAGE_ID), any(), any(Duration.class))).thenAnswer(recordInterruptFlag(interruptedDuringRetry, retried, Optional.empty()));
        var queuedMessage = queuedMessage();
        when(queuedMessage.isManuallyMarkedForRedelivery()).thenReturn(true);
        when(queuedMessage.getRedeliveryDelay()).thenReturn(Duration.ofMillis(5));
        var consumer = consumerDelivering(durableQueues, queuedMessage, message -> Thread.currentThread().interrupt());

        consumer.start();
        try {
            assertThat(retried.await(5, TimeUnit.SECONDS)).as("the redelivery is registered").isTrue();
            assertThat(interruptedDuringRetry.get()).as("the interrupt flag is cleared while the redelivery is written").isFalse();
        } finally {
            consumer.stop();
        }
    }

    @Test
    void the_retry_of_a_failed_handler_that_re_set_the_interrupt_flag_is_written_with_the_flag_cleared() throws InterruptedException {
        var durableQueues          = mock(DurableQueues.class);
        var interruptedDuringRetry = new AtomicReference<Boolean>();
        var retried                = new CountDownLatch(1);
        when(durableQueues.getMessageObserver()).thenReturn(DurableQueueMessageObserver.none());
        when(durableQueues.retryMessage(eq(MESSAGE_ID), any(), any(Duration.class))).thenAnswer(recordInterruptFlag(interruptedDuringRetry, retried, Optional.empty()));
        var consumer = consumerDelivering(durableQueues, queuedMessage(), message -> {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Simulated transient handler failure");
        });

        consumer.start();
        try {
            assertThat(retried.await(5, TimeUnit.SECONDS)).as("the retry is registered").isTrue();
            assertThat(interruptedDuringRetry.get()).as("the interrupt flag is cleared while the retry is written").isFalse();
            verify(durableQueues, never()).markAsDeadLetterMessage(any(QueueEntryId.class), any(Throwable.class));
        } finally {
            consumer.stop();
        }
    }

    @Test
    void the_dead_letter_mark_after_a_handler_re_set_the_interrupt_flag_is_written_with_the_flag_cleared() throws InterruptedException {
        var durableQueues               = mock(DurableQueues.class);
        var interruptedDuringDeadLetter = new AtomicReference<Boolean>();
        var deadLettered                = new CountDownLatch(1);
        when(durableQueues.getMessageObserver()).thenReturn(DurableQueueMessageObserver.none());
        when(durableQueues.markAsDeadLetterMessage(eq(MESSAGE_ID), any(Throwable.class))).thenAnswer(recordInterruptFlag(interruptedDuringDeadLetter, deadLettered, Optional.empty()));
        var consumer = consumerDelivering(durableQueues, queuedMessage(), message -> {
            Thread.currentThread().interrupt();
            // On the built-in permanent-error list, so the message is dead-lettered on its first delivery
            throw new IllegalArgumentException("Simulated permanent handler failure");
        });

        consumer.start();
        try {
            assertThat(deadLettered.await(5, TimeUnit.SECONDS)).as("the message is dead-lettered").isTrue();
            assertThat(interruptedDuringDeadLetter.get()).as("the interrupt flag is cleared while the dead-letter mark is written").isFalse();
        } finally {
            consumer.stop();
        }
    }

    private static Answer<Object> recordInterruptFlag(AtomicReference<Boolean> interrupted, CountDownLatch written, Object result) {
        return invocation -> {
            interrupted.set(Thread.currentThread().isInterrupted());
            written.countDown();
            return result;
        };
    }

    private static QueuedMessage queuedMessage() {
        var queuedMessage = mock(QueuedMessage.class);
        when(queuedMessage.getId()).thenReturn(MESSAGE_ID);
        return queuedMessage;
    }

    private static ConsumeFromQueue consumeFromQueue(QueuedMessageHandler handler) {
        return ConsumeFromQueue.builder()
                               .setQueueName(QUEUE_NAME)
                               .setConsumerName("TestConsumer")
                               .setParallelConsumers(1)
                               .setPollingInterval(Duration.ofMillis(10))
                               .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 1))
                               .setQueueMessageHandler(handler)
                               .build();
    }

    private static DefaultDurableQueueConsumer<?, ?, ?> newConsumer(ConsumeFromQueue consumeFromQueue, DurableQueues durableQueues) {
        var dependencies = DurableQueueConsumerDependencies.<DurableQueues, UnitOfWork, UnitOfWorkFactory<UnitOfWork>>builder()
                                                           .setDurableQueues(durableQueues)
                                                           .setRemoveDurableQueueConsumer(consumer -> {
                                                           })
                                                           .setPollingIntervalMs(consumeFromQueue.getPollingInterval().toMillis())
                                                           .build();
        return new DefaultDurableQueueConsumer<>(consumeFromQueue, dependencies) {
        };
    }

    /**
     * A consumer whose poll returns the message once, while the consumer is still started, so it reaches the handler
     */
    private static DefaultDurableQueueConsumer<?, ?, ?> consumerDelivering(DurableQueues durableQueues,
                                                                            QueuedMessage queuedMessage,
                                                                            QueuedMessageHandler handler) {
        var claimed = new AtomicBoolean();
        when(durableQueues.getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class)))
                .thenAnswer(invocation -> claimed.compareAndSet(false, true) ? Optional.of(queuedMessage) : Optional.empty());
        return newConsumer(consumeFromQueue(handler), durableQueues);
    }

    /**
     * A consumer whose poll stops the consumer while it is claiming a message - the window the release closes - and,
     * like a real stop, leaves the interrupt flag set on the polling thread
     */
    private static DefaultDurableQueueConsumer<?, ?, ?> consumerWhosePollClaimsAfterStop(DurableQueues durableQueues,
                                                                                         List<QueuedMessage> handledMessages) {
        var queuedMessage = queuedMessage();
        var consumerRef   = new AtomicReference<DefaultDurableQueueConsumer<?, ?, ?>>();
        when(durableQueues.getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class))).thenAnswer(invocation -> {
            consumerRef.get().stop();
            // stop() interrupts the delivery threads; set the flag explicitly so the test does not depend on when that happens
            Thread.currentThread().interrupt();
            return Optional.of(queuedMessage);
        });
        var consumer = newConsumer(consumeFromQueue(handledMessages::add), durableQueues);
        consumerRef.set(consumer);
        return consumer;
    }
}
