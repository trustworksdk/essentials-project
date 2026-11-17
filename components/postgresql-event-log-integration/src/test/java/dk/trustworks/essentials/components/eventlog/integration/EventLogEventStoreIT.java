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

package dk.trustworks.essentials.components.eventlog.integration;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.interceptor.InterceptorOrder;
import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * The {@link EventLogEventStore} adapter (design phase 8 §9.4): conflict mapping and I10, wire format against
 * {@link PostgresqlEventStore}, unit-of-work stages and the tier-2 wake, interceptors, reads, and the poll methods.
 * Every {@code must_fail_*} expects an {@link AssertionError} from the arm it breaks.
 */
class EventLogEventStoreIT extends AbstractProcessorIT {
    static final AggregateType ORDERS_TYPE = AggregateType.of(ORDERS);
    static final EventMetaData META        = EventMetaData.of("Key1", "Value1", "Key2", "Value2");

    /** A payload with single-value types: a hand-built mapper writes these differently from EssentialsObjectMappers */
    public record OrderPlaced(CorrelationId orderId, TenantId customer, int quantity) {
    }

    public record ProductAdded(CorrelationId orderId, String product) {
    }

    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> uowFactory;
    private Mapper                                             mapper;

    /** Maps with fixed metadata, an optional cause and tenant, like the repositories' mappers */
    static final class Mapper implements PersistableEventMapper {
        volatile EventId causedBy;
        volatile Tenant  tenant;

        @Override
        public PersistableEvent map(Object aggregateId, AggregateEventStreamConfiguration configuration, Object event, EventOrder eventOrder) {
            return PersistableEvent.from(EventId.random(), configuration.aggregateType, aggregateId, EventTypeOrName.with(event.getClass()), event, eventOrder,
                                         EventRevision.of(2), META, OffsetDateTime.now(), causedBy, CorrelationId.random(), tenant);
        }
    }

    @BeforeEach
    void setUpStores() {
        uowFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        mapper = new Mapper();
    }

    private EventLogEventStore adapter(Set<IntegrationMutant> mutants) {
        return adapter(serializer, mutants);
    }

    private EventLogEventStore adapter(JSONEventSerializer jsonSerializer, Set<IntegrationMutant> mutants) {
        var store = new EventLogEventStore(eventLog, uowFactory, jsonSerializer, mapper, new TenantSerializer.TenantIdSerializer(), List.of(), Optional.empty(), mutants);
        store.addAggregateEventStreamConfiguration(ORDERS_TYPE, CorrelationId.class);
        return store;
    }

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> oldStore() {
        var store = new PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration>(
                uowFactory,
                new SeparateTablePerAggregateTypePersistenceStrategy(jdbi, uowFactory, mapper,
                                                                      SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(
                                                                              serializer, IdentifierColumnType.UUID, JSONColumnType.JSON)));
        store.addAggregateEventStreamConfiguration(ORDERS_TYPE, CorrelationId.class);
        return store;
    }

    /** A stable UUID per name: the old engine's aggregate-id column is a uuid in these tests */
    private static CorrelationId id(String name) {
        return CorrelationId.of(UUID.nameUUIDFromBytes(name.getBytes()).toString());
    }

    private static List<Object> orderEvents(CorrelationId orderId) {
        return List.of(new OrderPlaced(orderId, TenantId.of("customer-1"), 3), new ProductAdded(orderId, "p-1"));
    }

    // ---- conflict, I10 ----

    @Test
    void a_stale_append_throws_the_old_engines_exception_and_message_and_burns_no_global_order() {
        conflictArm(Set.of());
    }

    @Test
    void must_fail_CONFLICT_UNMAPPED() {
        assertThatThrownBy(() -> conflictArm(Set.of(IntegrationMutant.CONFLICT_UNMAPPED))).isInstanceOf(AssertionError.class);
    }

