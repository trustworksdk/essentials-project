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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.shared.functional.CheckedRunnable;
import org.junit.jupiter.api.*;

import java.io.UncheckedIOException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Container-free tests of {@link AbstractEventProcessor#forwardFailedBatchOneEventPerUnitOfWork}, the replay of a batch
 * a skipping {@link SubscriptionErrorPolicy} gave up on
 */
class ForwardFailedBatchOneEventPerUnitOfWorkTest {
    private final List<Long>          forwarded = new CopyOnWriteArrayList<>();
    private final List<PersistedEvent> batch    = List.of(event(1), event(2), event(3));
    private       AbstractEventProcessor processor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        var unitOfWorkFactory = mock(EventStoreUnitOfWorkFactory.class);
        doAnswer(inv -> {
            ((CheckedRunnable) inv.getArgument(0)).run();
            return null;
        }).when(unitOfWorkFactory).usingUnitOfWork(any(CheckedRunnable.class));
        var eventStore = mock(ConfigurableEventStore.class);
        when(eventStore.getUnitOfWorkFactory()).thenReturn(unitOfWorkFactory);
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(eventStore);
        processor = mock(AbstractEventProcessor.class, withSettings().useConstructor(subscriptionManager, mock(DurableLocalCommandBus.class), List.of())
                                                                     .defaultAnswer(CALLS_REAL_METHODS));
        doReturn("replay-test").when(processor).getProcessorName();
    }

    @Test
    void an_io_failure_ends_the_replay_at_once_and_is_thrown_so_the_batch_is_not_skipped() {
        var ioFailure = new UncheckedIOException(new IOException("An I/O error occurred while sending to the backend"));

        assertThatThrownBy(() -> processor.forwardFailedBatchOneEventPerUnitOfWork(batch, new IllegalStateException("batch failed"), SubscriptionErrorPolicy.skip(),
                                                                                    failingAt(2, ioFailure)))
                .isSameAs(ioFailure);

        assertThat(forwarded).as("event 3 is not tried").containsExactly(1L);
    }

    @Test
    void a_failure_that_is_not_io_still_forwards_the_other_events_and_is_thrown_for_the_skip() {
        var failure = new IllegalArgumentException("cannot be forwarded");

        assertThatThrownBy(() -> processor.forwardFailedBatchOneEventPerUnitOfWork(batch, new IllegalStateException("batch failed"), SubscriptionErrorPolicy.skip(),
                                                                                    failingAt(2, failure)))
                .isSameAs(failure);

        assertThat(forwarded).containsExactly(1L, 3L);
    }

    private java.util.function.Consumer<PersistedEvent> failingAt(long globalOrder, RuntimeException failure) {
        return event -> {
            if (event.globalEventOrder().longValue() == globalOrder) throw failure;
            forwarded.add(event.globalEventOrder().longValue());
        };
    }

    private static PersistedEvent event(long globalOrder) {
        var event = mock(PersistedEvent.class);
        when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(globalOrder));
        return event;
    }
}
