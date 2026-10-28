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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.NoEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventTypeOrName;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AppendedEvents} records at {@link PostgresqlEventStore#appendToStream}, so it sees every append - not only the
 * ones an {@code EventStreamDecider} makes - and each one once, also when a decorating {@link EventStore} delegates to
 * it. The appenders in {@code eventsourced-aggregates} are covered by that module's {@code AppendedEventsIT}
 */
@Testcontainers
class AppendedEventsIT {
    private static final AggregateType ORDERS    = AggregateType.of("Orders");
    private static final AggregateType SHIPMENTS = AggregateType.of("Shipments");

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        // The container is shared by the class, so start every test from an empty database
        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = SeparateTablePerAggregateTypePersistenceStrategy.builder()
                                                                                  .setJdbi(jdbi)
                                                                                  .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                  .setEventMapper((aggregateId, configuration, event, eventOrder) ->
                                                                                                          PersistableEvent.builder()
                                                                                                                          .setEvent(event)
                                                                                                                          .setAggregateType(configuration.aggregateType)
                                                                                                                          .setAggregateId(aggregateId)
                                                                                                                          .setEventTypeOrName(EventTypeOrName.with(event.getClass()))
                                                                                                                          .setEventOrder(eventOrder)
                                                                                                                          .build())
                                                                                  .setAggregateEventStreamConfigurationFactory(standardSingleTenantConfiguration(
                                                                                          EssentialsJSONEventSerializers.create(),
                                                                                          IdentifierColumnType.TEXT,
                                                                                          JSONColumnType.JSONB))
                                                                                  .build();
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(String.class));
        eventStore.addAggregateEventStreamConfiguration(SHIPMENTS, AggregateIdSerializer.serializerFor(String.class));
    }

    @Test
    void a_direct_appendToStream_is_recorded_with_the_global_event_orders_it_was_given() {
        var recorded = AppendedEvents.recordDuring(() -> unitOfWorkFactory.withUnitOfWork(() -> {
            eventStore.appendToStream(ORDERS, "order-1", List.of(new OrderPlaced("order-1"), new OrderAccepted("order-1")));
            return eventStore.appendToStream(ORDERS, "order-2", List.of(new OrderPlaced("order-2"))).eventList();
        }));

        assertThat(recorded.appendedEvents()).extracting(PersistedEvent::aggregateId).containsExactly("order-1", "order-1", "order-2");
        assertThat(recorded.appendedEvents()).extracting(PersistedEvent::eventId).doesNotHaveDuplicates();
        assertThat(recorded.lastAppendedEvent().map(PersistedEvent::eventId)).contains(recorded.result().getFirst().eventId());
        assertThat(recorded.highestGlobalEventOrder(ORDERS)).contains(recorded.result().getFirst().globalEventOrder());
        assertThat(recorded.highestGlobalEventOrder(ORDERS).orElseThrow().longValue()).isEqualTo(3);
    }

    @Test
    void an_append_through_a_decorating_event_store_is_recorded_once() {
        EventStore cdcEventStore = CdcEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                                .setDelegate(eventStore)
                                                .setUnitOfWorkFactory(unitOfWorkFactory)
                                                .setEventStreamGapHandler(new NoEventStreamGapHandler<>())
                                                .setCdcBus(new CdcEventBus())
                                                .setCdcProperties(new CdcProperties())
                                                .setAvailability(new CdcAvailability())
                                                .build();

        var recorded = AppendedEvents.recordDuring(() -> unitOfWorkFactory.withUnitOfWork(() -> cdcEventStore.appendToStream(ORDERS,
                                                                                                                             "order-1",
                                                                                                                             List.of(new OrderPlaced("order-1"), new OrderAccepted("order-1")))
                                                                                                             .eventList()));

        assertThat(recorded.appendedEvents()).extracting(PersistedEvent::eventId).containsExactlyElementsOf(recorded.result().stream().map(PersistedEvent::eventId).toList());
    }

    @Test
    void an_append_a_synchronous_subscriber_makes_on_the_same_thread_during_the_action_is_recorded_after_its_trigger() {
        eventStore.addEventStoreInterceptor(new FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream());
        eventStore.localEventBus().addSyncSubscriber(event -> {
            if (event instanceof PersistedEvents persistedEvents && persistedEvents.commitStage == CommitStage.Flush) {
                persistedEvents.events.stream()
                                      .filter(persistedEvent -> persistedEvent.aggregateType().equals(ORDERS))
                                      .forEach(persistedEvent -> eventStore.appendToStream(SHIPMENTS, "shipment-1", List.of(new ShipmentRequested("shipment-1"))));
            }
        });

        var recorded = AppendedEvents.recordDuring(() -> unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(ORDERS, "order-1", List.of(new OrderPlaced("order-1")))));

        assertThat(recorded.appendedEvents()).extracting(PersistedEvent::aggregateType).containsExactly(ORDERS, SHIPMENTS);
        assertThat(recorded.highestGlobalEventOrder(SHIPMENTS)).isPresent();
    }

    @Test
    void an_append_on_another_thread_and_one_outside_the_recording_are_not_recorded() throws Exception {
        unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(ORDERS, "order-0", List.of(new OrderPlaced("order-0"))));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var recorded = AppendedEvents.recordDuring(() -> {
                try {
                    executor.submit(() -> unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(ORDERS, "order-1", List.of(new OrderPlaced("order-1")))))
                            .get(30, TimeUnit.SECONDS);
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    throw new IllegalStateException(e);
                }
                unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(ORDERS, "order-2", List.of(new OrderPlaced("order-2"))));
                return null;
            });

            assertThat(recorded.appendedEvents()).extracting(PersistedEvent::aggregateId).containsExactly("order-2");
        }
    }

    // ----------------------------------------------------------------------------------------------------- test data

    public record OrderPlaced(String orderId) {
    }

    public record OrderAccepted(String orderId) {
    }

    public record ShipmentRequested(String shipmentId) {
    }
}
