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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link CdcEventStore} as an {@link AwaitedGapInspector}: what {@code awaitProcessed} asks it about a subscriber's gaps
 */
class CdcEventStoreAwaitedGapInspectorTest {
    private static final AggregateType ORDERS     = AggregateType.of("orders");
    private static final SubscriberId  SUBSCRIBER = SubscriberId.of("inspector-test");

    @Test
    void forwards_to_a_delegate_that_is_an_awaited_gap_inspector() {
        ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate = mock(ConfigurableEventStore.class, withSettings().extraInterfaces(AwaitedGapInspector.class));
        when(((AwaitedGapInspector) delegate).isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(7))).thenReturn(true);
        var cdcEventStore = cdcEventStore(delegate, transientGaps());

        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(7))).isTrue();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(8))).isFalse();
    }

    @Test
    void answers_from_the_transient_gaps_its_own_gap_handler_records_when_the_delegate_is_no_awaited_gap_inspector() {
        ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate = mock(ConfigurableEventStore.class);
        var cdcEventStore = cdcEventStore(delegate, transientGaps(GlobalEventOrder.of(3)));

        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(3))).isTrue();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(4))).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static EventStreamGapHandler<?> transientGaps(GlobalEventOrder... gaps) {
        var subscriptionGapHandler = mock(SubscriptionGapHandler.class);
        when(subscriptionGapHandler.getTransientGapsFor(ORDERS)).thenReturn(List.of(gaps));
        EventStreamGapHandler<?> gapHandler = mock(EventStreamGapHandler.class);
        when(gapHandler.gapHandlerFor(any())).thenReturn(subscriptionGapHandler);
        return gapHandler;
    }

    @SuppressWarnings("unchecked")
    private static CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration> cdcEventStore(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate,
                                                                                                  EventStreamGapHandler<?> gapHandler) {
        return new CdcEventStore<>(delegate,
                                   mock(EventStoreUnitOfWorkFactory.class),
                                   gapHandler,
                                   new CdcEventBus(),
                                   new CdcProperties(),
                                   new CdcAvailability());
    }
}
