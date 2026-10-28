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

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The framework reports an empty poll through {@link QueuePollingOptimizer#queuePollingReturnedNoMessages(Instant)},
 * so a subclass of {@link SimpleQueuePollingOptimizer} or {@link CentralizedQueuePollingOptimizer} that overrides that
 * overload is called from {@link DefaultDurableQueueConsumer}'s empty-poll path - and an override of only the
 * deprecated no-arg form is not.
 */
class QueuePollingOptimizerSubclassOverrideTest {
    private static final QueueName QUEUE_NAME = QueueName.of("TestQueue");

    @Test
    void a_SimpleQueuePollingOptimizer_subclass_overriding_the_claim_time_overload_is_called_on_an_empty_poll() throws InterruptedException {
        var consumeFromQueue = consumeFromQueue();
        var claimTimes       = new LinkedBlockingQueue<Instant>();
        var noArgCalls       = new CopyOnWriteArrayList<String>();
        var optimizer = new SimpleQueuePollingOptimizer(consumeFromQueue, 10, 10) {
            @Override
            public void queuePollingReturnedNoMessages(Instant claimedAt) {
                claimTimes.add(claimedAt);
                super.queuePollingReturnedNoMessages(claimedAt);
            }

            @SuppressWarnings("deprecation")
            @Override
            public void queuePollingReturnedNoMessages() {
                noArgCalls.add("called");
                super.queuePollingReturnedNoMessages();
            }
        };

        assertTheClaimTimeOverloadIsCalledOnAnEmptyPoll(consumeFromQueue, optimizer, claimTimes, noArgCalls);
    }

    @Test
    void a_CentralizedQueuePollingOptimizer_subclass_overriding_the_claim_time_overload_is_called_on_an_empty_poll() throws InterruptedException {
        var consumeFromQueue = consumeFromQueue();
        var claimTimes       = new LinkedBlockingQueue<Instant>();
        var noArgCalls       = new CopyOnWriteArrayList<String>();
        var optimizer = new CentralizedQueuePollingOptimizer(QUEUE_NAME, 10, 10, 1.0, 0.0) {
            @Override
            public void queuePollingReturnedNoMessages(Instant claimedAt) {
                claimTimes.add(claimedAt);
                super.queuePollingReturnedNoMessages(claimedAt);
            }

            @SuppressWarnings("deprecation")
            @Override
            public void queuePollingReturnedNoMessages() {
                noArgCalls.add("called");
                super.queuePollingReturnedNoMessages();
            }
        };

        assertTheClaimTimeOverloadIsCalledOnAnEmptyPoll(consumeFromQueue, optimizer, claimTimes, noArgCalls);
    }

    private static void assertTheClaimTimeOverloadIsCalledOnAnEmptyPoll(ConsumeFromQueue consumeFromQueue,
                                                                        QueuePollingOptimizer optimizer,
                                                                        BlockingQueue<Instant> claimTimes,
                                                                        List<String> noArgCalls) throws InterruptedException {
        var durableQueues = mock(DurableQueues.class);
        when(durableQueues.getNextMessageReadyForDelivery(any(GetNextMessageReadyForDelivery.class))).thenReturn(Optional.empty());
        var dependencies = DurableQueueConsumerDependencies.<DurableQueues, UnitOfWork, UnitOfWorkFactory<UnitOfWork>>builder()
                                                           .setDurableQueues(durableQueues)
                                                           .setRemoveDurableQueueConsumer(consumer -> {
                                                           })
                                                           .setPollingIntervalMs(consumeFromQueue.getPollingInterval().toMillis())
                                                           .setQueuePollingOptimizer(optimizer)
                                                           .build();
        var consumer = new DefaultDurableQueueConsumer<>(consumeFromQueue, dependencies) {
        };

        var beforeStart = Instant.now();
        consumer.start();
        try {
            var claimedAt = claimTimes.poll(5, TimeUnit.SECONDS);

            assertThat(claimedAt).as("the subclass' override of queuePollingReturnedNoMessages(Instant)").isNotNull();
            assertThat(claimedAt).isAfterOrEqualTo(beforeStart);
            assertThat(noArgCalls).as("the deprecated no-arg form is not what the consumer calls").isEmpty();
        } finally {
            consumer.stop();
        }
    }

    private static ConsumeFromQueue consumeFromQueue() {
        return ConsumeFromQueue.builder()
                               .setQueueName(QUEUE_NAME)
                               .setConsumerName("TestConsumer")
                               .setParallelConsumers(1)
                               .setPollingInterval(Duration.ofMillis(10))
                               .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 1))
                               .setQueueMessageHandler(message -> {
                               })
                               .build();
    }
}
