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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link PartitionedViewEventProcessor#start()} refuses a queue implementation that does not keep an ordered message key
 * in order across instances, and {@code @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)} methods. Both refusals happen
 * before the processor counts as started and before it registers a queue consumer, so a refused processor reports
 * {@code isStarted() == false} and a second {@code start()} refuses again.
 * <p>
 * Mocks throughout, so no database is involved. The shard-owned engine's side is pinned by
 * {@code PartitionedProcessorsOnShardOwnedIT}.
 */
class PartitionedViewEventProcessorTest {
    private static final AggregateType THINGS = AggregateType.of("Things");

    @Test
    void a_partitioned_view_processor_starts_and_consumes_its_queue_where_key_order_is_kept() {
        var durableQueues = durableQueues(true);
        var processor     = new ThingsPartitionedViewProcessor(durableQueues);

        processor.start();

        assertThat(processor.isStarted()).isTrue();
        verify(durableQueues).consumeFromQueue(eq(QueueName.of("ThingsPartitionedViewProcessor:queue")), any(RedeliveryPolicy.class), anyInt(), any(QueuedMessageHandler.class));
    }

    @Test
    void a_partitioned_view_processor_refuses_to_start_where_key_order_is_not_kept_across_instances() {
        var durableQueues = durableQueues(false);
        var processor     = new ThingsPartitionedViewProcessor(durableQueues);

        assertThatThrownBy(processor::start).isInstanceOf(IllegalStateException.class)
                                            .hasMessageContaining("needs a queue implementation that keeps an ordered message key in order across instances")
                                            .hasMessageContaining("essentials.shard-owned-queue.durable-queues-enabled");
        assertThat(processor.isStarted())
                .describedAs("A processor that refused to start must not report itself as started")
                .isFalse();
        // ... and the second attempt must fail the same way instead of being swallowed by the already-started check
        assertThatThrownBy(processor::start).isInstanceOf(IllegalStateException.class)
                                            .hasMessageContaining("needs a queue implementation that keeps an ordered message key in order across instances");
        assertNeverConsumed(durableQueues);
    }

    @Test
    void a_partitioned_view_processor_with_a_NONE_handler_is_rejected_at_start() {
        var durableQueues = durableQueues(true);
        var processor     = new NonTransactionalPartitionedViewProcessor(durableQueues);

        assertThatThrownBy(processor::start).isInstanceOf(IllegalStateException.class)
                                            .hasMessageContaining("UnitOfWorkMode.NONE")
                                            .hasMessageContaining("PartitionedViewEventProcessor");
        assertThat(processor.isStarted())
                .describedAs("A processor that refused to start must not report itself as started")
                .isFalse();
        // ... and the second attempt must fail the same way instead of being swallowed by the already-started check
        assertThatThrownBy(processor::start).isInstanceOf(IllegalStateException.class)
                                            .hasMessageContaining("UnitOfWorkMode.NONE");
        assertNeverConsumed(durableQueues);
    }

    // -------------------------------------------------------------------------------------------------------------------

    private static void assertNeverConsumed(DurableQueues durableQueues) {
        verify(durableQueues, never()).consumeFromQueue(any(QueueName.class), any(RedeliveryPolicy.class), anyInt(), any(QueuedMessageHandler.class));
        verify(durableQueues, never()).consumeFromQueue(any(ConsumeFromQueue.class));
    }

    private static DurableQueues durableQueues(boolean keepsKeyOrderAcrossInstances) {
        var durableQueues = mock(DurableQueues.class);
        when(durableQueues.keepsKeyOrderAcrossInstances()).thenReturn(keepsKeyOrderAcrossInstances);
        when(durableQueues.consumeFromQueue(any(QueueName.class), any(RedeliveryPolicy.class), anyInt(), any(QueuedMessageHandler.class)))
                .thenReturn(mock(DurableQueueConsumer.class));
        return durableQueues;
    }

    private static EventStoreSubscriptionManager subscriptionManager() {
        // An AwaitedGapInspector, so start() logs no unrelated WARN
        var eventStore = mock(EventStore.class, withSettings().extraInterfaces(AwaitedGapInspector.class));
        doReturn(mock(EventStoreUnitOfWorkFactory.class)).when(eventStore).getUnitOfWorkFactory();
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(eventStore);
        return subscriptionManager;
    }

    private static FencedLockManager fencedLockManager() {
        // The processor registers its queue consumer holding the reset lock
        var lockManager = mock(FencedLockManager.class);
        when(lockManager.tryAcquireLock(any(LockName.class), any(Duration.class))).thenAnswer(invocation -> Optional.of(mock(FencedLock.class)));
        return lockManager;
    }

    record SomethingHappened(String id) {
    }

    static class ThingsPartitionedViewProcessor extends PartitionedViewEventProcessor {
        ThingsPartitionedViewProcessor(DurableQueues durableQueues) {
            super(subscriptionManager(), fencedLockManager(), durableQueues, mock(DurableLocalCommandBus.class), List.of());
        }

        @Override
        public String getProcessorName() {
            return "ThingsPartitionedViewProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(THINGS);
        }

        @MessageHandler
        void on(SomethingHappened e) {
        }
    }

    static class NonTransactionalPartitionedViewProcessor extends PartitionedViewEventProcessor {
        NonTransactionalPartitionedViewProcessor(DurableQueues durableQueues) {
            super(subscriptionManager(), fencedLockManager(), durableQueues, mock(DurableLocalCommandBus.class), List.of());
        }

        @Override
        public String getProcessorName() {
            return "NonTransactionalPartitionedViewProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(THINGS);
        }

        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(SomethingHappened e) {
        }
    }
}
