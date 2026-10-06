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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.shared.measurement.MeasurementTaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.util.List;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer.MeasurementEventStoreSubscriptionObserver.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MeasurementEventStoreSubscriptionObserverTest {
    private static final SubscriberId  SUBSCRIBER_ID  = SubscriberId.of("OrderProjector");
    private static final AggregateType AGGREGATE_TYPE = AggregateType.of("Orders");

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    /**
     * The counter is an incident signal: it must not depend on execution-time recording, which is what
     * {@link MeasurementTaker#none()} switches off
     */
    @Test
    void a_failed_event_is_counted_even_when_timings_are_not_recorded() {
        var observer = new MeasurementEventStoreSubscriptionObserver(MeasurementTaker.none(), "orders-module", meterRegistry);

        observer.handleEventFailed(event(7, "OrderAccepted"), mock(PersistedEventHandler.class), new IllegalStateException("boom"), subscription());
        observer.handleEventFailed(event(8, "OrderAccepted"), mock(PersistedEventHandler.class), new IllegalStateException("boom"), subscription());

        var counter = meterRegistry.find(HANDLE_EVENT_FAILED_METRIC)
                                   .tag("subscriber_id", SUBSCRIBER_ID.toString())
                                   .tag("aggregate_type", AGGREGATE_TYPE.toString())
                                   .tag("event_type", "OrderAccepted")
                                   .tag(MODULE_TAG_NAME, "orders-module")
                                   .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2);
        assertThat(counter.getId().getTag("event_handler")).isNotBlank();
    }

    @Test
    void a_failed_batch_counts_each_of_its_events() {
        var observer = new MeasurementEventStoreSubscriptionObserver(MeasurementTaker.none(), null, meterRegistry);

        observer.handleEventBatchFailed(List.of(event(1, "OrderAdded"), event(2, "OrderAccepted"), event(3, "OrderAccepted")),
                                        mock(BatchedPersistedEventHandler.class),
                                        new IllegalStateException("boom"),
                                        subscription());

        assertThat(meterRegistry.find(HANDLE_EVENT_FAILED_METRIC).tag("event_type", "OrderAdded").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find(HANDLE_EVENT_FAILED_METRIC).tag("event_type", "OrderAccepted").counter().count()).isEqualTo(2);
        // No module tag when no module is configured
        assertThat(meterRegistry.find(HANDLE_EVENT_FAILED_METRIC).counter().getId().getTag(MODULE_TAG_NAME)).isNull();
    }

    @Test
    void an_in_transaction_failure_has_its_own_counter() {
        var observer = new MeasurementEventStoreSubscriptionObserver(MeasurementTaker.none(), null, meterRegistry);

        observer.handleEventFailed(event(5, "OrderAccepted"), mock(TransactionalPersistedEventHandler.class), new IllegalStateException("boom"), subscription());

        assertThat(meterRegistry.find(HANDLE_EVENT_TRANSACTIONAL_FAILED_METRIC).counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find(HANDLE_EVENT_FAILED_METRIC).counter()).isNull();
    }

    @Test
    void without_a_meter_registry_nothing_is_counted_and_nothing_fails() {
        var observer = new MeasurementEventStoreSubscriptionObserver(MeasurementTaker.none(), null);

        assertThatNoException().isThrownBy(() -> observer.handleEventFailed(event(5, "OrderAccepted"), mock(PersistedEventHandler.class), new IllegalStateException("boom"), subscription()));
        assertThat(meterRegistry.getMeters()).isEmpty();
    }

    private static EventStoreSubscription subscription() {
        var subscription = mock(EventStoreSubscription.class);
        when(subscription.subscriberId()).thenReturn(SUBSCRIBER_ID);
        when(subscription.aggregateType()).thenReturn(AGGREGATE_TYPE);
        return subscription;
    }

    private static PersistedEvent event(long globalEventOrder, String eventType) {
        var event = mock(PersistedEvent.class, RETURNS_DEEP_STUBS);
        when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(globalEventOrder));
        when(event.event().getEventTypeOrNamePersistenceValue()).thenReturn(eventType);
        return event;
    }
}
