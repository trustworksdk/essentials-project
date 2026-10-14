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

import dk.trustworks.essentials.components.foundation.messaging.queue.operations.GetNextMessageReadyForDelivery;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A consumer that is stopping shuts its worker pool down before it unregisters from the fetcher, and workers finishing
 * in between fire refill polls. A message such a poll claims must be handed straight back, not left marked as being
 * delivered until the stuck-message reset, and released without being charged a redelivery attempt, since no handler
 * ever saw it.
 * <p>
 * A stopped fetcher can be started again: the stop shuts its poll thread down, and the start replaces it.
 */
class CentralizedMessageFetcherTest {
    private static final QueueName    QUEUE_NAME = QueueName.of("TestQueue");
    private static final QueueEntryId MESSAGE_ID = QueueEntryId.of("message-1");

    private DurableQueues             durableQueues;
    private DurableQueueConsumer      consumer;
    private QueuedMessageHandler      messageHandler;
    private CentralizedMessageFetcher fetcher;

    @BeforeEach
    void setUp() {
        durableQueues = mock(DurableQueues.class);
        when(durableQueues.getMessageObserver()).thenReturn(DurableQueueMessageObserver.none());
        consumer = mock(DurableQueueConsumer.class);
        messageHandler = mock(QueuedMessageHandler.class);
        fetcher = new CentralizedMessageFetcher(durableQueues, 10, List.of());
    }

    @AfterEach
    void tearDown() {
        fetcher.stop();
    }

    private static QueuedMessage orderedMessage(String key) {
        var message = mock(QueuedMessage.class);
        when(message.getId()).thenReturn(MESSAGE_ID);
        when(message.getQueueName()).thenReturn(QUEUE_NAME);
        when(message.getMessage()).thenReturn(OrderedMessage.of("payload", key, 0));
        return message;
    }