    private void conflictArm(Set<IntegrationMutant> mutants) {
        var store   = adapter(mutants);
        var orderId = id("1");
        uowFactory.usingUnitOfWork(uow -> store.appendToStream(ORDERS_TYPE, orderId, orderEvents(orderId)));
        // writer 2 commits after event order 1; writer 1 still expects 0
        uowFactory.usingUnitOfWork(uow -> store.appendToStream(ORDERS_TYPE, orderId, 1L, List.of(new ProductAdded(orderId, "p-2"))));
        var highestBefore = uowFactory.withUnitOfWork(uow -> store.findHighestGlobalEventOrderPersisted(ORDERS_TYPE)).orElseThrow();

        Throwable thrown = catchThrowable(() -> uowFactory.usingUnitOfWork(uow -> store.appendToStream(ORDERS_TYPE, orderId, 0L,
                                                                                                    List.of(new ProductAdded(orderId, "stale-1"), new ProductAdded(orderId, "stale-2")))));
        thrown = unwrapped(thrown);
        assertThat(thrown).isInstanceOf(OptimisticAppendToStreamException.class);

        // the same input on the old engine
        var old       = oldStore();
        Throwable oldThrown = catchThrowable(() -> {
            uowFactory.usingUnitOfWork(uow -> old.appendToStream(ORDERS_TYPE, orderId, orderEvents(orderId)));
            uowFactory.usingUnitOfWork(uow -> old.appendToStream(ORDERS_TYPE, orderId, 1L, List.of(new ProductAdded(orderId, "p-2"))));
            uowFactory.usingUnitOfWork(uow -> old.appendToStream(ORDERS_TYPE, orderId, 0L,
                                                                 List.of(new ProductAdded(orderId, "stale-1"), new ProductAdded(orderId, "stale-2"))));
        });
        oldThrown = unwrapped(oldThrown);
        assertThat(oldThrown).isInstanceOf(OptimisticAppendToStreamException.class);
        assertThat(thrown.getMessage()).isEqualTo(oldThrown.getMessage());

        // I10: the losing append consumed no global order
        var next = uowFactory.withUnitOfWork(uow -> store.appendToStream(ORDERS_TYPE, orderId, 2L, List.of(new ProductAdded(orderId, "p-3"))));
        assertThat(next.eventList().getFirst().globalEventOrder()).isEqualTo(GlobalEventOrder.of(highestBefore.longValue() + 1));
    }

    /** The unit of work wraps what the append throws */
    private static Throwable unwrapped(Throwable thrown) {
        return thrown instanceof dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkException && thrown.getCause() != null ? thrown.getCause() : thrown;
    }

    // ---- wire format ----

    @Test
    void payload_metadata_and_event_type_are_byte_equal_to_the_old_engines() throws SQLException {
        wireFormatArm(serializer);
    }

    @Test
    void must_fail_HAND_BUILT_MAPPER() {
        var handBuilt = new Jackson3JSONEventSerializer(JsonMapper.builder().build());
        assertThatThrownBy(() -> wireFormatArm(handBuilt)).isInstanceOf(AssertionError.class);
    }

