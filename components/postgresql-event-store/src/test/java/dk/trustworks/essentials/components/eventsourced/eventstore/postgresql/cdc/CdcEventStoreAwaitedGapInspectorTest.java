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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcDeliveryTracker.MAX_AWAITED_ORDERS_PER_GAP_END;
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

    @Test
    void does_not_ask_a_gap_handler_that_records_no_gaps() {
        ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate = mock(ConfigurableEventStore.class);
        var gapHandler = spy(new NoEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>());
        var cdcEventStore = cdcEventStore(delegate, gapHandler);

        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(3))).isFalse();
        verify(gapHandler, never()).gapHandlerFor(any());
    }

    @Test
    void answers_from_the_middles_a_subscription_left_for_the_next_one_when_neither_the_delegate_nor_the_gap_handler_awaits_them() {
        ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate = mock(ConfigurableEventStore.class, withSettings().extraInterfaces(AwaitedGapInspector.class));
        var cdcEventStore = cdcEventStore(delegate, transientGaps());
        var middles = cdcEventStore.gapMiddlesAwaitedAcrossSubscribes();
        var subscribe = middles.subscribe(SUBSCRIBER, ORDERS, 1, Duration.ofMinutes(2));
        subscribe.await(LongRange.between(100, 200));
        middles.subscribeEnded(subscribe);

        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(150))).isTrue();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(201))).isFalse();
        assertThat(cdcEventStore.isAwaitedAsGap(SubscriberId.of("another-subscriber"), ORDERS, GlobalEventOrder.of(150))).isFalse();
    }

    @Test
    void answers_from_the_running_subscription_for_the_middle_of_a_wide_gap_it_found_itself_until_the_subscription_ends() {
        ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate = mock(ConfigurableEventStore.class, withSettings().extraInterfaces(AwaitedGapInspector.class));
        var cdcEventStore = cdcEventStore(delegate, transientGaps());
        var tracker = CdcDeliveryTracker.startingAfter("inspector-test", 0);
        tracker.markDelivered(1);
        // Opens a gap wider than twice MAX_AWAITED_ORDERS_PER_GAP_END: its middle is awaited in memory only, by the tracker
        long aboveTheGap = 2 + 2L * MAX_AWAITED_ORDERS_PER_GAP_END + 100;
        tracker.markDelivered(aboveTheGap);
        long inTheMiddle = 2 + MAX_AWAITED_ORDERS_PER_GAP_END + 50;
        cdcEventStore.deliveryTrackerRunning(SUBSCRIBER, ORDERS, tracker);

        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(inTheMiddle))).as("in the middle, not delivered").isTrue();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(1))).as("delivered").isFalse();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(aboveTheGap))).as("delivered").isFalse();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(aboveTheGap + 1))).as("above the highest delivered").isFalse();
        assertThat(cdcEventStore.isAwaitedAsGap(SubscriberId.of("another-subscriber"), ORDERS, GlobalEventOrder.of(inTheMiddle))).isFalse();

        // Delivered as a gap fill: still awaited until the subscriber is done with it
        tracker.markDelivered(inTheMiddle);
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(inTheMiddle))).as("delivered, the subscriber not done with it").isTrue();
        tracker.doneWith(List.of(eventWithGlobalOrder(inTheMiddle)));
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(inTheMiddle))).as("the subscriber is done with it").isFalse();

        // The subscription ends: the tracker is no longer asked
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(inTheMiddle + 1))).isTrue();
        cdcEventStore.deliveryTrackerEnded(SUBSCRIBER, ORDERS, tracker);
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(inTheMiddle + 1))).isFalse();
        assertThat(cdcEventStore.runningDeliveryTrackerCount()).isZero();
    }

    private static PersistedEvent eventWithGlobalOrder(long globalOrder) {
        var event = mock(PersistedEvent.class);
        when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(globalOrder));
        return event;
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
