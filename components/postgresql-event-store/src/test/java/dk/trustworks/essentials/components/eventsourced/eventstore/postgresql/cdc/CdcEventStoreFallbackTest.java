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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
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

    /**
     * When CDC is not ACTIVE at subscribe time the subscription must still be delivered via
     * delegate polling, and the fallback signal must be recorded. Since P9 the inactive path no
     * longer terminally early-returns plain polling — it returns the adaptive live source seeded at
     * the resume point, whose fallback branch IS {@code eventStore.pollEvents(resume, ...)} while
     * availability stays non-ACTIVE (and would transparently cut over to the CDC bus if it became
     * ACTIVE). Delivery timing while inactive is unchanged (no head snapshot, no backfill). This
     * asserts the inactive-at-subscribe delivery contract still holds end-to-end.
     */
    @Test
    void pollEvents_falls_back_to_delegate_when_cdc_inactive() {
        EventStore delegate = mock(EventStore.class);
        EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory = mock(EventStoreUnitOfWorkFactory.class);
        EventStreamGapHandler<?> gapHandler = mock(EventStreamGapHandler.class);

        var availability = new CdcAvailability(); // stays INACTIVE → fallback-to-polling path
        var cdcEventStore = new CdcEventStore(
                delegate,
                unitOfWorkFactory,
                gapHandler,
                new CdcEventBus(),
                new CdcProperties(),
                availability
        );

        // The adaptive source applies a `globalOrder > lastSeen` ordering filter and a tenant
        // filter to the live (here: polling-fallback) events, so the event needs those fields.
        var liveEvent = mock(PersistedEvent.class);
        when(liveEvent.globalEventOrder()).thenReturn(GlobalEventOrder.of(1));
        when(liveEvent.aggregateType()).thenReturn(AggregateType.of("orders"));
        when(liveEvent.tenant()).thenReturn(Optional.empty());
        when(delegate.pollEvents(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.just(liveEvent));

        var result = cdcEventStore.pollEvents(
                AggregateType.of("orders"),
                0L,
                Optional.empty(),
                Optional.of(Duration.ofMillis(50)),
                Optional.empty(),
                Optional.of(SubscriberId.of("sub-1")),
                Optional.of((Function<String, EventStorePollingOptimizer>) name -> null)
        );

        var first = result.blockFirst(Duration.ofSeconds(2));
        assertThat(first).isNotNull();
        assertThat(first.globalEventOrder()).isEqualTo(GlobalEventOrder.of(1));

        // Delivery while inactive is served by the delegate poll (the adaptive source's fallback
        // branch), and the signal is recorded exactly once. CDC has never been active here, which is the
        // startup case, so it lands on the warm-up counter rather than being reported as a CDC regression.
        verify(delegate, atLeastOnce()).pollEvents(any(), anyLong(), any(), any(), any(), any(), any());
        assertThat(availability.getWarmupPollCount()).isEqualTo(1);
        assertThat(availability.getFallbackCount()).isZero();
    }

    /**
     * The counterpart to the test above: once CDC has been active, a subscription that starts while it is
     * unavailable is a genuine regression and must be counted as a fallback.
     */
    @Test
    @SuppressWarnings("unchecked")
    void pollEvents_after_cdc_has_been_active_records_a_fallback() {
        EventStore delegate = mock(EventStore.class);
        EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory = mock(EventStoreUnitOfWorkFactory.class);
        EventStreamGapHandler<?> gapHandler = mock(EventStreamGapHandler.class);

        var availability = new CdcAvailability();
        availability.active("slot_a");
        availability.inactive("slot_a", "tailer stopped");

        var cdcEventStore = new CdcEventStore(
                delegate,
                unitOfWorkFactory,
                gapHandler,
                new CdcEventBus(),
                new CdcProperties(),
                availability
        );

        var liveEvent = mock(PersistedEvent.class);
        when(liveEvent.globalEventOrder()).thenReturn(GlobalEventOrder.of(1));
        when(liveEvent.aggregateType()).thenReturn(AggregateType.of("orders"));
        when(liveEvent.tenant()).thenReturn(Optional.empty());
        when(delegate.pollEvents(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.just(liveEvent));

        var result = cdcEventStore.pollEvents(
                AggregateType.of("orders"),
                0L,
                Optional.empty(),
                Optional.of(Duration.ofMillis(50)),
                Optional.empty(),
                Optional.of(SubscriberId.of("sub-1")),
                Optional.of((Function<String, EventStorePollingOptimizer>) name -> null)
        );

        assertThat(result.blockFirst(Duration.ofSeconds(2))).isNotNull();
        assertThat(availability.getFallbackCount()).isEqualTo(1);
        assertThat(availability.getWarmupPollCount()).isZero();
    }

    /**
     * CdcEventStore is a decorator: the highest/lowest global-order lookups must delegate to the
     * wrapped store. findLowestGlobalEventOrderPersisted previously returned Optional.empty()
     * unconditionally, making callers see "no events persisted" even when events existed.
     */
    @Test
    @SuppressWarnings("unchecked")
    void findHighest_and_findLowest_global_event_order_delegate_to_wrapped_store() {
        EventStore delegate = mock(EventStore.class);
        var cdcEventStore = new CdcEventStore(
                delegate,
                mock(EventStoreUnitOfWorkFactory.class),
                mock(EventStreamGapHandler.class),
                new CdcEventBus(),
                new CdcProperties(),
                new CdcAvailability()
        );

        var orders = AggregateType.of("orders");
        when(delegate.findHighestGlobalEventOrderPersisted(orders)).thenReturn(Optional.of(GlobalEventOrder.of(42)));
        when(delegate.findLowestGlobalEventOrderPersisted(orders)).thenReturn(Optional.of(GlobalEventOrder.of(7)));

        assertThat(cdcEventStore.findHighestGlobalEventOrderPersisted(orders)).contains(GlobalEventOrder.of(42));
        assertThat(cdcEventStore.findLowestGlobalEventOrderPersisted(orders)).contains(GlobalEventOrder.of(7));

        verify(delegate).findHighestGlobalEventOrderPersisted(orders);
        verify(delegate).findLowestGlobalEventOrderPersisted(orders);
    }
}
