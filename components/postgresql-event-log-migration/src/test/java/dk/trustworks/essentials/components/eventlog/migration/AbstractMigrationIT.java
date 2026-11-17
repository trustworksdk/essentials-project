package dk.trustworks.essentials.components.eventlog.migration;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.NoEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.components.eventlog.migration.test_data.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One PostgreSQL container per test class, an empty {@code public} schema per test. The old engine's tables are built by
 * {@link PostgresqlEventStore} itself (design phase 9 §9), under its own JDBI unit of work; the test may also write its
 * own old tables directly, to plant rows the old engine would not write.
 */
@Testcontainers
abstract class AbstractMigrationIT {
    @Container
    static final PostgreSQLContainer postgres = EssentialsTestContainers.postgres();

    protected PGSimpleDataSource dataSource;
    protected EventLog           eventLog;

    @BeforeEach
    void resetDatabase() throws SQLException {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        execute("DROP SCHEMA public CASCADE");
        execute("CREATE SCHEMA public");
        eventLog = EventLog.builder().setDataSource(dataSource).build();
        eventLog.start();
    }

    @AfterEach
    void stopEventLog() {
        if (eventLog != null) {
            eventLog.stop();
        }
    }

    // ------------------------------------------------------------------ the migration

    protected Migration migration(CopySettings settings, OldEngine... engines) {
        var tables = new ArrayList<SourceTable>();
        for (var engine : engines) {
            for (var type : engine.types) {
                tables.add(SourceTable.of(type, engine.table(type)));
            }
        }
        return new Migration(new MigrationPlan(dataSource, eventLog, tables, settings));
    }

    protected Migration migration(OldEngine... engines) {
        return migration(CopySettings.defaults().withBatchSize(50), engines);
    }

    protected static MigrationSeams mutants(MigrationMutant... mutants) {
        return new MigrationSeams(Set.of(mutants), Map.of(), name -> true);
    }

    protected static MigrationSeams seams(Set<MigrationMutant> mutants, Map<MigrationSeams.Hook, MigrationSeams.ConnectionHook> hooks, Predicate<String> intern) {
        return new MigrationSeams(mutants, hooks, intern);
    }

    // ------------------------------------------------------------------ the old engine

    /** The old engine with one id and JSON column type; every event gets its own correlation id and a cause */
    protected final class OldEngine {
        final List<String>                                                         types;
        final EventStoreManagedUnitOfWorkFactory                                   factory;
        final PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
        volatile TenantId                                                          tenant;

        @SuppressWarnings("unchecked")
        OldEngine(IdentifierColumnType idType, JSONColumnType jsonType, String... types) {
            this.types = List.of(types);
            var jdbi = Jdbi.create(dataSource);
            jdbi.installPlugin(new PostgresPlugin());
            factory = new EventStoreManagedUnitOfWorkFactory(jdbi);
            PersistableEventMapper mapper = (aggregateId, configuration, event, eventOrder) ->
                    PersistableEvent.from(EventId.random(), configuration.aggregateType, aggregateId, EventTypeOrName.with(event.getClass()), event, eventOrder,
                                          EventRevision.of(1), EventMetaData.of("Key1", "Value1"), OffsetDateTime.now(), EventId.random(), CorrelationId.random(), tenant);
            var strategy = SeparateTablePerAggregateTypePersistenceStrategy.builder()
                                                                           .setJdbi(jdbi)
                                                                           .setUnitOfWorkFactory(factory)
                                                                           .setEventMapper(mapper)
                                                                           .setAggregateEventStreamConfigurationFactory(
                                                                                   SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardConfiguration(
                                                                                           EssentialsJSONEventSerializers.create(), idType, jsonType,
                                                                                           new TenantSerializer.TenantIdSerializer()))
                                                                           .build();
            eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                             .setUnitOfWorkFactory(factory)
                                             .setPersistenceStrategy(strategy)
                                             .setEventStreamGapHandlerFactory(es -> new NoEventStreamGapHandler<>())
                                             .setEventStoreSubscriptionObserver(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver())
                                             .build();
            for (var type : types) {
                eventStore.addAggregateEventStreamConfiguration(AggregateType.of(type), TestId.class);
            }
        }

        String table(String type) {
            return eventStore.getAggregateEventStreamConfiguration(AggregateType.of(type)).eventStreamTableName;
        }

        /** Appends {@code events} events to a new stream, committed */
        TestId stream(String type, int events) {
            var id = TestId.random();
            factory.usingUnitOfWork(uow -> eventStore.appendToStream(AggregateType.of(type), id, Optional.empty(), events(events)));
            return id;
        }

        void streams(String type, int streams, int events) {
            for (var i = 0; i < streams; i++) {
                stream(type, events);
            }
        }