    private void wireFormatArm(JSONEventSerializer adapterSerializer) throws SQLException {
        var store   = adapter(adapterSerializer, Set.of());
        var old     = oldStore();
        var orderId = id("wire");
        uowFactory.usingUnitOfWork(uow -> store.appendToStream(ORDERS_TYPE, orderId, orderEvents(orderId)));
        uowFactory.usingUnitOfWork(uow -> old.appendToStream(ORDERS_TYPE, orderId, orderEvents(orderId)));

        var engineRows = eventLog.loadStream(ORDERS, orderId.toString());
        var oldRows = jdbi.withHandle(handle -> handle.createQuery("SELECT event_type, event_payload::text AS payload, event_metadata::text AS metadata FROM orders_events " +
                                                                           "WHERE aggregate_id = :id ORDER BY event_order")
                                                      .bind("id", UUID.fromString(orderId.toString()))
                                                      .map((rs, ctx) -> List.of(rs.getString("event_type"), rs.getString("payload"), rs.getString("metadata")))
                                                      .list());
        assertThat(engineRows).hasSize(2);
        assertThat(engineRows.stream().map(row -> List.of(row.eventType(), row.payload(), row.metadata())).toList()).isEqualTo(oldRows);
        // and it reads back as the same typed event
        var loaded = uowFactory.withUnitOfWork(uow -> store.fetchStream(ORDERS_TYPE, orderId).orElseThrow().eventList());
        assertThat((Object) loaded.getFirst().event().deserialize()).isEqualTo(orderEvents(orderId).getFirst());
        assertThat((Object) loaded.getFirst().metaData().deserialize()).isEqualTo(META);
    }

    // ---- unit-of-work stages and the tier-2 wake ----

    @Test
    void persisted_events_are_published_at_the_commit_stages_and_the_engine_is_woken_after_commit_only() throws Exception {
        stagesArm(Set.of());
    }

    @Test
    void must_fail_PUBLISH_BEFORE_COMMIT() {
        assertThatThrownBy(() -> stagesArm(Set.of(IntegrationMutant.PUBLISH_BEFORE_COMMIT))).isInstanceOf(AssertionError.class);
    }

    private void stagesArm(Set<IntegrationMutant> mutants) throws Exception {
        // a backstop the test never reaches: only the commit callback's wake delivers in time
        eventLog.stop();
        eventLog = EventLog.builder()
                           .setDataSource(engineDataSource)
                           .setSettings(EventLogSettings.defaults().withBackstopInterval(Duration.ofSeconds(60)).withCrossJvmWakeUp(false))
                           .build();
        eventLog.start();
        eventLog.registerAggregateType(ORDERS);
        var store  = adapter(mutants);
        var stages = new CopyOnWriteArrayList<String>();
        store.localEventBus().addSyncSubscriber(event -> {
            if (event instanceof PersistedEvents persisted) {
                stages.add(persisted.commitStage + ":" + persisted.events.size());
            }
        });
        var delivered = new CopyOnWriteArrayList<Long>();
        var reactor = EventReactor.builder("adapter-waker", ORDERS)
                                  .ownership(Ownership.perInstance("node-1"))
                                  .on(OrderPlaced.class, event -> delivered.add(event.globalOrder().longValue()))
                                  .build(eventLog, serializer)
                                  .start();
        try {
            awaitTrue("attached", Duration.ofSeconds(10), () -> reactor.handle().orElseThrow().status().phase().equals("ATTACHED"));
            var rolledBack = id("rolled-back");
            var uow        = uowFactory.getOrCreateNewUnitOfWork();
            store.appendToStream(ORDERS_TYPE, rolledBack, orderEvents(rolledBack));
            uow.rollback();
            assertThat(stages).doesNotContain("BeforeCommit:2", "AfterCommit:2");
            stages.clear();

            var orderId = id("committed");
            uowFactory.usingUnitOfWork(work -> {
                store.appendToStream(ORDERS_TYPE, orderId, orderEvents(orderId));
                Thread.sleep(500); // a wake before commit finds nothing committed
            });
            assertThat(stages).containsExactly("BeforeCommit:2", "AfterCommit:2");
            awaitTrue("the committed append delivered without the backstop", Duration.ofSeconds(10), () -> delivered.size() == 1);
        } finally {
            reactor.stop();
        }
    }

    // ---- interceptors ----

    @Test
    void interceptors_run_in_order_and_can_modify_the_operation_as_with_the_old_engine() {
        var adapterCalls = interceptedFetch(adapter(Set.of()), "order-intercepted-1");
        var oldCalls     = interceptedFetch(oldStore(), "order-intercepted-2");
        assertThat(adapterCalls).isEqualTo(oldCalls).containsExactly("first", "second", "events:1");
    }

