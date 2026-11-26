package dk.trustworks.essentials.components.eventlog.migration;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.PostgresqlDurableSubscriptionRepository;
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
 * {@link PostgresqlEventStore} itself, under its own JDBI unit of work; the test may also write its
 * own old tables directly, to plant rows the old engine would not write.
 */
@Testcontainers
abstract class AbstractMigrationIT {
    /**
     * Overrides the PostgreSQL image of every IT of this module, e.g. {@code -Dmigration.it.image=postgres:16.15}, to run
     * them against another supported major (16, 17 and 18, as the engine's {@code -Deventlog.it.image}). Unset:
     * {@link EssentialsTestContainers#POSTGRES_IMAGE}
     */
    static final String IMAGE_PROPERTY = "migration.it.image";

    @Container
    static final PostgreSQLContainer postgres = postgresContainer();

    private static PostgreSQLContainer postgresContainer() {
        var image = System.getProperty(IMAGE_PROPERTY, "");
        // max_prepared_transactions > 0 (default 0): MigrationEpochIT prepares a two-phase transaction
        if (image.isBlank()) {
            return EssentialsTestContainers.postgres().withCommand("postgres", "-c", "fsync=off", "-c", "max_prepared_transactions=10");
        }
        var defaults = EssentialsTestContainers.postgres();
        return new PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(defaults.getDatabaseName())
                .withUsername(defaults.getUsername())
                .withPassword(defaults.getPassword())
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_prepared_transactions=10");
    }

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
        final Jdbi                                                                 jdbi;
        /** The old engine's gap handler, when built with {@code gapHandling}; else null */
        PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration> gapHandler;

        OldEngine(IdentifierColumnType idType, JSONColumnType jsonType, String... types) {
            this(false, idType, jsonType, types);
        }

