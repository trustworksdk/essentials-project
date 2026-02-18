/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStorePollingOptimizer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.EventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CdcEventStoreFallbackTest {

    @Test
    void pollEvents_falls_back_to_delegate_when_cdc_inactive() {
        EventStore delegate = mock(EventStore.class);
        EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory = mock(EventStoreUnitOfWorkFactory.class);
        EventStreamGapHandler<?> gapHandler = mock(EventStreamGapHandler.class);

        var availability = new CdcAvailability();
        var cdcEventStore = new CdcEventStore<>(
                delegate,
                unitOfWorkFactory,
                gapHandler,
                new CdcEventBus(),
                new CdcProperties(),
                availability
        );

        var expected = Flux.just(mock(PersistedEvent.class));
        when(delegate.pollEvents(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(expected);

        var result = cdcEventStore.pollEvents(
                AggregateType.of("orders"),
                0L,
                Optional.empty(),
                Optional.of(Duration.ofMillis(50)),
                Optional.empty(),
                Optional.of(SubscriberId.of("sub-1")),
                Optional.of((Function<String, EventStorePollingOptimizer>) name -> null)
        );

        var first = result.blockFirst(Duration.ofSeconds(1));
        assertThat(first).isNotNull();

        verify(delegate, times(1)).pollEvents(any(), anyLong(), any(), any(), any(), any(), any());
        assertThat(availability.getFallbackCount()).isEqualTo(1);
    }
}