    @InterceptorOrder(1)
    record First(List<String> calls) implements EventStoreInterceptor {
        @Override
        public <ID> Optional<AggregateEventStream<ID>> intercept(FetchStream<ID> operation, EventStoreInterceptorChain<FetchStream<ID>, Optional<AggregateEventStream<ID>>> chain) {
            calls.add("first");
            operation.setEventOrderRange(LongRange.from(1));
            return chain.proceed();
        }
    }

    @InterceptorOrder(2)
    record Second(List<String> calls) implements EventStoreInterceptor {
        @Override
        public <ID> Optional<AggregateEventStream<ID>> intercept(FetchStream<ID> operation, EventStoreInterceptorChain<FetchStream<ID>, Optional<AggregateEventStream<ID>>> chain) {
            calls.add("second");
            return chain.proceed();
        }
    }

    private List<String> interceptedFetch(ConfigurableEventStore<?> store, String id) {
        var calls   = new ArrayList<String>();
        var orderId = id(id);
        store.addEventStoreInterceptor(new Second(calls));
        store.addEventStoreInterceptor(new First(calls));
        uowFactory.usingUnitOfWork(uow -> store.appendToStream(ORDERS_TYPE, orderId, orderEvents(orderId)));
        var events = uowFactory.withUnitOfWork(uow -> store.fetchStream(ORDERS_TYPE, orderId).orElseThrow().eventList());
        calls.add("events:" + events.size());
        return calls;
    }

    // ---- reads ----

    @Test
    void reads_by_global_order_ids_causation_and_bounds() {
        var store = adapter(Set.of());
        var a     = id("a");
        var b     = id("b");
        var c     = id("c");
        uowFactory.withUnitOfWork(uow -> {
            assertThat(store.findHighestGlobalEventOrderPersisted(ORDERS_TYPE)).isEmpty();
            assertThat(store.findLowestGlobalEventOrderPersisted(ORDERS_TYPE)).isEmpty();
            return null;
        });
        var first = uowFactory.withUnitOfWork(uow -> store.appendToStream(ORDERS_TYPE, a, orderEvents(a))).eventList(); // 1, 2
        // a hole: a rolled-back append draws 3 and 4
        var uow = uowFactory.getOrCreateNewUnitOfWork();
        store.appendToStream(ORDERS_TYPE, b, orderEvents(b));
        uow.rollback();
        mapper.tenant = TenantId.of("tenant-1");
        mapper.causedBy = first.getFirst().eventId();
        var tenant1 = uowFactory.withUnitOfWork(work -> store.appendToStream(ORDERS_TYPE, c, orderEvents(c))).eventList(); // 5, 6
        mapper.tenant = TenantId.of("tenant-2");
        mapper.causedBy = null;
        var tenant2 = uowFactory.withUnitOfWork(work -> store.appendToStream(ORDERS_TYPE, b, orderEvents(b))).eventList(); // 7, 8

        uowFactory.usingUnitOfWork(work -> {
            assertThat(orders(store.loadEventsByGlobalOrder(ORDERS_TYPE, LongRange.between(1, 6)))).containsExactly(1L, 2L, 5L, 6L);
            assertThat(orders(store.loadEventsByGlobalOrder(ORDERS_TYPE, LongRange.between(2, 2), List.of(GlobalEventOrder.of(7)))))
                    .containsExactly(2L, 7L);
            assertThat(orders(store.loadEventsByGlobalOrder(ORDERS_TYPE, LongRange.from(1), List.of(), TenantId.of("tenant-1"))))
                    .as("the tenant filter keeps untenanted events").containsExactly(1L, 2L, 5L, 6L);
            try (var stream = store.loadEventsByGlobalOrder(new LoadEventsByGlobalOrder(ORDERS_TYPE, LongRange.from(1), List.of(), (Tenant) null)
                                                                     .setOnlyLoadPayloadIfEventBelongsToTenant(TenantId.of("tenant-1")))) {
                var events = stream.toList();
                assertThat(events.stream().map(e -> e.globalEventOrder().longValue()).toList()).containsExactly(1L, 2L, 5L, 6L, 7L, 8L);
                assertThat(events.get(4).event().getJson()).as("another tenant's payload is omitted").isEqualTo("{}");
                assertThat(events.get(2).event().getJson()).isNotEqualTo("{}");
            }
            assertThat(store.findHighestGlobalEventOrderPersisted(ORDERS_TYPE)).contains(GlobalEventOrder.of(8));
            assertThat(store.findLowestGlobalEventOrderPersisted(ORDERS_TYPE)).contains(GlobalEventOrder.of(1));
            assertThat(store.loadEvents(ORDERS_TYPE, List.of(tenant2.get(1).eventId(), first.get(0).eventId())))
                    .extracting(PersistedEvent::eventId).containsExactly(first.get(0).eventId(), tenant2.get(1).eventId());
            assertThat(store.loadEvent(ORDERS_TYPE, tenant1.get(0).eventId()).orElseThrow().tenant()).isEqualTo(Optional.of(TenantId.of("tenant-1")));
            assertThat(store.loadEventsCausedBy(first.getFirst().eventId())).extracting(PersistedEvent::eventId)
                                                                             .containsExactly(tenant1.get(0).eventId(), tenant1.get(1).eventId());
            assertThat(store.loadLastPersistedEventRelatedTo(ORDERS_TYPE, b).orElseThrow().eventOrder()).isEqualTo(EventOrder.of(1));
            assertThat(store.fetchStream(ORDERS_TYPE, c, LongRange.from(1)).orElseThrow().eventList()).extracting(PersistedEvent::globalEventOrder)
                                                                                                       .containsExactly(GlobalEventOrder.of(6));
            assertThat(store.fetchStream(ORDERS_TYPE, c, LongRange.from(0), (Tenant) TenantId.of("tenant-2"))).as("another tenant's stream").isEmpty();
            assertThatThrownBy(() -> store.findEvent(first.getFirst().eventId())).isInstanceOf(UnsupportedOperationException.class);
        });
    }