        @SuppressWarnings("unchecked")
        OldEngine(boolean gapHandling, IdentifierColumnType idType, JSONColumnType jsonType, String... types) {
            this.types = List.of(types);
            jdbi = Jdbi.create(dataSource);
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
                                             .setEventStreamGapHandlerFactory(es -> gapHandling ? (gapHandler = new PostgresqlEventStreamGapHandler<>(factory))
                                                                                                    : new NoEventStreamGapHandler<>())
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
         * An append in a two-phase transaction, prepared as {@code gid} and left so: its rows stay invisible, and its
         * backend leaves {@code pg_stat_activity}, until {@code COMMIT PREPARED}
         */
        void prepared(String type, String gid) {
            try {
                factory.usingUnitOfWork(uow -> {
                    eventStore.appendToStream(AggregateType.of(type), TestId.random(), Optional.empty(), events(1));
                    uow.handle().execute("PREPARE TRANSACTION '" + gid + "'");
                });
            } catch (RuntimeException ignored) {
                // the unit of work's own commit finds no transaction: PREPARE TRANSACTION ended it
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
     * compared over every column, payload and metadata as text
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

    // ------------------------------------------------------------------ the I8 fixture (MigrationInvariantIT, MigrationCliIT)

    /** The I8 fixture's names and values; see {@link #i8Source()} */
    static final class I8 {
        static final String ORDERS     = "Orders";
        static final String PAYMENTS   = "Payments";
        static final String OLD_ORDERS = "orders-projector";
        static final String OLD_LEGACY = "payments-legacy";

        static final long R         = 17;
        static final long OPEN_HOLE = 7;
        static final long GAP_FILL  = 13;

        private I8() {
        }
    }

    /**
     * The old engine's side of invariant I8 (MigrationInvariantIT's class comment), up to and excluding the sequence jump:
     * {@code Orders} 1..16 with holes, permanent gaps, a gap fill and the old subscriber's resume point {@code R = 17} with
     * its open transient gaps 7 and 13; {@code Payments} with a hole and an unmapped old subscriber
     */
    protected OldEngine i8Source() throws Exception {
        var old  = new OldEngine(true, IdentifierColumnType.UUID, JSONColumnType.JSONB, I8.ORDERS, I8.PAYMENTS);
        var gaps = old.gapHandler;
        old.streams(I8.ORDERS, 2, 3);                                               // 1..6
        old.burn(I8.ORDERS, 1);                                                     // 7
        old.burn(I8.ORDERS, 1);                                                     // 8
        old.factory.usingUnitOfWork(uow -> gaps.registerPermanentGaps(AggregateType.of(I8.ORDERS), List.of(GlobalEventOrder.of(8)), "rolled back"));
        var lateForPermanent = old.held(I8.ORDERS);                                 // 9, in flight
        lateForPermanent.awaitInserted();
        old.streams(I8.ORDERS, 1, 3);                                               // 10..12
        var gapFill = old.held(I8.ORDERS);                                          // 13, in flight
        gapFill.awaitInserted();
        old.streams(I8.ORDERS, 1, 3);                                               // 14..16

        // The old subscriber polls past the in-flight appends: its gap handler records them as open transient gaps
        var subscriber = SubscriberId.of(I8.OLD_ORDERS);
        var seen = old.eventStore.pollEvents(AggregateType.of(I8.ORDERS), 1L, Optional.of(100), Optional.of(Duration.ofMillis(20)), Optional.empty(),
                                             Optional.of(subscriber), Optional.empty())
                                 .map(e -> e.globalEventOrder().longValue())
                                 .takeUntil(g -> g == I8.R - 1)
                                 .collectList()
                                 .block(Duration.ofSeconds(30));
        assertThat(seen).as("delivered to the old subscriber").containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 10L, 11L, 12L, 14L, 15L, 16L);
        // It gives up on 9 (promoted to a permanent gap), and saves its resume point
        old.factory.usingUnitOfWork(uow -> gaps.gapHandlerFor(subscriber).giveUpTransientGaps(AggregateType.of(I8.ORDERS), List.of(GlobalEventOrder.of(9))));
        var resumePoints = new PostgresqlDurableSubscriptionRepository(old.jdbi, old.eventStore);
        var point        = resumePoints.createResumePoint(subscriber, AggregateType.of(I8.ORDERS), GlobalEventOrder.of(1));
        resumePoints.saveResumePoints(List.of(point.setResumeFromAndIncluding(GlobalEventOrder.of(I8.R))));
        resumePoints.createResumePoint(SubscriberId.of(I8.OLD_LEGACY), AggregateType.of(I8.PAYMENTS), GlobalEventOrder.of(2));

        // Both late appends commit before the cut-over
        lateForPermanent.releaseAndJoin();
        gapFill.releaseAndJoin();
        assertThat(longs("SELECT gap_global_event_order FROM transient_subscriber_gaps WHERE subscriber_id = '" + I8.OLD_ORDERS + "' ORDER BY 1"))
                .as("open transient gaps").containsExactly(I8.OPEN_HOLE, I8.GAP_FILL);
        assertThat(longs("SELECT gap_global_event_order FROM permanent_gaps WHERE aggregate_type = '" + I8.ORDERS + "' ORDER BY 1")).as("permanent gaps")
                                                                                                                                 .containsExactly(8L, 9L);
        assertThat(queryLong("SELECT resume_from_and_including_global_eventorder FROM durable_subscriptions WHERE subscriber_id = '" + I8.OLD_ORDERS + "'"))
                .isEqualTo(I8.R);

        old.streams(I8.PAYMENTS, 2, 2);
        old.burn(I8.PAYMENTS, 1);
        old.stream(I8.PAYMENTS, 1);
        return old;
    }

    // ------------------------------------------------------------------ SQL helpers

    protected List<Long> longs(String sql) throws SQLException {
        var result = new ArrayList<Long>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                result.add(resultSet.getLong(1));
            }
        }
        return result;
    }

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
