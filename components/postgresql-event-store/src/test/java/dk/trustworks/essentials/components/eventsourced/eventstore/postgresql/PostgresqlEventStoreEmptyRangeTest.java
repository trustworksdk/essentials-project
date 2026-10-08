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
package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamPersistenceStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * An empty {@link LongRange} (N-10) reads nothing and makes no database round trip. The store decides it after the
 * interceptors ran, because an interceptor can replace the range.
 */
class PostgresqlEventStoreEmptyRangeTest {
    private static final AggregateType ORDERS   = AggregateType.of("Orders");
    private static final String        ORDER_ID = "order-1";

    private AggregateEventStreamPersistenceStrategy<SeparateTablePerAggregateEventStreamConfiguration> persistenceStrategy;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration>                    eventStore;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        persistenceStrategy = mock(AggregateEventStreamPersistenceStrategy.class);
        EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> unitOfWorkFactory = mock(EventStoreUnitOfWorkFactory.class);
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory,
                                                persistenceStrategy,
                                                new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver());
    }

    @Test
    void fetchStream_with_an_empty_range_returns_nothing_without_reading() {
        assertThat(eventStore.fetchStream(ORDERS, ORDER_ID, LongRange.empty())).isEmpty();
        assertThat(eventStore.fetchStream(ORDERS, ORDER_ID, LongRange.between(5, 4))).isEmpty();

        verify(persistenceStrategy, never()).loadAggregateEvents(any(), any(), any(), any(), any());
    }

    @Test
    void fetchStream_with_a_non_empty_range_reads() {
        eventStore.fetchStream(ORDERS, ORDER_ID, LongRange.only(0));

        verify(persistenceStrategy).loadAggregateEvents(any(), eq(ORDERS), eq(ORDER_ID), eq(LongRange.only(0)), any());
    }

    @Test
    void fetchStream_decides_on_the_range_the_interceptors_left() {
        var rangesIntercepted = new ArrayList<LongRange>();
        eventStore.addEventStoreInterceptor(new EventStoreInterceptor() {
            @Override
            public <ID> Optional<AggregateEventStream<ID>> intercept(FetchStream<ID> operation,
                                                                      EventStoreInterceptorChain<FetchStream<ID>, Optional<AggregateEventStream<ID>>> eventStoreInterceptorChain) {
                rangesIntercepted.add(operation.getEventOrderRange());
                operation.setEventOrderRange(operation.getEventOrderRange().isEmpty() ? LongRange.from(0) : LongRange.empty());
                return eventStoreInterceptorChain.proceed();
            }
        });

        // Empty in, replaced by a non-empty range: read
        eventStore.fetchStream(ORDERS, ORDER_ID, LongRange.empty());
        verify(persistenceStrategy).loadAggregateEvents(any(), eq(ORDERS), eq(ORDER_ID), eq(LongRange.from(0)), any());

        // Non-empty in, replaced by an empty range: not read
        clearInvocations(persistenceStrategy);
        assertThat(eventStore.fetchStream(ORDERS, ORDER_ID, LongRange.from(0))).isEmpty();
        verify(persistenceStrategy, never()).loadAggregateEvents(any(), any(), any(), any(), any());

        // The interceptors run for an empty range too
        assertThat(rangesIntercepted).containsExactly(LongRange.empty(), LongRange.from(0));
    }

    @Test
    void loadEventsByGlobalOrder_with_an_empty_range_returns_nothing_without_reading() {
        assertThat(eventStore.loadEventsByGlobalOrder(ORDERS, LongRange.empty())).isEmpty();
        assertThat(eventStore.loadEventsByGlobalOrder(ORDERS, LongRange.empty(), List.of())).isEmpty();
        assertThat(eventStore.loadEventsByGlobalOrder(ORDERS, LongRange.empty(), List.of(), TenantId.of("t1"))).isEmpty();
        var omittingOtherTenantsPayloads = new LoadEventsByGlobalOrder(ORDERS, LongRange.between(10, 9), null, null);
        omittingOtherTenantsPayloads.setOnlyLoadPayloadIfEventBelongsToTenant(TenantId.of("t1"));
        assertThat(eventStore.loadEventsByGlobalOrder(omittingOtherTenantsPayloads)).isEmpty();

        verify(persistenceStrategy, never()).loadEventsByGlobalOrder(any(), any(), any(), any(), any());
        verify(persistenceStrategy, never()).loadEventsByGlobalOrderOmittingOtherTenantsPayloads(any(), any(), any(), any(), any());
    }

    @Test
    void loadEventsByGlobalOrder_with_an_empty_range_still_reads_the_additional_global_orders() {
        var additional = List.of(GlobalEventOrder.of(7));
        when(persistenceStrategy.loadEventsByGlobalOrder(any(), any(), any(), any(), any())).thenReturn(Stream.empty());

        eventStore.loadEventsByGlobalOrder(ORDERS, LongRange.empty(), additional).toList();

        verify(persistenceStrategy).loadEventsByGlobalOrder(any(), eq(ORDERS), eq(LongRange.empty()), eq(additional), any());
    }

    @Test
    void loadEventsByGlobalOrder_decides_on_the_range_the_interceptors_left() {
        eventStore.addEventStoreInterceptor(new EventStoreInterceptor() {
            @Override
            public Stream<PersistedEvent> intercept(LoadEventsByGlobalOrder operation,
                                                    EventStoreInterceptorChain<LoadEventsByGlobalOrder, Stream<PersistedEvent>> eventStoreInterceptorChain) {
                operation.setGlobalEventOrderRange(LongRange.empty());
                return eventStoreInterceptorChain.proceed();
            }
        });

        assertThat(eventStore.loadEventsByGlobalOrder(ORDERS, LongRange.from(1))).isEmpty();

        verify(persistenceStrategy, never()).loadEventsByGlobalOrder(any(), any(), any(), any(), any());
    }
}