    private static List<Long> orders(java.util.stream.Stream<PersistedEvent> events) {
        try (events) {
            return events.map(event -> event.globalEventOrder().longValue()).toList();
        }
    }

    // ---- the subscription methods ----

    @Test
    void every_poll_overload_throws() throws Exception {
        var store     = adapter(Set.of());
        var overloads = 0;
        for (Method method : EventStore.class.getMethods()) {
            if (!method.getName().equals("pollEvents") && !method.getName().equals("unboundedPollForEvents")) {
                continue;
            }
            overloads++;
            var arguments = new Object[method.getParameterCount()];
            var types     = method.getParameterTypes();
            for (var i = 0; i < types.length; i++) {
                arguments[i] = types[i] == AggregateType.class ? ORDERS_TYPE
                               : types[i] == long.class ? 1L
                               : types[i] == GlobalEventOrder.class ? GlobalEventOrder.of(1)
                               : types[i] == SubscriberAcknowledgement.class ? SubscriberAcknowledgement.create()
                               : types[i] == Optional.class ? Optional.empty()
                               : types[i] == Duration.class ? Duration.ofMillis(100)
                               : types[i].isInterface() ? Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{types[i]}, (proxy, m, args) -> null)
                               : null;
            }
            var thrown = catchThrowable(() -> {
                try {
                    method.invoke(store, arguments);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
            assertThat(thrown).as(method.toString()).isInstanceOf(UnsupportedOperationException.class)
                              .hasMessageContaining("EventLog subscriptions");
        }
        assertThat(overloads).isGreaterThanOrEqualTo(8);
    }
}
