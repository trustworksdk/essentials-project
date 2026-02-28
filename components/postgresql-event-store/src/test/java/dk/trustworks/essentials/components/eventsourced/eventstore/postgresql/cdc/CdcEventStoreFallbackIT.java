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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.EventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypePersistenceStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.CustomerId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.ProductId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.createObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class CdcEventStoreFallbackIT extends AbstractWal2JsonPostgresIT {

    private PostgresqlEventStore<?> eventStore;
    private EventStreamGapHandler<?> gapHandler;
    private JacksonJSONEventSerializer jacksonJSONSerializer;

    @BeforeEach
    void setup() {
        jacksonJSONSerializer = new JacksonJSONEventSerializer(createObjectMapper());
        var eventMapper = new EventProcessorIT.TestPersistableEventMapper();

        var persistenceStrategy =
                new SeparateTablePerAggregateTypePersistenceStrategy(
                        jdbi,
                        unitOfWorkFactory,
                        eventMapper,
                        SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(jacksonJSONSerializer)
                );

        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        gapHandler = new PostgresqlEventStreamGapHandler<>(eventStore, unitOfWorkFactory);
    }

    @Test
    void pollEvents_falls_back_to_polling_when_cdc_inactive() {
        var availability = new CdcAvailability();
        var cdcEventStore = new CdcEventStore(
                eventStore,
                unitOfWorkFactory,
                gapHandler,
                new CdcEventBus(),
                new CdcProperties(),
                availability
        );

        // Persist events
        var orderId = OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(() -> {
            eventStore.appendToStream(
                    ORDERS,
                    orderId,
                    EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,
                    List.of(
                            new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1),
                            new OrderEvent.ProductAddedToOrder(orderId, ProductId.random(), 2),
                            new OrderEvent.ProductRemovedFromOrder(orderId, ProductId.random())
                           )
                                     );
        });

        List<PersistedEvent> received =
                cdcEventStore.pollEvents(
                                ORDERS,
                                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                Optional.of(10),
                                Optional.of(Duration.ofMillis(50)),
                                Optional.empty(),
                                Optional.of(SubscriberId.of("fallback-it")),
                                Optional.empty()
                        )
                             .take(3)
                             .collectList()
                             .block(Duration.ofSeconds(5));

        assertThat(received).isNotNull();
        assertThat(received).hasSize(3);
        assertThat(availability.getFallbackCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void pollEvents_falls_back_when_cdc_failed() {
        var availability = new CdcAvailability();
        availability.failed("slot-test", "wal2json plugin not usable");

        var cdcEventStore = new CdcEventStore(
                eventStore,
                unitOfWorkFactory,
                gapHandler,
                new CdcEventBus(),
                new CdcProperties(),
                availability
        );

        var orderId = OrderId.random();
        unitOfWorkFactory.usingUnitOfWork(() -> {
            eventStore.appendToStream(
                    ORDERS,
                    orderId,
                    EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED,
                    List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), 1))
                                     );
        });

        var first = cdcEventStore.pollEvents(
                        ORDERS,
                        GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                        Optional.of(10),
                        Optional.of(Duration.ofMillis(50)),
                        Optional.empty(),
                        Optional.of(SubscriberId.of("fallback-failed-it")),
                        Optional.empty()
                )
                                 .blockFirst(Duration.ofSeconds(5));

        assertThat(first).isNotNull();
        assertThat(availability.getFallbackCount()).isGreaterThanOrEqualTo(1);
    }
}
