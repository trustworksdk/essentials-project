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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.interceptor.InterceptorOrder;
import dk.trustworks.essentials.types.LongRange;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongUnaryOperator;
import java.util.stream.Stream;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@link EventStoreInterceptor}s that change what they intercept
 * (<code>docs/design/interceptor-operation-replacement.md</code>): the input is replaced through the operation's
 * setters before {@code proceed()}, the output by returning a different value, built with
 * {@link PersistedEvent#withEvent(EventJSON)} / {@link PersistedEvent#withMetaData(EventMetaDataJSON)}.
 */
@Testcontainers
class EventStoreInterceptorReplacementIT {
    private static final AggregateType ORDERS = AggregateType.of("Orders");

    @Container
    private final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private JSONEventSerializer                                                     serializer;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                               postgreSQLContainer.getUsername(),
                               postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        serializer = EssentialsJSONEventSerializers.create();
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new TestPersistableEventMapper(),
                                                                                       standardSingleTenantConfiguration(aggregateType -> aggregateType + "_events",
                                                                                                                         EventStreamTableColumnNames.defaultColumnNames(),
                                                                                                                         serializer,
                                                                                                                         IdentifierColumnType.UUID,
                                                                                                                         JSONColumnType.JSONB));
        eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                         .setPersistenceStrategy(persistenceStrategy)
                                         .setEventStreamGapHandlerFactory(eventStore -> new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory))
                                         .setEventStoreSubscriptionObserver(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver())
                                         .build();
        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(OrderId.class));
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
    }

    @Test
    void an_interceptor_that_replaces_the_events_to_append_gets_the_replacement_persisted() {
        eventStore.addEventStoreInterceptor(new MultiplyOrderNumberOnAppend());
        var orderId = OrderId.random();

        var appended = unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(ORDERS, orderId, List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), 12))));

        assertThat(orderNumberOf(appended.eventList().get(0))).isEqualTo(120);
        var stored = unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(ORDERS, orderId).orElseThrow().eventList());
        assertThat(stored).hasSize(1);
        assertThat(orderNumberOf(stored.get(0))).isEqualTo(120);
        assertThat(stored.get(0).event().getJson()).contains("120");
    }

    @Test
    void two_ordered_interceptors_replace_in_interceptor_order_and_the_second_sees_the_first_replacement() {
        // Registered in reverse order: @InterceptorOrder decides, not registration order
        var addSeven = new AddSevenToOrderNumberOnAppend();
        eventStore.addEventStoreInterceptor(addSeven);
        eventStore.addEventStoreInterceptor(new MultiplyOrderNumberOnAppend());
        var orderId = OrderId.random();

        unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(ORDERS, orderId, List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), 12))));

        // Multiply (order 1) ran first and add-seven (order 2) saw its replacement: (12 * 10) + 7, not (12 + 7) * 10
        assertThat(addSeven.orderNumbersSeen).containsExactly(120L);
        var stored = unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(ORDERS, orderId).orElseThrow().eventList());
        assertThat(orderNumberOf(stored.get(0))).isEqualTo(127);
    }

    @Test
    void a_read_side_interceptor_returns_transformed_events_from_fetch_stream_load_event_and_load_events_by_global_order() {
        var orderId = OrderId.random();
        var appended = unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(ORDERS, orderId,
                                                                                        List.of(new OrderEvent.OrderAdded(orderId, CustomerId.random(), 5),
                                                                                                new OrderEvent.OrderAccepted(orderId)))
                                                                        .eventList());
        eventStore.addEventStoreInterceptor(new NegateOrderNumberOnRead(serializer));

        unitOfWorkFactory.usingUnitOfWork(() -> {
            var fetched = eventStore.fetchStream(ORDERS, orderId).orElseThrow();
            assertThat(fetched.eventOrderRangeIncluded()).isEqualTo(LongRange.from(0));
            var fetchedEvents = fetched.eventList();
            assertTransformed(fetchedEvents.get(0), appended.get(0));
            assertThat((CharSequence) fetchedEvents.get(1).eventId()).isEqualTo(appended.get(1).eventId());
            assertThat(fetchedEvents.get(1).metaData().getJsonDeserialized()).isEqualTo(Optional.of(EventMetaData.of("Key1", "Value1")));

            assertTransformed(eventStore.loadEvent(ORDERS, appended.get(0).eventId()).orElseThrow(), appended.get(0));

            var byGlobalOrder = eventStore.loadEventsByGlobalOrder(ORDERS, LongRange.from(1)).toList();
            assertThat(byGlobalOrder).hasSize(2);
            assertTransformed(byGlobalOrder.get(0), appended.get(0));
        });
    }

    private void assertTransformed(PersistedEvent transformed, PersistedEvent original) {
        assertThat(orderNumberOf(transformed)).isEqualTo(-5);
        assertThat(transformed.metaData().getJsonDeserialized()).isEqualTo(Optional.of(EventMetaData.of("transformed", "true")));
        assertThat((CharSequence) transformed.eventId()).isEqualTo(original.eventId());
        assertThat((CharSequence) transformed.aggregateType()).isEqualTo(original.aggregateType());
        assertThat(transformed.aggregateId()).isEqualTo(original.aggregateId());
        assertThat(transformed.eventOrder()).isEqualTo(original.eventOrder());
        assertThat(transformed.globalEventOrder()).isEqualTo(original.globalEventOrder());
        assertThat(transformed.eventRevision()).isEqualTo(original.eventRevision());
        assertThat(transformed.causedByEventId()).isEqualTo(original.causedByEventId());
        assertThat(transformed.correlationId()).isEqualTo(original.correlationId());
    }

    private static long orderNumberOf(PersistedEvent event) {
        return event.event().<OrderEvent.OrderAdded>getJsonDeserialized().orElseThrow().orderNumber;
    }

    private static List<?> replaceOrderNumbers(List<?> events, LongUnaryOperator change) {
        return events.stream()
                     .map(event -> event instanceof OrderEvent.OrderAdded added
                                   ? new OrderEvent.OrderAdded(added.orderId, added.orderingCustomerId, change.applyAsLong(added.orderNumber))
                                   : event)
                     .toList();
    }

    @InterceptorOrder(1)
    private static class MultiplyOrderNumberOnAppend implements EventStoreInterceptor {
        @Override
        public <ID> AggregateEventStream<ID> intercept(AppendToStream<ID> operation, EventStoreInterceptorChain<AppendToStream<ID>, AggregateEventStream<ID>> chain) {
            operation.setEventsToAppend(replaceOrderNumbers(operation.getEventsToAppend(), orderNumber -> orderNumber * 10));
            return chain.proceed();
        }
    }

    @InterceptorOrder(2)
    private static class AddSevenToOrderNumberOnAppend implements EventStoreInterceptor {
        private final List<Long> orderNumbersSeen = new CopyOnWriteArrayList<>();

        @Override
        public <ID> AggregateEventStream<ID> intercept(AppendToStream<ID> operation, EventStoreInterceptorChain<AppendToStream<ID>, AggregateEventStream<ID>> chain) {
            chain.operation().getEventsToAppend().stream()
                 .filter(OrderEvent.OrderAdded.class::isInstance)
                 .forEach(event -> orderNumbersSeen.add(((OrderEvent.OrderAdded) event).orderNumber));
            operation.setEventsToAppend(replaceOrderNumbers(operation.getEventsToAppend(), orderNumber -> orderNumber + 7));
            return chain.proceed();
        }
    }

    /**
     * Stands in for a decrypting interceptor: transforms what was loaded and returns it, built with the copy helpers
     */
    private static class NegateOrderNumberOnRead implements EventStoreInterceptor {
        private final JSONEventSerializer serializer;

        private NegateOrderNumberOnRead(JSONEventSerializer serializer) {
            this.serializer = serializer;
        }

        private PersistedEvent transform(PersistedEvent event) {
            var payload = event.event().getJsonDeserialized().orElseThrow();
            if (!(payload instanceof OrderEvent.OrderAdded added)) {
                return event;
            }
            return event.withEvent(serializer.serializeEvent(new OrderEvent.OrderAdded(added.orderId, added.orderingCustomerId, -added.orderNumber)))
                        .withMetaData(serializer.serializeMetaData(EventMetaData.of("transformed", "true")));
        }

        @Override
        public Optional<PersistedEvent> intercept(LoadEvent operation, EventStoreInterceptorChain<LoadEvent, Optional<PersistedEvent>> chain) {
            return chain.proceed().map(this::transform);
        }

        @Override
        public Stream<PersistedEvent> intercept(LoadEventsByGlobalOrder operation, EventStoreInterceptorChain<LoadEventsByGlobalOrder, Stream<PersistedEvent>> chain) {
            return chain.proceed().map(this::transform);
        }

        @Override
        public <ID> Optional<AggregateEventStream<ID>> intercept(FetchStream<ID> operation, EventStoreInterceptorChain<FetchStream<ID>, Optional<AggregateEventStream<ID>>> chain) {
            var configuration = ((PostgresqlEventStore<?>) chain.eventStore()).getAggregateEventStreamConfiguration(operation.aggregateType);
            return chain.proceed().map(stream -> AggregateEventStream.of(configuration,
                                                                         stream.aggregateId(),
                                                                         stream.eventOrderRangeIncluded(),
                                                                         stream.events().map(this::transform)));
        }
    }

    private static class TestPersistableEventMapper implements PersistableEventMapper {
        private final CorrelationId correlationId   = CorrelationId.random();
        private final EventId       causedByEventId = EventId.random();

        @Override
        public PersistableEvent map(Object aggregateId, AggregateEventStreamConfiguration aggregateEventStreamConfiguration, Object event, EventOrder eventOrder) {
            return PersistableEvent.from(EventId.random(),
                                         aggregateEventStreamConfiguration.aggregateType,
                                         aggregateId,
                                         EventTypeOrName.with(event.getClass()),
                                         event,
                                         eventOrder,
                                         null,
                                         EventMetaData.of("Key1", "Value1"),
                                         OffsetDateTime.now(),
                                         causedByEventId,
                                         correlationId,
                                         null);
        }
    }
}
