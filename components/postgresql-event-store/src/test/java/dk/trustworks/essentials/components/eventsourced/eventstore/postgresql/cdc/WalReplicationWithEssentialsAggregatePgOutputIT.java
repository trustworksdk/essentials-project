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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDeliveryMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDispatcherProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.PgOutputProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalReplicationTailerProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalParserMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.EventStreamTableColumnNames;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.IdentifierColumnType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.JSONColumnType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.EventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypePersistenceStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.CustomerId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.ProductEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.ProductId;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.createObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class WalReplicationWithEssentialsAggregatePgOutputIT extends AbstractLogicalReplicationPostgresIT {

    /**
     * A second aggregate type — and therefore a second event-stream table — so the pgoutput
     * RELATION-message handling is exercised with more than one relation in play. Single-table
     * coverage cannot see a collision between two relations' schema messages.
     */
    private static final AggregateType PRODUCTS = AggregateType.of("Products");

    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private JSONEventSerializer                                                    jacksonJSONSerializer;
    private CdcInboxRepository                                                      inboxRepository;
    private EventStreamGapHandler<?>                                                gapHandler;

    @BeforeEach
    void setup() {
        jacksonJSONSerializer = EssentialsJSONEventSerializers.createForActiveJacksonFlavor();
        var eventMapper = new EventProcessorIT.TestPersistableEventMapper();

        var persistenceStrategy =
                new SeparateTablePerAggregateTypePersistenceStrategy(
                        jdbi,
                        unitOfWorkFactory,
                        eventMapper,
                        SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardConfiguration(
                                aggregateType -> aggregateType + "_events",
                                EventStreamTableColumnNames.defaultColumnNames(),
                                jacksonJSONSerializer,
                                IdentifierColumnType.TEXT,
                                JSONColumnType.JSONB,
                                new TenantSerializer.TenantIdSerializer()
                        )
                );

        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
        persistenceStrategy.addAggregateEventStreamConfiguration(PRODUCTS, ProductId.class);

        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        inboxRepository = new CdcInboxRepository(unitOfWorkFactory);
        gapHandler = new PostgresqlEventStreamGapHandler<>(eventStore, unitOfWorkFactory);
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void pgoutput_direct_delivery_mode_publishes_without_inbox_writes() {
        String slotName = slotName();
        String publicationName = publicationName();
        createPublication(publicationName);

        AggregateTypeResolver resolver = table -> "orders_events".equalsIgnoreCase(table) ? ORDERS : null;
        var pgOutputConverter = new PgOutputToPersistedEventConverter(jacksonJSONSerializer, resolver, AggregateIdSerializerResolver.forEventStore(eventStore));
        List<PersistedEvent> cdcPersistedEvents = new CopyOnWriteArrayList<>();

        var availability = new CdcAvailability();
        var tailer = new WalReplicationTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                inboxRepository,
                tailerProperties(),
                PgSlotMode.CREATE_IF_MISSING,
                CdcMode.AUTO,
                CdcDeliveryMode.DIRECT,
                pgOutputPlugin(publicationName, pgOutputConverter),
                Optional.of(cdcPersistedEvents::addAll),
                Optional.empty(),
                availability,
                Optional.empty(),
                Optional.empty(),
                Optional.of(eventStreamTablesSupplier()),
                false
        );

        tailer.startAndAwaitReady(Duration.ofSeconds(10));
        appendOrderEvents();

        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(cdcPersistedEvents).hasSizeGreaterThanOrEqualTo(3);
                    assertThat(inboxRepository.countByStatus(slotName, "RECEIVED")).isZero();
                    assertThat(inboxRepository.countByStatus(slotName, "POISON")).isZero();
                    assertThat(inboxRepository.countByStatus(slotName, "DISPATCHED")).isZero();
                });

        tailer.stop();
    }

    @Test
    void pgoutput_inbox_delivery_persists_and_dispatches_events() {
        String slotName = slotName();
        String publicationName = publicationName();
        createPublication(publicationName);

        AggregateTypeResolver resolver = table -> "orders_events".equalsIgnoreCase(table) ? ORDERS : null;
        var pgOutputConverter = new PgOutputToPersistedEventConverter(jacksonJSONSerializer, resolver, AggregateIdSerializerResolver.forEventStore(eventStore));
        List<PersistedEvent> cdcPersistedEvents = new CopyOnWriteArrayList<>();

        var availability = new CdcAvailability();
        var logicalDecodingPlugin = pgOutputPlugin(publicationName, pgOutputConverter);
        var tailer = new WalReplicationTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                inboxRepository,
                tailerProperties(),
                PgSlotMode.CREATE_IF_MISSING,
                CdcMode.AUTO,
                CdcDeliveryMode.INBOX,
                logicalDecodingPlugin,
                Optional.of(cdcPersistedEvents::addAll),
                Optional.empty(),
                availability,
                Optional.empty(),
                Optional.empty(),
                Optional.of(eventStreamTablesSupplier()),
                false
        );

        var dispatcher = new CdcDispatcher(
                inboxRepository,
                unitOfWorkFactory,
                gapHandler,
                logicalDecodingPlugin,
                Optional.empty(),
                cdcPersistedEvents::addAll,
                slotName,
                CdcDispatcherProperties.defaults(),
                CdcDeliveryMode.INBOX,
                availability,
                Optional.empty()
        );

        tailer.startAndAwaitReady(Duration.ofSeconds(10));
        dispatcher.start();
        appendOrderEvents();

        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(cdcPersistedEvents).hasSizeGreaterThanOrEqualTo(3);
                    assertThat(inboxRepository.countByStatus(slotName, "RECEIVED")).isZero();
                    assertThat(inboxRepository.countByStatus(slotName, "POISON")).isZero();
                    assertThat(inboxRepository.countByStatus(slotName, "DISPATCHED")).isGreaterThan(0);
                });

        dispatcher.stop();
        tailer.stop();
    }

    @Test
    void pgoutput_two_tailers_only_one_holds_slot_lock_and_second_takes_over() {
        String slotName = slotName();
        String publicationName = publicationName();
        createPublication(publicationName);

        AggregateTypeResolver resolver = table -> "orders_events".equalsIgnoreCase(table) ? ORDERS : null;
        var pgOutputConverter = new PgOutputToPersistedEventConverter(jacksonJSONSerializer, resolver, AggregateIdSerializerResolver.forEventStore(eventStore));
        List<PersistedEvent> node1Events = new CopyOnWriteArrayList<>();
        List<PersistedEvent> node2Events = new CopyOnWriteArrayList<>();

        var tailer1 = directPgOutputTailer(slotName, publicationName, pgOutputConverter, node1Events);
        var tailer2 = directPgOutputTailer(slotName, publicationName, pgOutputConverter, node2Events);

        tailer1.startAndAwaitReady(Duration.ofSeconds(10));
        tailer2.start();

        await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(tailer1.getStatus().slotLockAcquired()).isTrue();
                    assertThat(tailer2.getStatus().slotLockAcquired()).isFalse();
                });

        appendOrderEvents();

        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(node1Events).hasSizeGreaterThanOrEqualTo(3);
                    assertThat(node2Events).isEmpty();
                });

        tailer1.stop();

        await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(tailer2.getStatus().slotLockAcquired()).isTrue());

        appendOneMoreOrderEvent();

        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(node2Events).hasSizeGreaterThanOrEqualTo(1));

        tailer2.stop();
    }

    /**
     * Verifies the inbox dedup invariant documented on {@link CdcInboxRepository#insertIfAbsent}: under
     * pgoutput, every persisted WAL message carries a distinct LSN, so the {@code unique(slot_name, lsn)}
     * key never falsely dedups two distinct messages. Exercised across a multi-statement transaction
     * (4 INSERTs in one txn — the first append, so the RELATION-message boundary is crossed) plus a
     * second transaction (another BEGIN/COMMIT boundary). Runs the tailer in INBOX mode WITHOUT a
     * dispatcher so the rows stay {@code RECEIVED} and their LSNs can be inspected directly.
     */
    @Test
    void pgoutput_inbox_persists_a_distinct_lsn_per_message_across_a_multi_statement_transaction() {
        String slotName = slotName();
        String publicationName = publicationName();
        createPublication(publicationName);

        AggregateTypeResolver resolver = table -> "orders_events".equalsIgnoreCase(table) ? ORDERS : null;
        var pgOutputConverter = new PgOutputToPersistedEventConverter(jacksonJSONSerializer, resolver, AggregateIdSerializerResolver.forEventStore(eventStore));

        var availability = new CdcAvailability();
        var tailer = new WalReplicationTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                inboxRepository,
                tailerProperties(),
                PgSlotMode.CREATE_IF_MISSING,
                CdcMode.AUTO,
                CdcDeliveryMode.INBOX,
                pgOutputPlugin(publicationName, pgOutputConverter),
                Optional.empty(), // INBOX mode → no direct consumer
                Optional.empty(),
                availability,
                Optional.empty(),
                Optional.empty(),
                Optional.of(eventStreamTablesSupplier()),
                false
        );

        tailer.startAndAwaitReady(Duration.ofSeconds(10));
        appendOrderEvents();       // 4 INSERTs in ONE transaction (first append → RELATION boundary)
        appendOneMoreOrderEvent(); // a 2nd transaction → another BEGIN/COMMIT boundary

        // The pgoutput pre-filter persists 'R' (RELATION) and 'I' (INSERT) messages, dropping
        // BEGIN/COMMIT. So the inbox receives 1 RELATION (emitted once, before the first INSERT for
        // orders_events) + 5 INSERTs = 6 rows. No dispatcher runs, so they remain RECEIVED. Crucially,
        // a RELATION→INSERT LSN collision (the documented risk) would dedup the first INSERT away and
        // we'd see fewer than 6 distinct LSNs.
        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(inboxRepository.countByStatus(slotName, "RECEIVED")).isGreaterThanOrEqualTo(6L));

        var lsns = jdbi.withHandle(handle -> handle.createQuery(
                                                   "select lsn from " + CdcSql.DEFAULT_CDC_TABLE_NAME + " where slot_name = :slot")
                                                   .bind("slot", slotName)
                                                   .mapTo(String.class)
                                                   .list());

        assertThat(lsns).as("1 RELATION + 5 INSERT messages persisted").hasSizeGreaterThanOrEqualTo(6);
        assertThat(Set.copyOf(lsns))
                .as("every persisted WAL message (RELATION and INSERTs alike) must carry a distinct LSN — the inbox unique(slot_name, lsn) dedup key depends on it")
                .hasSize(lsns.size());

        tailer.stop();
    }

    /**
     * Regression test for the RELATION-message dedup collision.
     * <p>
     * pgoutput reports <b>every</b> RELATION message at LSN {@code 0/0}, because the walsender
     * synthesizes them rather than reading them from a WAL record. When the inbox keyed its
     * {@code unique(slot_name, lsn)} dedup constraint on the raw LSN, the first RELATION to arrive
     * claimed {@code 0/0} and the schema for every other event-stream table was silently discarded by
     * {@code on conflict do nothing}. The dispatcher's decoder then knew exactly one relation and
     * quarantined every INSERT on the rest — one working table and the others permanently dead.
     * <p>
     * Single-table coverage is blind to this: with one relation there is nothing to collide with.
     * This test therefore drives two tables and asserts both keep their schema and deliver events.
     */
    @Test
    void pgoutput_retains_the_relation_schema_of_every_event_stream_table() {
        String slotName = slotName();
        String publicationName = publicationName();
        createPublicationForOrdersAndProducts(publicationName);

        var pgOutputConverter = new PgOutputToPersistedEventConverter(jacksonJSONSerializer, ordersAndProductsResolver(), AggregateIdSerializerResolver.forEventStore(eventStore));
        List<PersistedEvent> cdcPersistedEvents = new CopyOnWriteArrayList<>();

        var availability          = new CdcAvailability();
        var logicalDecodingPlugin = pgOutputPlugin(publicationName, pgOutputConverter);
        var tailer                = inboxPgOutputTailer(slotName, logicalDecodingPlugin, ordersAndProductsTablesSupplier(), availability);
        var dispatcher            = inboxDispatcher(slotName, logicalDecodingPlugin, availability, cdcPersistedEvents);

        tailer.startAndAwaitReady(Duration.ofSeconds(10));
        dispatcher.start();

        appendOrderEvents();   // 4 events on orders_events   → 1 RELATION + 4 INSERTs
        appendProductEvents(); // 2 events on products_events → 1 RELATION + 2 INSERTs

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(cdcPersistedEvents).hasSizeGreaterThanOrEqualTo(6);
                    assertThat(inboxRepository.countByStatus(slotName, "RECEIVED")).isZero();
                });

        assertThat(inboxRepository.countByStatus(slotName, "POISON"))
                .as("no row may be quarantined — a missing RELATION schema is what poisons them")
                .isZero();

        assertThat(persistedRelationIds(slotName))
                .as("each event-stream table must keep its own RELATION row; a raw-LSN dedup key collapses them all onto one")
                .hasSize(2);

        assertThat(aggregateTypesOf(cdcPersistedEvents))
                .as("both aggregate types must be delivered, not just whichever table's RELATION won the dedup race")
                .containsExactlyInAnyOrder(ORDERS, PRODUCTS);

        dispatcher.stop();
        tailer.stop();
    }

    /**
     * A dispatcher's relation cache is in-memory, while the RELATION messages that fill it are
     * streamed once per replication session — so a restarted dispatcher starts blind, and the
     * re-sent RELATION dedups against the row it already wrote rather than repopulating anything.
     * Without priming from the retained inbox rows, every subsequent INSERT is quarantined.
     * <p>
     * Simulated here by handing a second dispatcher a <b>fresh plugin instance</b> (empty cache)
     * while the original tailer keeps streaming.
     */
    @Test
    void a_restarted_dispatcher_rebuilds_its_relation_schema_cache_from_the_inbox() {
        String slotName = slotName();
        String publicationName = publicationName();
        createPublicationForOrdersAndProducts(publicationName);

        var availability = new CdcAvailability();
        List<PersistedEvent> firstRunEvents = new CopyOnWriteArrayList<>();

        var tailerPlugin = pgOutputPlugin(publicationName,
                                          new PgOutputToPersistedEventConverter(jacksonJSONSerializer, ordersAndProductsResolver(), AggregateIdSerializerResolver.forEventStore(eventStore)));
        var tailer       = inboxPgOutputTailer(slotName, tailerPlugin, ordersAndProductsTablesSupplier(), availability);

        var firstDispatcher = inboxDispatcher(slotName, tailerPlugin, availability, firstRunEvents);

        tailer.startAndAwaitReady(Duration.ofSeconds(10));
        firstDispatcher.start();
        appendOrderEvents();
        appendProductEvents();

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(firstRunEvents).hasSizeGreaterThanOrEqualTo(6));
        firstDispatcher.stop();

        // Restart: a brand-new plugin means a brand-new (empty) relation cache, exactly as after a
        // JVM restart. The RELATION rows are still in the inbox, already DISPATCHED.
        List<PersistedEvent> afterRestartEvents = new CopyOnWriteArrayList<>();
        var restartedPlugin     = pgOutputPlugin(publicationName,
                                                 new PgOutputToPersistedEventConverter(jacksonJSONSerializer, ordersAndProductsResolver(), AggregateIdSerializerResolver.forEventStore(eventStore)));
        var restartedDispatcher = inboxDispatcher(slotName, restartedPlugin, availability, afterRestartEvents);
        restartedDispatcher.start();

        appendOneMoreOrderEvent();
        appendProductEvents();

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(afterRestartEvents).hasSizeGreaterThanOrEqualTo(3);
                    assertThat(inboxRepository.countByStatus(slotName, "RECEIVED")).isZero();
                });

        assertThat(inboxRepository.countByStatus(slotName, "POISON"))
                .as("a restarted dispatcher must recover the schema from the inbox rather than quarantine the rows")
                .isZero();
        assertThat(aggregateTypesOf(afterRestartEvents))
                .containsExactlyInAnyOrder(ORDERS, PRODUCTS);

        restartedDispatcher.stop();
        tailer.stop();
    }

    /**
     * Payload parity: an event delivered by CDC must be indistinguishable from the same event loaded
     * through the polling path. Not just "the same events in the same order" — the same <em>values</em>,
     * field for field, at the same types.
     * <p>
     * This is the gate that was missing. {@code CdcEventStoreSubscriptionParity_IT} compares
     * global-event-orders and counts, and it runs against an INACTIVE {@link CdcAvailability}, so it
     * exercises the fallback-to-polling path rather than the CDC decode path — it cannot see a converter
     * defect at all. Nothing compared what CDC actually produced against what polling produces, which is
     * how CDC came to deliver {@code aggregateId} as a raw {@code String} where polling delivers the typed
     * id. Everything downstream that trusted the declared type then broke under CDC and only under CDC.
     */
    @Test
    void cdc_delivered_events_are_field_for_field_identical_to_polled_events() {
        String slotName = slotName();
        String publicationName = publicationName();
        createPublicationForOrdersAndProducts(publicationName);

        var pgOutputConverter = new PgOutputToPersistedEventConverter(jacksonJSONSerializer,
                                                                      ordersAndProductsResolver(),
                                                                      AggregateIdSerializerResolver.forEventStore(eventStore));
        List<PersistedEvent> cdcDelivered = new CopyOnWriteArrayList<>();

        var availability          = new CdcAvailability();
        var logicalDecodingPlugin = pgOutputPlugin(publicationName, pgOutputConverter);
        var tailer                = inboxPgOutputTailer(slotName, logicalDecodingPlugin, ordersAndProductsTablesSupplier(), availability);
        var dispatcher            = inboxDispatcher(slotName, logicalDecodingPlugin, availability, cdcDelivered);

        tailer.startAndAwaitReady(Duration.ofSeconds(10));
        dispatcher.start();

        appendOrderEvents();
        appendProductEvents();

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(cdcDelivered).hasSizeGreaterThanOrEqualTo(6));

        for (var aggregateType : List.of(ORDERS, PRODUCTS)) {
            var polled = unitOfWorkFactory.withUnitOfWork(uow ->
                    eventStore.loadEventsByGlobalOrder(aggregateType, LongRange.from(1)).toList());
            var streamed = cdcDelivered.stream()
                                       .filter(event -> event.aggregateType().equals(aggregateType))
                                       .sorted(Comparator.comparingLong(event -> event.globalEventOrder().longValue()))
                                       .toList();

            assertThat(comparableFormOf(streamed))
                    .as("CDC-delivered events for '%s' must match the polled events field for field", aggregateType)
                    .isEqualTo(comparableFormOf(polled));
        }

        dispatcher.stop();
        tailer.stop();
    }

    /**
     * Every field a subscriber can observe, with the aggregate-id's runtime class included explicitly —
     * comparing only its {@code toString()} would let a raw {@code String} masquerade as the typed id,
     * which is precisely the defect this guards.
     */
    private List<String> comparableFormOf(List<PersistedEvent> events) {
        return events.stream()
                     .map(event -> String.join("|",
                                               event.eventId().toString(),
                                               event.aggregateType().toString(),
                                               String.valueOf(event.aggregateId()),
                                               event.aggregateId().getClass().getName(),
                                               String.valueOf(event.eventOrder().longValue()),
                                               String.valueOf(event.eventRevision().intValue()),
                                               String.valueOf(event.globalEventOrder().longValue()),
                                               event.event().getEventTypeOrNamePersistenceValue(),
                                               parsedJson(event.event().getJson()),
                                               parsedJson(event.metaData().getJson()),
                                               String.valueOf(event.tenant())))
                     .toList();
    }

    /**
     * Compare JSON payloads by structure, not by their exact text.
     * <p>
     * The two paths legitimately differ in formatting: the polling path returns the {@code jsonb} column as
     * Postgres renders it ({@code {"orderId": "…"}}), while the CDC converter re-serializes what it decodes
     * and so emits the canonical compact form ({@code {"orderId":"…"}}). That re-serialization is deliberate
     * — it is what keeps the persisted payload byte-identical across Jackson majors. Values, ordering and
     * types must still agree exactly, which is what parsing and comparing the structures checks.
     */
    private String parsedJson(String json) {
        return String.valueOf(jacksonJSONSerializer.deserialize(json, Object.class));
    }

    private Set<AggregateType> aggregateTypesOf(List<PersistedEvent> events) {
        return events.stream().map(PersistedEvent::aggregateType).collect(java.util.stream.Collectors.toSet());
    }

    /**
     * The distinct relation OIDs for which a {@code 'R'} (RELATION) row survived in the inbox. The
     * relation OID is the 4-byte big-endian int directly after the {@code 'R'} type marker.
     */
    private List<Integer> persistedRelationIds(String slotName) {
        return jdbi.withHandle(handle -> handle.createQuery(
                                               "select distinct (get_byte(payload_bytes,1)::bigint<<24)"
                                                       + " | (get_byte(payload_bytes,2)<<16)"
                                                       + " | (get_byte(payload_bytes,3)<<8)"
                                                       + " | get_byte(payload_bytes,4) as relation_id"
                                                       + " from " + CdcSql.DEFAULT_CDC_TABLE_NAME
                                                       + " where slot_name = :slot and get_byte(payload_bytes,0) = 82")
                                               .bind("slot", slotName)
                                               .mapTo(Integer.class)
                                               .list());
    }

    private AggregateTypeResolver ordersAndProductsResolver() {
        return table -> {
            if ("orders_events".equalsIgnoreCase(table)) return ORDERS;
            if ("products_events".equalsIgnoreCase(table)) return PRODUCTS;
            return null;
        };
    }

    private static Supplier<Set<String>> ordersAndProductsTablesSupplier() {
        return () -> Set.of("orders_events", "products_events");
    }

    private void createPublicationForOrdersAndProducts(String publicationName) {
        jdbi.useHandle(handle -> {
            handle.execute("drop publication if exists " + publicationName);
            handle.execute("create publication " + publicationName + " for table orders_events, products_events");
        });
    }

    private void appendProductEvents() {
        var productId = ProductId.of("ProductId-" + UUID.randomUUID());
        var uow       = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        eventStore.appendToStream(PRODUCTS, productId, List.of(
                new ProductEvent.ProductAdded(productId),
                new ProductEvent.ProductDiscontinued(productId)
        ));
        uow.commit();
    }

    private WalReplicationTailer inboxPgOutputTailer(String slotName,
                                                     PgOutputLogicalDecodingPlugin plugin,
                                                     Supplier<Set<String>> eventStreamTables,
                                                     CdcAvailability availability) {
        return new WalReplicationTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                inboxRepository,
                tailerProperties(),
                PgSlotMode.CREATE_IF_MISSING,
                CdcMode.AUTO,
                CdcDeliveryMode.INBOX,
                plugin,
                Optional.empty(),
                Optional.empty(),
                availability,
                Optional.empty(),
                Optional.empty(),
                Optional.of(eventStreamTables),
                false
        );
    }

    private CdcDispatcher inboxDispatcher(String slotName,
                                          LogicalDecodingPlugin plugin,
                                          CdcAvailability availability,
                                          List<PersistedEvent> sink) {
        return new CdcDispatcher(
                inboxRepository,
                unitOfWorkFactory,
                gapHandler,
                plugin,
                Optional.empty(),
                sink::addAll,
                slotName,
                CdcDispatcherProperties.defaults(),
                CdcDeliveryMode.INBOX,
                availability,
                Optional.empty()
        );
    }

    private WalReplicationTailer directPgOutputTailer(String slotName,
                                                       String publicationName,
                                                       PgOutputToPersistedEventConverter pgOutputConverter,
                                                       List<PersistedEvent> persistedEvents) {
        var availability = new CdcAvailability();
        return new WalReplicationTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                slotName,
                inboxRepository,
                tailerProperties(),
                PgSlotMode.CREATE_IF_MISSING,
                CdcMode.AUTO,
                CdcDeliveryMode.DIRECT,
                pgOutputPlugin(publicationName, pgOutputConverter),
                Optional.of(persistedEvents::addAll),
                Optional.empty(),
                availability,
                Optional.empty(),
                Optional.empty(),
                Optional.of(eventStreamTablesSupplier()),
                false
        );
    }

    /**
     * Live supplier of registered event-stream table names for this test class. Mirrors the
     * production {@code WalMessageFilter} wiring (Spring autoconfig pulls
     * {@code persistenceStrategy.getSeparateTablePerEventStreamTableNameAggregates().keySet()})
     * so the pgoutput pre-filter can correctly decide which {@code I}/{@code R} messages to
     * keep.
     */
    private static Supplier<Set<String>> eventStreamTablesSupplier() {
        return () -> Set.of("orders_events");
    }

    private void appendOneMoreOrderEvent() {
        var orderId = OrderId.of("beed77fb-1115-1115-9c48-03ed5bfe8f89");
        var uow = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        eventStore.appendToStream(ORDERS, orderId, List.of(new OrderEvent.OrderAccepted(orderId)));
        uow.commit();
    }

    private void appendOrderEvents() {
        var orderId = OrderId.of("beed77fb-1115-1115-9c48-03ed5bfe8f89");
        var persistableEvents = List.of(
                new OrderEvent.OrderAdded(orderId, CustomerId.of("Test-Customer-Id-15"), 1234),
                new OrderEvent.ProductAddedToOrder(orderId, ProductId.of("ProductId-1"), 2),
                new OrderEvent.ProductAddedToOrder(orderId, ProductId.of("ProductId-2"), 1),
                new OrderEvent.ProductRemovedFromOrder(orderId, ProductId.of("ProductId-1"))
        );

        var uow = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        eventStore.appendToStream(ORDERS, orderId, persistableEvents);
        uow.commit();
    }

    private void createPublication(String publicationName) {
        jdbi.useHandle(handle -> {
            handle.execute("drop publication if exists " + publicationName);
            handle.execute("create publication " + publicationName + " for table orders_events");
        });
    }

    private static String slotName() {
        return "slot_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String publicationName() {
        return "pub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static WalReplicationTailerProperties tailerProperties() {
        return WalReplicationTailerProperties.defaults(
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                Duration.ofSeconds(2),
                Duration.ofMillis(250)
        );
    }

    private static PgOutputLogicalDecodingPlugin pgOutputPlugin(String publicationName, PgOutputToPersistedEventConverter converter) {
        var properties = new PgOutputProperties();
        properties.setPublicationName(publicationName);
        properties.setProtoVersion(1);
        properties.setBinary(false);
        properties.setMessages(false);
        return new PgOutputLogicalDecodingPlugin(properties, converter);
    }
}