    @Test
    void a_message_claimed_for_a_worker_pool_that_rejects_it_is_handed_back_and_frees_its_worker_slot_and_key() {
        when(consumer.isStarted()).thenReturn(true);
        var workerPool = Executors.newSingleThreadExecutor();
        workerPool.shutdownNow();
        var claims = new CopyOnWriteArrayList<GetNextMessageReadyForDelivery>();
        var message = orderedMessage("key-1");
        when(durableQueues.getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class))).thenAnswer(invocation -> {
            claims.add(invocation.getArgument(0));
            return claims.size() == 1 ? Optional.of(message) : Optional.empty();
        });
        fetcher.registerConsumer(QUEUE_NAME, consumer, messageHandler, workerPool, 1);

        fetcher.start();

        verify(durableQueues, timeout(5_000)).releaseUndeliveredMessage(MESSAGE_ID, Duration.ZERO);
        // No handler ran, so the hand-back must not be charged as a redelivery attempt
        verify(durableQueues, never()).retryMessage(any(QueueEntryId.class), any(), any(Duration.class));
        // The slot is free again (a later poll claims) and the ordered key is no longer excluded
        verify(durableQueues, timeout(5_000).atLeast(2)).getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class));
        assertThat(claims.get(1).getExcludeOrderedMessagesWithKey()).isEmpty();
        verify(messageHandler, never()).handle(any());
    }

    @Test
    void a_message_claimed_just_before_its_consumer_began_stopping_is_handed_back_without_reaching_a_worker() throws InterruptedException {
        var started = new AtomicBoolean(true);
        when(consumer.isStarted()).thenAnswer(invocation -> started.get());
        var workerPool = Executors.newSingleThreadExecutor();
        var message    = orderedMessage("key-1");
        when(durableQueues.getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class))).thenAnswer(invocation -> {
            // The consumer's stop() runs between the poll's slot count and its claim
            started.set(false);
            return Optional.of(message);
        });
        fetcher.registerConsumer(QUEUE_NAME, consumer, messageHandler, workerPool, 1);

        try {
            fetcher.start();

            verify(durableQueues, timeout(5_000)).releaseUndeliveredMessage(MESSAGE_ID, Duration.ZERO);
            verify(durableQueues, never()).retryMessage(any(QueueEntryId.class), any(), any(Duration.class));
            Thread.sleep(200);
            verify(durableQueues, times(1)).getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class));
            verify(messageHandler, never()).handle(any());
        } finally {
            workerPool.shutdownNow();
        }
    }

    @Test
    void a_message_claimed_for_a_consumer_that_unregistered_in_between_is_released_without_charging_an_attempt() {
        when(consumer.isStarted()).thenReturn(true);
        var workerPool = Executors.newSingleThreadExecutor();
        var message    = orderedMessage("key-1");
        when(durableQueues.getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class))).thenAnswer(invocation -> {
            // The consumer is cancelled between the poll that claims the message and its hand-off
            fetcher.unregisterConsumer(QUEUE_NAME);
            return Optional.of(message);
        });
        fetcher.registerConsumer(QUEUE_NAME, consumer, messageHandler, workerPool, 1);

        try {
            fetcher.start();

            // Released for one polling interval
            verify(durableQueues, timeout(5_000)).releaseUndeliveredMessage(MESSAGE_ID, Duration.ofMillis(10));
            verify(durableQueues, never()).retryMessage(any(QueueEntryId.class), any(), any(Duration.class));
            verify(messageHandler, never()).handle(any());
        } finally {
            workerPool.shutdownNow();
        }
    }

    @Test
    void a_stopping_consumer_gets_no_messages_claimed_for_it() throws InterruptedException {
        when(consumer.isStarted()).thenReturn(false);
        var workerPool = Executors.newSingleThreadExecutor();
        fetcher.registerConsumer(QUEUE_NAME, consumer, messageHandler, workerPool, 1);

        try {
            fetcher.start();
            Thread.sleep(200);

            verify(durableQueues, never()).getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class));
        } finally {
            workerPool.shutdownNow();
        }
    }

    @Test
    void a_stopped_fetcher_polls_again_when_started_again() {
        when(consumer.isStarted()).thenReturn(true);
        var workerPool = Executors.newSingleThreadExecutor();
        when(durableQueues.getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class))).thenReturn(Optional.empty());
        fetcher.registerConsumer(QUEUE_NAME, consumer, messageHandler, workerPool, 1);

        try {
            fetcher.start();
            verify(durableQueues, timeout(5_000).atLeastOnce()).getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class));
            fetcher.stop();
            assertThat(fetcher.isStarted()).isFalse();
            clearInvocations(durableQueues);

            fetcher.start();

            assertThat(fetcher.isStarted()).isTrue();
            verify(durableQueues, timeout(5_000).atLeast(2)).getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class));
        } finally {
            workerPool.shutdownNow();
        }
    }

    @Test
    void a_poll_still_in_flight_from_before_a_stop_does_not_overlap_the_polls_after_the_next_start() throws InterruptedException {
        when(consumer.isStarted()).thenReturn(true);
        var workerPool       = Executors.newSingleThreadExecutor();
        var firstPollEntered = new CountDownLatch(1);
        var releaseFirstPoll = new CountDownLatch(1);
        var polls            = new AtomicInteger();
        var inFlight         = new AtomicInteger();
        var maxInFlight      = new AtomicInteger();
        when(durableQueues.getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class))).thenAnswer(invocation -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                if (polls.incrementAndGet() == 1) {
                    firstPollEntered.countDown();
                    // Ignores the interrupt from stop(), like a database call that does not react to one
                    var interrupted = false;
                    while (true) {
                        try {
                            releaseFirstPoll.await();
                            break;
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return Optional.empty();
            } finally {
                inFlight.decrementAndGet();
            }
        });
        fetcher.registerConsumer(QUEUE_NAME, consumer, messageHandler, workerPool, 1);

        try {
            fetcher.start();
            assertThat(firstPollEntered.await(5, TimeUnit.SECONDS)).isTrue();
            fetcher.stop();
            fetcher.start();
            // The new poll thread waits for the old one's poll
            Thread.sleep(200);
            assertThat(polls.get()).isEqualTo(1);

            releaseFirstPoll.countDown();

            verify(durableQueues, timeout(5_000).atLeast(3)).getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class));
            assertThat(maxInFlight.get()).isEqualTo(1);
        } finally {
            releaseFirstPoll.countDown();
            workerPool.shutdownNow();
        }
    }
}
