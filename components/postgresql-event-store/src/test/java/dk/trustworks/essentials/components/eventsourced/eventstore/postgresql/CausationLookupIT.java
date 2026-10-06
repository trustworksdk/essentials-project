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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.CausationIndexNotEnabledException;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.DurableSubscriptionRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventTypeOrName;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.*;

/**
 * The read path of event causation: {@link EventStore#findEvent(EventId)} ("what caused this?") and
 * {@link EventStore#loadEventsCausedBy(EventId)} ("what did this cause?"), and the opt-in caused-by-event-id index the
 * second one requires. The event-id columns are UUID-typed here, which is the configuration where a cause that is not a
 * UUID can matter.
 */
@Testcontainers
class CausationLookupIT {
    private static final AggregateType ORDERS    = AggregateType.of("Orders");
    private static final AggregateType SHIPMENTS = AggregateType.of("Shipments");
    // The UUID identifier column type types the aggregate-id columns too
    private static final String        ORDER_1    = "6a3f1c2e-0b7d-4c55-9a51-1f0e9c3d2b11";
    private static final String        SHIPMENT_1 = "8d2e4b6a-3c1f-4e77-b0a2-5c9d7e1f4a22";

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private Jdbi                                                                    jdbi;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private SeparateTablePerAggregateTypePersistenceStrategy                        persistenceStrategy;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        // The container is shared by the class, so start every test from an empty database
        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        persistenceStrategy = SeparateTablePerAggregateTypePersistenceStrategy.builder()
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
                                                                                      IdentifierColumnType.UUID,
                                                                                      JSONColumnType.JSONB))
                                                                              .setPersistableEventEnrichers(List.of(new CausationPersistableEventEnricher()))
                                                                              .build();
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(String.class));
        eventStore.addAggregateEventStreamConfiguration(SHIPMENTS, AggregateIdSerializer.serializerFor(String.class));
    }

    // ---------------------------------------------------------------------------------------------- what caused this?

    @Test
    void findEvent_finds_an_event_by_its_id_in_whichever_event_stream_holds_it() {
        var orderPlaced  = append(Optional.empty(), ORDERS, ORDER_1, new OrderPlaced(ORDER_1));
        var shipmentMade = append(Optional.of(orderPlaced), SHIPMENTS, SHIPMENT_1, new ShipmentRequested(SHIPMENT_1));

        var found = unitOfWorkFactory.withUnitOfWork(() -> eventStore.findEvent(shipmentMade));

        assertThat(found).hasValueSatisfying(event -> {
            assertThat((Object) event.aggregateType()).isEqualTo(SHIPMENTS);
            assertThat(event.causedByEventId()).contains(orderPlaced);
        });
    }

    @Test
    void walking_back_from_an_event_reaches_the_event_that_caused_it() {
        var orderPlaced  = append(Optional.empty(), ORDERS, ORDER_1, new OrderPlaced(ORDER_1));
        var shipmentMade = append(Optional.of(orderPlaced), SHIPMENTS, SHIPMENT_1, new ShipmentRequested(SHIPMENT_1));

        var cause = unitOfWorkFactory.withUnitOfWork(() -> eventStore.findEvent(shipmentMade)
                                                                     .flatMap(PersistedEvent::causedByEventId)
                                                                     .flatMap(eventStore::findEvent));

        assertThat(cause.map(PersistedEvent::eventId)).contains(orderPlaced);
        assertThat(cause.flatMap(PersistedEvent::causedByEventId)).isEmpty();
    }

    @Test
    void findEvent_returns_empty_for_an_unknown_id_and_for_one_no_UUID_typed_table_can_hold() {
        append(Optional.empty(), ORDERS, ORDER_1, new OrderPlaced(ORDER_1));

        assertThat(unitOfWorkFactory.withUnitOfWork(() -> eventStore.findEvent(EventId.random()))).isEmpty();
        assertThat(unitOfWorkFactory.withUnitOfWork(() -> eventStore.findEvent(EventId.of("not-a-uuid")))).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ what did this cause?

    @Test
    void loadEventsCausedBy_refuses_without_the_index_rather_than_scanning_every_table() {
        var orderPlaced = append(Optional.empty(), ORDERS, ORDER_1, new OrderPlaced(ORDER_1));

        assertThatThrownBy(() -> unitOfWorkFactory.withUnitOfWork(() -> eventStore.loadEventsCausedBy(orderPlaced)))
                .hasStackTraceContaining("essentials.eventstore.causation.index-enabled");
    }

    @Test
    void loadEventsCausedBy_returns_every_event_the_cause_led_to_across_aggregate_types() {
        persistenceStrategy.enableCausationIndex();
        var orderPlaced = append(Optional.empty(), ORDERS, ORDER_1, new OrderPlaced(ORDER_1));
        var shipment    = append(Optional.of(orderPlaced), SHIPMENTS, SHIPMENT_1, new ShipmentRequested(SHIPMENT_1));
        var accepted    = append(Optional.of(orderPlaced), ORDERS, ORDER_1, new OrderAccepted(ORDER_1));
        append(Optional.of(shipment), SHIPMENTS, SHIPMENT_1, new ShipmentDispatched(SHIPMENT_1));

        var caused = unitOfWorkFactory.withUnitOfWork(() -> eventStore.loadEventsCausedBy(orderPlaced));

        // Table-name order across aggregate types; the grandchild is not a direct effect
        assertThat(caused).extracting(PersistedEvent::eventId).containsExactly(accepted, shipment);
        assertThat(unitOfWorkFactory.withUnitOfWork(() -> eventStore.loadEventsCausedBy(EventId.random()))).isEmpty();
    }

    // -------------------------------------------------------------------------------------------------------- index

    @Test
    void the_index_is_partial_and_created_for_tables_registered_before_and_after_it_is_enabled() {
        persistenceStrategy.enableCausationIndex();
        eventStore.addAggregateEventStreamConfiguration(AggregateType.of("Invoices"), AggregateIdSerializer.serializerFor(String.class));

        var indexes = jdbi.withHandle(handle -> handle.createQuery("SELECT tablename, indexdef FROM pg_indexes WHERE indexname LIKE '%caused_by_event_id'")
                                                      .mapToMap()
                                                      .list());

        assertThat(indexes).extracting(row -> row.get("tablename"))
                           .containsExactlyInAnyOrder("orders_events", "shipments_events", "invoices_events");
        assertThat(indexes).allSatisfy(row -> assertThat((String) row.get("indexdef")).contains("WHERE (caused_by_event_id IS NOT NULL)"));
    }

    @Test
    void no_index_is_created_unless_it_is_enabled() {
        var indexes = jdbi.withHandle(handle -> handle.createQuery("SELECT count(*) FROM pg_indexes WHERE indexname LIKE '%caused_by_event_id'")
                                                      .mapTo(Long.class)
                                                      .one());

        assertThat(indexes).isZero();
    }

    @Test
    void an_index_built_by_hand_concurrently_with_the_documented_statement_is_found_in_place() {
        var statement = SeparateTablePerAggregateTypePersistenceStrategy.causationIndexStatement(persistenceStrategy.getAggregateEventStreamConfiguration(ORDERS));
        jdbi.useHandle(handle -> handle.execute(statement.replace("CREATE INDEX IF NOT EXISTS", "CREATE INDEX CONCURRENTLY IF NOT EXISTS")));

        assertThatCode(persistenceStrategy::enableCausationIndex).doesNotThrowAnyException();
        var ordersIndexes = jdbi.withHandle(handle -> handle.createQuery("SELECT count(*) FROM pg_indexes WHERE tablename = 'orders_events' AND indexname LIKE '%caused_by_event_id'")
                                                            .mapTo(Long.class)
                                                            .one());
        assertThat(ordersIndexes).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------- the admin API's walk

    @Test
    void the_causation_chain_walks_back_from_an_event_to_the_root_of_its_chain() {
        var root       = append(Optional.empty(), ORDERS, ORDER_1, new OrderPlaced(ORDER_1));
        var child      = append(Optional.of(root), SHIPMENTS, SHIPMENT_1, new ShipmentRequested(SHIPMENT_1));
        var grandchild = append(Optional.of(child), SHIPMENTS, SHIPMENT_1, new ShipmentDispatched(SHIPMENT_1));

        var chain = api().findCausationChain("principal", grandchild, 20);

        assertThat(chain).extracting(ApiCausationEvent::eventId).containsExactly(grandchild.toString(), child.toString(), root.toString());
        assertThat(chain).extracting(ApiCausationEvent::aggregateType).containsExactly("Shipments", "Shipments", "Orders");
        assertThat(chain.getLast().causedByEventId()).isNull();
    }

    @Test
    void the_causation_chain_stops_at_maxDepth_and_at_a_cause_no_registered_event_stream_holds() {
        var root       = append(Optional.empty(), ORDERS, ORDER_1, new OrderPlaced(ORDER_1));
        var child      = append(Optional.of(root), SHIPMENTS, SHIPMENT_1, new ShipmentRequested(SHIPMENT_1));
        var grandchild = append(Optional.of(child), SHIPMENTS, SHIPMENT_1, new ShipmentDispatched(SHIPMENT_1));
        var orphan     = append(Optional.of(EventId.random()), ORDERS, ORDER_1, new OrderAccepted(ORDER_1));

        assertThat(api().findCausationChain("principal", grandchild, 2)).extracting(ApiCausationEvent::eventId)
                                                                       .containsExactly(grandchild.toString(), child.toString());
        assertThat(api().findCausationChain("principal", orphan, 20)).extracting(ApiCausationEvent::eventId)
                                                                    .containsExactly(orphan.toString());
        assertThat(api().findCausationChain("principal", EventId.random(), 20)).isEmpty();
        assertThatThrownBy(() -> api().findCausationChain("principal", root, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api().findCausationChain("principal", root, EventStoreApi.MAX_CAUSATION_CHAIN_DEPTH + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_admin_API_lists_caused_events_once_the_index_is_enabled() {
        var root = append(Optional.empty(), ORDERS, ORDER_1, new OrderPlaced(ORDER_1));
        append(Optional.of(root), SHIPMENTS, SHIPMENT_1, new ShipmentRequested(SHIPMENT_1));

        assertThatThrownBy(() -> api().findEventsCausedBy("principal", root)).isInstanceOf(CausationIndexNotEnabledException.class);

        persistenceStrategy.enableCausationIndex();
        assertThat(api().findEventsCausedBy("principal", root)).extracting(ApiCausationEvent::eventType)
                                                              .singleElement()
                                                              .asString()
                                                              .endsWith("ShipmentRequested");
    }

    @Test
    void an_aggregates_most_recent_events_are_listed_oldest_first_with_their_causes() {
        var placed   = append(Optional.empty(), ORDERS, ORDER_1, new OrderPlaced(ORDER_1));
        var accepted = append(Optional.of(placed), ORDERS, ORDER_1, new OrderAccepted(ORDER_1));
        var third    = append(Optional.of(accepted), ORDERS, ORDER_1, new OrderAccepted(ORDER_1));

        assertThat(api().findAggregateEvents("principal", ORDERS, ORDER_1, 100)).extracting(ApiCausationEvent::eventId)
                                                                              .containsExactly(placed.toString(), accepted.toString(), third.toString());
        var lastTwo = api().findAggregateEvents("principal", ORDERS, ORDER_1, 2);
        assertThat(lastTwo).extracting(ApiCausationEvent::eventId).containsExactly(accepted.toString(), third.toString());
        assertThat(lastTwo).extracting(ApiCausationEvent::causedByEventId).containsExactly(placed.toString(), accepted.toString());

        assertThat(api().findAggregateEvents("principal", ORDERS, "00000000-0000-0000-0000-000000000000", 100)).isEmpty();
        assertThat(api().findAggregateEvents("principal", AggregateType.of("NotRegistered"), ORDER_1, 100)).isEmpty();
        assertThatThrownBy(() -> api().findAggregateEvents("principal", ORDERS, ORDER_1, EventStoreApi.MAX_AGGREGATE_EVENTS + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private EventStoreApi api() {
        return new DefaultEventStoreApi(new EssentialsSecurityProvider.AllAccessSecurityProvider(),
                                        eventStore,
                                        org.mockito.Mockito.mock(DurableSubscriptionRepository.class));
    }

    // --------------------------------------------------------------------------------------------- unstorable causes

    @Test
    void a_cause_a_UUID_typed_table_cannot_store_is_dropped_rather_than_failing_the_append() {
        var appended = append(Optional.of(EventId.of("not-a-uuid")), ORDERS, ORDER_1, new OrderPlaced(ORDER_1));

        var persisted = unitOfWorkFactory.withUnitOfWork(() -> eventStore.findEvent(appended));
        assertThat(persisted).hasValueSatisfying(event -> assertThat(event.causedByEventId()).isEmpty());
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    private EventId append(Optional<EventId> cause, AggregateType aggregateType, String aggregateId, Object event) {
        return CausationContext.where(cause)
                               .call(() -> unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(aggregateType, aggregateId, event)
                                                                                            .eventList()
                                                                                            .getLast()
                                                                                            .eventId()));
    }

    record OrderPlaced(String orderId) {
    }

    record OrderAccepted(String orderId) {
    }

    record ShipmentRequested(String shipmentId) {
    }

    record ShipmentDispatched(String shipmentId) {
    }
}
