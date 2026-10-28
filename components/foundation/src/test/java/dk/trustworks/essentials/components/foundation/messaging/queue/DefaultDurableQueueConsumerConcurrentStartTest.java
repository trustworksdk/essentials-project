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
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.transaction.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Two {@link DefaultDurableQueueConsumer#start()} calls at the same time must schedule the consumer's pollers once.
 * The queues start a consumer both from their own {@code start()} and from {@code consumeFromQueue} when they are
 * already started, without coordinating, so a consumer registered while the queues start is started from both threads.
 * The start checked a flag it only set after scheduling, so both scheduled their pollers: twice the configured
 * parallel consumers polling the queue.
 * <p>
 * The test holds the first start inside its first schedule until the second start has scheduled a poller too, or
 * until it is clear the second start is waiting for the first.
 */
class DefaultDurableQueueConsumerConcurrentStartTest {
    private static final int PARALLEL_CONSUMERS = 2;

    @Test
    void two_concurrent_starts_schedule_the_pollers_once() throws Exception {
        var scheduler = new HoldingFirstScheduleExecutor();
        var consumeFromQueue = ConsumeFromQueue.builder()
                                               .setQueueName(QueueName.of("TestQueue"))
                                               .setConsumerName("TestConsumer")
                                               .setParallelConsumers(PARALLEL_CONSUMERS)
                                               .setConsumerExecutorService(scheduler)
                                               .setPollingInterval(Duration.ofHours(1))
                                               .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 1))
                                               .setQueueMessageHandler(message -> {
                                               })
                                               .build();
        var dependencies = DurableQueueConsumerDependencies.<DurableQueues, UnitOfWork, UnitOfWorkFactory<UnitOfWork>>builder()
                                                           .setDurableQueues(mock(DurableQueues.class))
                                                           .setRemoveDurableQueueConsumer(consumer -> {
                                                           })
                                                           .setPollingIntervalMs(consumeFromQueue.getPollingInterval().toMillis())
                                                           .build();
        var consumer = new DefaultDurableQueueConsumer<>(consumeFromQueue, dependencies) {
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstStart = executor.submit(consumer::start);
            assertThat(scheduler.firstScheduleEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var secondStart = executor.submit(consumer::start);

            firstStart.get(10, TimeUnit.SECONDS);
            secondStart.get(10, TimeUnit.SECONDS);

            assertThat(scheduler.pollersScheduled).as("pollers scheduled").hasValue(PARALLEL_CONSUMERS);
            assertThat(consumer.isStarted()).isTrue();
        } finally {
            executor.shutdownNow();
            consumer.stop();
            // close() waits for the interrupted start calls to end; stop() shuts the scheduler down only when the consumer started
            executor.close();
            scheduler.close();
        }
    }

    private static class HoldingFirstScheduleExecutor extends ScheduledThreadPoolExecutor {
        final AtomicInteger  pollersScheduled     = new AtomicInteger();
        final CountDownLatch firstScheduleEntered = new CountDownLatch(1);
        final CountDownLatch secondStartScheduled = new CountDownLatch(1);
        volatile Thread      firstScheduler;

        HoldingFirstScheduleExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            if (pollersScheduled.incrementAndGet() == 1) {
                firstScheduler = Thread.currentThread();
                firstScheduleEntered.countDown();
                try {
                    // Never reaches zero when the second start waits for the first, so give up after a while
                    secondStartScheduled.await(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else if (Thread.currentThread() != firstScheduler) {
                secondStartScheduled.countDown();
            }
            return super.scheduleAtFixedRate(command, initialDelay, period, unit);
        }
    }
}