        /** Appends to a new stream and rolls back: burns global_order values */
        void burn(String type, int events) {
            try {
                factory.usingUnitOfWork(uow -> {
                    eventStore.appendToStream(AggregateType.of(type), TestId.random(), Optional.empty(), events(events));
                    throw new IllegalStateException("rolled back on purpose");
                });
            } catch (RuntimeException expected) {
                // the values are burned
            }
        }

        /**
         * An append that holds its transaction open until {@link HeldWriter#release()}; returns once the row is inserted
         * (its value drawn) or, if the insert blocks on a lock, once it is blocked
         */
        HeldWriter held(String type) throws Exception {
            var inserted = new CountDownLatch(1);
            var release  = new CountDownLatch(1);
            var id       = TestId.random();
            var future = CompletableFuture.runAsync(() -> factory.usingUnitOfWork(uow -> {
                eventStore.appendToStream(AggregateType.of(type), id, Optional.empty(), events(1));
                inserted.countDown();
                release.await();
            }));
            return new HeldWriter(id, inserted, release, future);
        }
    }

    record HeldWriter(TestId id, CountDownLatch inserted, CountDownLatch release, CompletableFuture<Void> done) {
        void awaitInserted() throws InterruptedException {
            assertThat(inserted.await(10, TimeUnit.SECONDS)).as("held writer inserted").isTrue();
        }

        void releaseAndJoin() throws Exception {
            release.countDown();
            done.get(30, TimeUnit.SECONDS);
        }
    }

    static List<Object> events(int count) {
        var list = new ArrayList<Object>();
        for (var i = 0; i < count; i++) {
            list.add(i % 2 == 0 ? new Opened("n" + i, i) : new Amended("é \"quoted\" " + i, i, List.of("a", "b" + i)));
        }
        return list;
    }

    // ------------------------------------------------------------------ comparison (the test's own; 9-D builds the real verification)

    private static final String RE = MigrationStorage.UUID_RE;

    /**
     * @return the rows that differ between the old table (up to {@code throughGlobalOrder}) and the event log's table,
     * compared over every column, payload and metadata as text (§6.1's columns)
     */
    protected long differingRows(OldEngine engine, String type, long throughGlobalOrder) throws SQLException {
        return differingRows(engine.table(type), type, throughGlobalOrder);
    }

    protected long differingRows(String oldTable, String type, long throughGlobalOrder) throws SQLException {
        var tables = AggregateTypeTables.of(type);
        var oldRow = "concat_ws('|', o.global_order, o.aggregate_id::text, o.event_order, o.event_id::text, " +
                "CASE WHEN o.caused_by_event_id::text ~ " + RE + " THEN o.caused_by_event_id::text END, o.correlation_id::text, o.event_type, o.event_revision::text, " +
                "(extract(epoch FROM o.timestamp) * 1e6)::bigint, o.event_payload::text, o.event_metadata::text, coalesce(o.tenant, '-'))";
        var newRow = "concat_ws('|', e.global_order, e.aggregate_id, e.event_order, e.event_id::text, e.caused_by_event_id::text, e.correlation_id::text, y.name, " +
                "e.event_revision::text, (extract(epoch FROM e.timestamp) * 1e6)::bigint, e.event_payload::text, e.event_metadata::text, coalesce(e.tenant, '-'))";
        return queryLong("SELECT count(*) FROM (SELECT " + oldRow + " AS r FROM " + oldTable + " o WHERE o.global_order <= " + throughGlobalOrder + ") a " +
                                 "FULL JOIN (SELECT " + newRow + " AS r FROM " + tables.eventTable() + " e JOIN el_event_types y ON y.id = e.event_type) b " +
                                 "USING (r) WHERE a.r IS NULL OR b.r IS NULL");
    }

    protected long oldMax(OldEngine engine, String type) throws SQLException {
        return queryLong("SELECT coalesce(max(global_order), 0) FROM " + engine.table(type));
    }

    protected long newCount(String type) throws SQLException {
        return queryLong("SELECT count(*) FROM " + AggregateTypeTables.of(type).eventTable());
    }

    protected void assertHeadsConsistent(String type) throws SQLException {
        var tables = AggregateTypeTables.of(type);
        assertThat(queryLong("SELECT count(*) FROM (SELECT aggregate_id, max(event_order) AS m FROM " + tables.eventTable() + " GROUP BY aggregate_id) e " +
                                     "FULL JOIN " + tables.headTable() + " h USING (aggregate_id) WHERE h.version IS DISTINCT FROM e.m"))
                .as("streams of %s whose head differs from max(event_order)", type).isZero();
    }

    // ------------------------------------------------------------------ SQL helpers

    protected long queryLong(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    protected void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** @return whether some backend waits for a lock on {@code table} */
    protected boolean waitingForLockOn(String table) throws SQLException {
        return queryLong("SELECT count(*) FROM pg_locks WHERE NOT granted AND relation = '" + table + "'::regclass") > 0;
    }

    protected static void await(Callable<Boolean> condition, Duration timeout) throws Exception {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(20);
        }
    }
}
