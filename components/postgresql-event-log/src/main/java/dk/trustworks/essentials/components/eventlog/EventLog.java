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
package dk.trustworks.essentials.components.eventlog;

import dk.trustworks.essentials.components.foundation.schema.SchemaOwnership;
import org.postgresql.util.PSQLException;
import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The new PostgreSQL event store engine's facade: append and stream load (design
 * {@code docs/plans/event-store-engine-phase2.md} §3.1, §3.2). Subscriptions are added by later units of phase 2.
 * <p>
 * Appends run on the caller's {@link Connection}, which the engine never commits: they join the caller's transaction.
 * One statement per aggregate; statements of the same shape go out as one pgjdbc batch. Each statement takes
 * {@code pg_current_xact_id()} before anything else, moves the stream's head row with a guarded update (or creates it),
 * and inserts the events only if that succeeded - so a lost optimistic-concurrency check returns zero rows, raises
 * {@link StreamAppendConflictException} and consumes no {@code global_order}.
 */
public final class EventLog {
    private static final Logger log = LoggerFactory.getLogger(EventLog.class);
    /** PostgreSQL's {@code undefined_table} */
    private static final String UNDEFINED_TABLE = "42P01";

    private final DataSource                                          dataSource;
    private final SchemaOwnership                                     schemaOwnership;
    private final EventLogSchema.AggregateTypeDdlExecutor             ddl;
    private final EventTypeRegistry                                   eventTypes;
    private final ConcurrentHashMap<String, RegisteredAggregateType>  aggregateTypes = new ConcurrentHashMap<>();
    private final EventLogSettings                                    settings;
    private final EventLogObserver                                    observer;
    private final ProgressStore                                       progressStore;
    private final ReaderLoop                                          readerLoop;
    private final List<Subscription>                             subscriptions  = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean                                          started;
    private final EventTypeIndex                                      eventTypeIndex;
    private final EventTypeIndex                                      tenantIndex;
    /** The database's shared catch-up permits (phase 5 §4), and this event log's own for the per-event-log mutant */
    private volatile CatchUpPermits                                   permits;
    private volatile CatchUpPermits                                   ownPermits;
    /** This event log's id in wake payloads (phase 6 §3.1): a listener drops its own instance's notifications */
    private final String                                              instance;
    /** Tier 1 (phase 6 §3); both {@code null} without {@link EventLogSettings#crossJvmWakeUp()} */
    private final WakeNotifier                                        notifier;
    private final WakeListener                                        listener;
    /** The mutants and hooks of the notifier and listener; for tests */
    private volatile TestSeams                                        wakeSeams      = TestSeams.NONE;
    /** Phase 7: the lease keeper of this event log's exclusive subscriptions, and its mutants and hooks (tests) */
    private final LeaseKeeper                                         leaseKeeper;
    private final EventLogCommitCallback                              commitCallback = new EventLogCommitCallback(this);
    private volatile TestSeams                                        leaseSeams     = TestSeams.NONE;
    private volatile TestSeams                                        adminSeams     = TestSeams.NONE;
    private final EventLogAdmin                                       admin;

    EventLog(DataSource dataSource, SchemaOwnership schemaOwnership, EventLogSchema.AggregateTypeDdlExecutor ddl, EventLogSettings settings, EventLogObserver observer) {
        this(dataSource, schemaOwnership, ddl, settings, observer, () -> schemaOwnership == SchemaOwnership.COMPONENT);
    }

    /**
     * @param createsSchema whether the engine creates the schema (schema mode {@code create}): the opt-in event-type
     *                      index is built only then (phase 5 §5.2)
     */
    EventLog(DataSource dataSource, SchemaOwnership schemaOwnership, EventLogSchema.AggregateTypeDdlExecutor ddl, EventLogSettings settings, EventLogObserver observer,
             java.util.function.BooleanSupplier createsSchema) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.observer = requireNonNull(observer, "No observer provided");
        this.progressStore = new ProgressStore(dataSource);
        this.readerLoop = new ReaderLoop(dataSource, settings, observer);
        this.schemaOwnership = requireNonNull(schemaOwnership, "No schemaOwnership provided");
        this.ddl = requireNonNull(ddl, "No ddl provided");
        this.eventTypes = new EventTypeRegistry(dataSource);
        this.eventTypeIndex = new EventTypeIndex(dataSource, createsSchema);
        this.tenantIndex = new EventTypeIndex(dataSource, createsSchema, EventTypeIndex.Kind.TENANT);
        this.instance = newInstanceId();
        this.admin = new DatabaseEventLogAdmin(dataSource, settings, progressStore, () -> subscriptions, () -> adminSeams);
        this.leaseKeeper = new LeaseKeeper(dataSource, settings, observer, settings.nodeName().isEmpty() ? instance : settings.nodeName() + "/" + instance, () -> leaseSeams);
        if (settings.crossJvmWakeUp()) {
            this.notifier = new WakeNotifier(instance, WakeNotifier.sendOnBorrowedConnection(dataSource), () -> wakeSeams, settings.notifySendMin());
            this.listener = new WakeListener(dataSource, settings, instance, readerLoop, notifier, () -> wakeSeams);
            readerLoop.onActivity(any -> {
                if (any) {
                    listener.start();
                } else {
                    listener.stop();
                }
            });
        } else {
            this.notifier = null;
            this.listener = null;
        }
    }

    /** A random 8-character id (phase 6 §3.1) */
    private static String newInstanceId() {
        var chars  = "abcdefghijklmnopqrstuvwxyz0123456789";
        var random = java.util.concurrent.ThreadLocalRandom.current();
        var id     = new StringBuilder(8);
        for (var i = 0; i < 8; i++) {
            id.append(chars.charAt(random.nextInt(chars.length())));
        }
        return id.toString();
    }

    /** This event log's wake id; for tests */
    String instance() {
        return instance;
    }

    /** The tier-1 notifier, {@code null} without cross-JVM wake-up; for tests */
    WakeNotifier notifier() {
        return notifier;
    }

    /** The tier-1 listener, {@code null} without cross-JVM wake-up; for tests */
    WakeListener listener() {
        return listener;
    }

    /** Set the notifier's and listener's mutants and hooks; package-private, for tests */
    void wakeSeams(TestSeams seams) {
        this.wakeSeams = requireNonNull(seams, "No seams provided");
    }

    /** Set the lease keeper's mutants and hooks (phase 7 §9); package-private, for tests */
    void leaseSeams(TestSeams seams) {
        this.leaseSeams = requireNonNull(seams, "No seams provided");
    }

    /** The lease keeper, for tests */
    LeaseKeeper leaseKeeper() {
        return leaseKeeper;
    }

    /** This event log's {@code lease_owner} (phase 7 §2.1): {@code [<nodeName>/]<instance>}, new on every event log */
    String leaseOwner() {
        return leaseKeeper.owner();
    }

    /**
     * @return a builder
     */
    public static EventLogBuilder builder() {
        return new EventLogBuilder();
    }

    /**
     * Create the fixed schema (with {@link SchemaOwnership#COMPONENT}) and verify the watermark prerequisite. Idempotent.
     *
     * @throws IllegalStateException if the database cannot support the watermark
     */
    public void start() throws SQLException {
        if (started) {
            return;
        }
        if (schemaOwnership == SchemaOwnership.COMPONENT) {
            EventLogSchema.initialize(dataSource);
        }
        EventLogSchema.verifyWatermarkPrerequisites(dataSource);
        var database = CatchUpPermits.databaseKey(dataSource);
        permits = CatchUpPermits.forDatabase(database, settings.catchUpPermits());
        ownPermits = CatchUpPermits.unshared(database, settings.catchUpPermits());
        warnIfPermitsReachPool();
        started = true;
    }

    /**
     * §4.3: a cap at or above the pool size does nothing; Hikari's maximum pool size is read reflectively, if present
     */
    private void warnIfPermitsReachPool() {
        try {
            var size = (Integer) dataSource.getClass().getMethod("getMaximumPoolSize").invoke(dataSource);
            if (size != null && settings.catchUpPermits() >= size) {
                log.info("catchUpPermits ({}) is at or above the pool's maximum size ({}): the cap does not limit catch-up reads, and appenders may wait for " +
                                 "connections during a catch-up storm", settings.catchUpPermits(), size);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // not a pool that reports its size
        }
    }

    /**
     * Stop every subscription (each saves its resume point once more). Idempotent.
     */
    public void stop() {
        for (var subscription : subscriptions) {
            subscription.stop();
        }
        subscriptions.clear();
        try {
            if (!readerLoop.awaitStopped(java.time.Duration.ofSeconds(30))) {
                log.warn("The reader loop did not end within 30 s of the last subscription's stop");
            }
            if (listener != null && !listener.awaitStopped(java.time.Duration.ofSeconds(5))) {
                log.warn("The wake listener did not end within 5 s of the last subscription's stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (notifier != null) {
            notifier.stop();
        }
        leaseKeeper.stop();
        started = false;
    }

    /**
     * @return the subscription settings
     */
    public EventLogSettings settings() {
        return settings;
    }

    /**
     * Start a subscription (design phase 8 §2.5). A {@link PeriodicHandler} gets one event at a time and the resume point is
     * saved periodically; a {@link PageHandler} gets pages in transactions from {@link PageTransactionSource#engine()}, each
     * of which moves the resume point as its last statement. An existing progress row resumes from its stored point; a
     * new one starts at {@link SubscriptionDefinition#startFrom()}. An exclusive subscription returns at once, possibly as
     * a standby.
     *
     * @return the running subscription
     */
    public SubscriptionHandle subscribe(SubscriptionDefinition definition, DeliveryHandler handler) throws SQLException {
        requireNonNull(handler, "No handler provided");
        return switch (handler) {
            case PeriodicHandler periodic -> handle(definition, subscribe(spec(definition, periodic, null, null)));
            case PageHandler page -> subscribe(definition, page, PageTransactionSource.engine());
        };
    }

    /**
     * {@link #subscribe(SubscriptionDefinition, DeliveryHandler)} for a periodic handler; lets a one-argument lambda be passed
     */
    public SubscriptionHandle subscribe(SubscriptionDefinition definition, PeriodicHandler handler) throws SQLException {
        return subscribe(definition, (DeliveryHandler) handler);
    }

    /**
     * {@link #subscribe(SubscriptionDefinition, DeliveryHandler)} for a page handler on {@link PageTransactionSource#engine()};
     * lets a two-argument lambda be passed
     */
    public SubscriptionHandle subscribe(SubscriptionDefinition definition, PageHandler handler) throws SQLException {
        return subscribe(definition, handler, PageTransactionSource.engine());
    }

    /**
     * Start an in-transaction subscription whose page transactions come from {@code source} (design phase 8 §2.4): the
     * handler's writes, the parked rows and the progress update commit together through it. The source must give
     * connections to this event log's database.
     *
     * @return the running subscription
     * @throws IllegalArgumentException if {@code source} connects to another database
     */
    public SubscriptionHandle subscribe(SubscriptionDefinition definition, PageHandler handler, PageTransactionSource source) throws SQLException {
        requireNonNull(handler, "No handler provided");
        requireNonNull(source, "No source provided");
        requireStarted();
        if (!EnginePageTransactions.isEngine(source)) {
            requireSameDatabase(source);
        }
        return handle(definition, subscribe(spec(definition, null, handler, source)));
    }

    /**
     * The package-private spec of a definition: the seams-free path every test seam path shares. The tenant filter goes
     * into the spec beside the event-type filter (phase 8 §15 question 7): in SQL on catch-up and bridge reads, in memory
     * on live batches; an event of another tenant counts as handled without reaching the handler
     */
    static Subscription.SubscriptionSpec spec(SubscriptionDefinition definition, PeriodicHandler periodic, PageHandler page, PageTransactionSource source) {
        requireNonNull(definition, "No definition provided");
        var exclusive = definition.ownership() instanceof Ownership.Exclusive;
        var key       = new ProgressStore.Key(definition.subscriber(), definition.aggregateType(), definition.ownership().instanceName());
        var fromHead  = definition.startFrom() instanceof StartFrom.Head;
        var initial   = definition.startFrom() instanceof StartFrom.GlobalOrder order ? order.value() : 1;
        var tenant    = definition.tenant().orElse(null);
        var model = periodic != null ? ProgressModel.PERIODIC : ProgressModel.IN_HANDLER_TRANSACTION;
        return new Subscription.SubscriptionSpec(key, initial, definition.consistency(), model, periodic, page, TestSeams.NONE, definition.eventTypes(), exclusive, source,
                                                 definition.errors().orElse(null), definition.filterDigest(), fromHead, tenant);
    }

    /** §2.4: a source on another database would make the page's progress non-atomic with its output */
    private void requireSameDatabase(PageTransactionSource source) throws SQLException {
        var open = source.begin();
        String theirs;
        try (var statement = open.connection().prepareStatement(EventLogStorage.DATABASE_IDENTITY);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            theirs = resultSet.getString(1) + "@" + resultSet.getString(2) + ":" + resultSet.getInt(3);
        } finally {
            open.rollback();
        }
        String ours;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(EventLogStorage.DATABASE_IDENTITY);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            ours = resultSet.getString(1) + "@" + resultSet.getString(2) + ":" + resultSet.getInt(3);
        }
        if (!ours.equals(theirs)) {
            throw new IllegalArgumentException("The PageTransactionSource connects to database " + theirs + ", not this event log's " + ours +
                                                       ": a page's progress would not commit atomically with its output");
        }
    }

    private SubscriptionHandle handle(SubscriptionDefinition definition, Subscription subscription) {
        return new DefinedSubscription(definition, subscription);
    }

    /**
     * Foundation's unit-of-work callback that calls {@link #afterCommit(Collection)} after a commit (design phase 8 §2.8):
     * register it on a unit of work with the aggregate types it appended to as the associated resources
     *
     * @return this event log's commit callback
     */
    public dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkLifecycleCallback<String> commitCallback() {
        return commitCallback;
    }

    /**
     * The admin operations (design phase 8 §4): listing, parked rows, reset, retry and discard from any node, retired
     * instances
     *
     * @return this event log's admin facade
     */
    public EventLogAdmin admin() {
        requireStarted();
        return admin;
    }

    /** The admin facade's mutants (phase 8 §9.3); for tests */
    void adminSeams(TestSeams seams) {
        this.adminSeams = requireNonNull(seams, "No seams provided");
    }

    /**
     * Reset a subscription to {@code resumeFrom}: bump its reset epoch and delete its parked rows in one transaction
     * (design phase 4 §4.5). A running subscription notices at its next save or page. Package-private until phase 8's
     * reset API.
     *
     * @return whether the progress row existed
     */
    boolean reset(String subscriber, String aggregateType, String instanceName, long resumeFrom) throws SQLException {
        requireStarted();
        return progressStore.reset(new ProgressStore.Key(subscriber, aggregateType, instanceName), resumeFrom);
    }

    /**
     * With test seams: package-private, for the invariant ITs
     */
    Subscription subscribe(Subscription.SubscriptionSpec spec) throws SQLException {
        requireStarted();
        var aggregateType = spec.key().aggregateType();
        var type          = registered(aggregateType);
        var relation      = spec.seams().readTable() != null ? spec.seams().readTable() : type.tables.eventTable();
        if (settings.eventTypeIndexed().contains(aggregateType)) {
            eventTypeIndex.refresh(type.tables);
        }
        if (spec.tenant() != null && settings.tenantIndexed().contains(aggregateType)) {
            tenantIndex.refresh(type.tables);
        }
        var catchUp       = new Subscription.CatchUpContext(eventTypes, permits, ownPermits, () -> eventTypeIndex.valid(aggregateType),
                                                            () -> tenantIndex.valid(aggregateType));
        Lease lease = null;
        if (spec.exclusive()) {
            for (var running : subscriptions) {
                if (running.lease() != null && running.lease().key().equals(spec.key()) && running.isRunning()) {
                    throw new IllegalStateException("Exclusive subscription " + spec.key().subscriber() + " on " + aggregateType + " is already running in this EventLog");
                }
            }
            lease = new Lease(spec.key(), new Lease.NewRow(spec.mode().name(), spec.model().name(), spec.initialResumeFrom(), spec.fromHead() ? type.tables.eventTable() : null, spec.filterDigest()), leaseKeeper.owner(),
                              settings.leaseLocalStop().toNanos(), System::nanoTime);
        }
        var subscription  = new Subscription(spec, readerLoop, EventLogStorage.TailStatements.of(relation), rs -> read(aggregateType, rs), progressStore, settings,
                                                  observer, catchUp, lease, lease == null ? null : leaseKeeper);
        subscription.start();
        subscriptions.add(subscription);
        return subscription;
    }

    /**
     * The tail reader of {@code aggregateType}, for tests: {@code null} while the type has no subscription or its reader
     * restarts. Only {@link TailReader#state()} may be called from outside the reader loop's thread.
     */
    TailReader reader(String aggregateType) {
        return readerLoop.reader(aggregateType);
    }

    /** The opt-in event-type index, for tests */
    EventTypeIndex eventTypeIndex() {
        return eventTypeIndex;
    }

    /** The opt-in tenant index (phase 8 §15 question 7), for tests */
    EventTypeIndex tenantIndex() {
        return tenantIndex;
    }

    /** The shared catch-up permits of this event log's database, for tests */
    CatchUpPermits catchUpPermits() {
        return permits;
    }

    /** The shared reader loop, for tests */
    ReaderLoop readerLoop() {
        return readerLoop;
    }

    /**
     * @return the event-type registry
     */
    public EventTypeRegistry eventTypes() {
        return eventTypes;
    }

    /**
     * Register an aggregate type: create (or have the harness create) its event and head tables and registry row.
     * Idempotent. The table is verified - identity {@code CACHE 1}, the registry row maps back to this aggregate type -
     * now if it exists, otherwise before its first append or load.
     * <p>
     * A type whose move from the old event store is still being copied ({@code el_migration} state {@code COPYING},
     * design phase 9 §3.8) is refused: this node's reader would take the copied rows appearing below its watermark as
     * retired holes, and its appends would draw values a later copy epoch collides with. Register it after the cut-over.
     *
     * @return the aggregate type's validated names
     * @throws IllegalStateException if the aggregate type is being copied from the old event store
     */
    public AggregateTypeTables registerAggregateType(String aggregateType) throws SQLException {
        requireStarted();
        var tables = AggregateTypeTables.of(aggregateType);
        var existing = aggregateTypes.get(aggregateType);
        if (existing != null) {
            return existing.tables;
        }
        if (!adminSeams.has(WatermarkMutant.GUARD_SKIPPED)) {
            refuseWhileCopying(aggregateType);
        }
        ddl.execute(tables, EventLogSchemaContributor.aggregateTypeStatements(tables));
        var registered = new RegisteredAggregateType(tables);
        if (aggregateTypes.putIfAbsent(aggregateType, registered) == null) {
            if (settings.eventTypeIndexed().contains(aggregateType)) {
                eventTypeIndex.ensure(tables);
            }
            if (settings.tenantIndexed().contains(aggregateType)) {
                tenantIndex.ensure(tables);
            }
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, tables.eventTable());
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                if (resultSet.getBoolean(1)) {
                    aggregateTypes.get(aggregateType).verify(connection);
                }
            }
        }
        return tables;
    }

    /**
     * The registration guard (phase 9 §3.8): one primary-key read of {@code el_migration}. A schema without the table
     * (one created before phase 9 and not upgraded, under a validating harness) has had no migration written to it
     */
    private void refuseWhileCopying(String aggregateType) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(EventLogStorage.MIGRATION_STATE)) {
            statement.setString(1, aggregateType);
            try (var resultSet = statement.executeQuery()) {
                if (resultSet.next() && "COPYING".equals(resultSet.getString(1))) {
                    throw new IllegalStateException("Aggregate type '" + aggregateType + "' is being copied from the old event store (" +
                                                            EventLogSchema.MIGRATION_TABLE + " row: state COPYING, source table " + resultSet.getString(2) +
                                                            ", started " + resultSet.getObject(3, java.time.OffsetDateTime.class) +
                                                            "). Its tables may not be used by the event log before the migration's cut-over; " +
                                                            "start this node, or register the type, after the cut-over");
                }
            }
        } catch (SQLException e) {
            if (!UNDEFINED_TABLE.equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    /**
     * Append one stream's events on the caller's connection. See {@link #append(Connection, List)}.
     */
    public AppendResult append(Connection connection, AppendRequest request) throws SQLException {
        return append(connection, List.of(requireNonNull(request, "No request provided"))).getFirst();
    }

    /**
     * Append the events of several streams - of one or several aggregate types - on the caller's connection, in the
     * caller's transaction (design §3.1, plan §5.2). New event types are registered first, each in its own committed
     * transaction on the engine's {@code DataSource}.
     *
     * @return one result per request, in request order
     * @throws StreamAppendConflictException for the first request (in request order) that lost its optimistic check;
     *                                       further conflicts are attached as suppressed. The caller must roll back
     * @throws SQLException                  on any other database error
     */
    public List<AppendResult> append(Connection connection, List<AppendRequest> requests) throws SQLException {
        requireNonNull(connection, "No connection provided");
        requireNonEmpty(requireNonNull(requests, "No requests provided"), "No requests provided");
        requireStarted();
        // Group by statement text, keeping each request's index
        var groups = new LinkedHashMap<String, List<Integer>>();
        var bound  = new ArrayList<BoundAppend>(requests.size());
        for (var index = 0; index < requests.size(); index++) {
            var request    = requests.get(index);
            var type       = registered(request.aggregateType());
            var statements = type.statements;
            var sql = request.expectedEventOrder() == null ? statements.appendWithoutExpectation()
                                                           : request.expectedEventOrder() == AppendRequest.NEW_STREAM ? statements.appendNewStream()
                                                                                                                      : statements.appendAfter();
            var typeIds = new Short[request.events().size()];
            for (var i = 0; i < typeIds.length; i++) {
                typeIds[i] = eventTypes.idOf(request.events().get(i).eventType());
            }
            bound.add(new BoundAppend(request, type, typeIds));
            groups.computeIfAbsent(sql, k -> new ArrayList<>()).add(index);
        }

        var results   = new AppendResult[requests.size()];
        var conflicts = new ArrayList<StreamAppendConflictException>();
        for (var group : groups.entrySet()) {
            executeGroup(connection, group.getKey(), group.getValue(), bound, results, conflicts);
        }
        if (!conflicts.isEmpty()) {
            var first = conflicts.getFirst();
            conflicts.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
        return List.of(results);
    }

    private void executeGroup(Connection connection, String sql, List<Integer> indexes, List<BoundAppend> bound,
                              AppendResult[] results, List<StreamAppendConflictException> conflicts) throws SQLException {
        try (var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (var index : indexes) {
                bind(connection, statement, bound.get(index));
                statement.addBatch();
            }
            int[] counts;
            try {
                counts = statement.executeBatch();
            } catch (BatchUpdateException e) {
                throw mapBatchFailure(e, indexes, bound);
            }
            try (var keys = statement.getGeneratedKeys()) {
                for (var i = 0; i < indexes.size(); i++) {
                    var append  = bound.get(indexes.get(i));
                    var request = append.request;
                    var rows    = counts[i];
                    if (rows == 0) {
                        // No read of the stream's actual version: it would cost a loser one more round trip (phase 8 §18.5)
                        conflicts.add(new StreamAppendConflictException(request.aggregateType(), request.aggregateId(), request.expectedEventOrder(), null));
                        continue;
                    }
                    var events = new ArrayList<AppendedEvent>(rows);
                    for (var r = 0; r < rows; r++) {
                        if (!keys.next()) {
                            throw new IllegalStateException("Append to " + request.aggregateType() + "/" + request.aggregateId() + " reported " + rows + " rows but returned fewer");
                        }
                        events.add(new AppendedEvent(request.events().get(r).eventId(), keys.getLong(1), keys.getLong(2)));
                    }
                    results[indexes.get(i)] = new AppendResult(request.aggregateType(), request.aggregateId(), events);
                }
            }
        }
    }

    private static void bind(Connection connection, PreparedStatement statement, BoundAppend append) throws SQLException {
        var request = append.request;
        var n       = (long) request.events().size();
        var p       = 1;
        if (request.expectedEventOrder() == null) {
            statement.setString(p++, request.aggregateId());
            statement.setLong(p++, n);
            statement.setLong(p++, n);
            statement.setLong(p++, n);
        } else if (request.expectedEventOrder() == AppendRequest.NEW_STREAM) {
            statement.setString(p++, request.aggregateId());
            statement.setLong(p++, n);
        } else {
            statement.setLong(p++, n);
            statement.setString(p++, request.aggregateId());
            statement.setLong(p++, request.expectedEventOrder());
            statement.setLong(p++, n);
        }
        var size        = request.events().size();
        var ids         = new UUID[size];
        var causedBy    = new UUID[size];
        var correlation = new UUID[size];
        var revisions   = new Short[size];
        var timestamps  = new String[size];
        var payloads    = new String[size];
        var metadata    = new String[size];
        for (var i = 0; i < size; i++) {
            var event = request.events().get(i);
            ids[i] = event.eventId();
            causedBy[i] = event.causedByEventId();
            correlation[i] = event.correlationId();
            revisions[i] = (short) event.eventRevision();
            timestamps[i] = event.timestamp().truncatedTo(ChronoUnit.MICROS).toString();
            payloads[i] = event.payload();
            metadata[i] = event.metadata();
        }
        statement.setString(p++, request.aggregateId());
        statement.setString(p++, request.tenant());
        statement.setArray(p++, connection.createArrayOf("uuid", ids));
        statement.setArray(p++, connection.createArrayOf("uuid", causedBy));
        statement.setArray(p++, connection.createArrayOf("uuid", correlation));
        statement.setArray(p++, connection.createArrayOf("int2", append.typeIds));
        statement.setArray(p++, connection.createArrayOf("int2", revisions));
        statement.setArray(p++, connection.createArrayOf("text", timestamps));
        statement.setArray(p++, connection.createArrayOf("text", payloads));
        statement.setArray(p, connection.createArrayOf("text", metadata));
    }

    /**
     * A unique violation on the stream-order constraint is the backstop for a head row out of step with its events;
     * mapped by constraint name, never by message. The transaction is aborted, so the actual order cannot be read.
     */
    private SQLException mapBatchFailure(BatchUpdateException e, List<Integer> indexes, List<BoundAppend> bound) {
        var counts = e.getUpdateCounts();
        var failed = counts.length;
        for (var i = 0; i < counts.length; i++) {
            if (counts[i] == Statement.EXECUTE_FAILED) {
                failed = i;
                break;
            }
        }
        for (Throwable cause = e; cause != null; cause = cause instanceof SQLException sql && sql.getNextException() != null ? sql.getNextException() : cause.getCause()) {
            if (cause instanceof PSQLException psql && "23505".equals(psql.getSQLState()) && psql.getServerErrorMessage() != null) {
                var constraint = psql.getServerErrorMessage().getConstraint();
                for (var index : indexes) {
                    var append = bound.get(index);
                    if (append.type.tables.streamOrderConstraint().equals(constraint)) {
                        var request = failed < indexes.size() ? bound.get(indexes.get(failed)).request : append.request;
                        log.error("Unique violation on {} appending to {}/{}: the stream's head row in {} is out of step with its events - " +
                                          "something other than the event log wrote to the event table", constraint, request.aggregateType(), request.aggregateId(),
                                  append.type.tables.headTable());
                        throw new StreamAppendConflictException(request.aggregateType(), request.aggregateId(), request.expectedEventOrder(), null);
                    }
                }
            }
        }
        return e;
    }

    /**
     * Tell the engine that a transaction which appended to {@code aggregateTypes} has committed (design phase 6 §2.2):
     * each type active in this event log's reader is woken, so its subscriptions see the commit at once instead of
     * within the backstop interval; with {@link EventLogSettings#crossJvmWakeUp()} each registered type is also sent, coalesced,
     * as one {@code NOTIFY} on a borrowed connection, waking other event logs' readers (tier 1, §3). Call it after {@code commit()} returns; with foundation's {@code UnitOfWork} the
     * engine's commit callback does it. A hint, never a statement of fact: a call before the commit, or for a
     * transaction that rolled back, costs one read; no call costs latency up to the backstop interval, never an event
     * (I7). Never blocks and never throws for an unknown or inactive type. An application that appends on a connection it commits
     * itself calls it; with foundation's {@code UnitOfWork}, register {@link #commitCallback()} instead.
     *
     * @param aggregateTypes the aggregate types the committed transaction appended to
     */
    public void afterCommit(Collection<String> aggregateTypes) {
        requireNonNull(aggregateTypes, "No aggregateTypes provided");
        for (var aggregateType : aggregateTypes) {
            if (aggregateType != null) {
                committed(aggregateType);
            }
        }
    }

    /** Tier 2: wake the type if active here; tier 1: hand it to the notifier if this event log appends to it */
    private void committed(String aggregateType) {
        readerLoop.wake(aggregateType);
        if (notifier != null && aggregateTypes.containsKey(aggregateType)) {
            notifier.add(aggregateType);
        }
    }

    /**
     * Load a stream's events with {@code fromEventOrder <= event_order <= toEventOrder}, in event order, on the caller's
     * connection (design §3.2).
     */
    public List<StoredEvent> loadStream(Connection connection, String aggregateType, String aggregateId, long fromEventOrder, long toEventOrder) throws SQLException {
        requireNonNull(connection, "No connection provided");
        requireNonBlank(aggregateId, "No aggregateId provided");
        requireStarted();
        var type = registered(aggregateType);
        try (var statement = connection.prepareStatement(type.statements.loadStream())) {
            statement.setString(1, aggregateId);
            statement.setLong(2, fromEventOrder);
            statement.setLong(3, toEventOrder);
            try (var resultSet = statement.executeQuery()) {
                var events = new ArrayList<StoredEvent>();
                while (resultSet.next()) {
                    events.add(read(aggregateType, resultSet));
                }
                return events;
            }
        }
    }

    /**
     * Load a whole stream on a connection of the engine's {@code DataSource}.
     */
    public List<StoredEvent> loadStream(String aggregateType, String aggregateId) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return loadStream(connection, aggregateType, aggregateId, 0, Long.MAX_VALUE);
        }
    }

    /**
     * @return the event with {@code eventId}, if the aggregate type's table holds it
     */
    public Optional<StoredEvent> findEvent(Connection connection, String aggregateType, UUID eventId) throws SQLException {
        requireNonNull(connection, "No connection provided");
        requireNonNull(eventId, "No eventId provided");
        requireStarted();
        var type = registered(aggregateType);
        try (var statement = connection.prepareStatement(type.statements.findEvent())) {
            statement.setObject(1, eventId);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(read(aggregateType, resultSet)) : Optional.empty();
            }
        }
    }

    // ---- phase 8 unit 8b-A: the reads the EventStore adapter needs (§5.2) ----

    /**
     * The last event of a stream (the adapter's {@code loadLastPersistedEventRelatedTo}): one row, read backwards on the
     * {@code (aggregate_id, event_order)} constraint, on the caller's connection.
     *
     * @return the stream's event with the highest event order, or empty for a stream with no events
     */
    public Optional<StoredEvent> findLastEvent(Connection connection, String aggregateType, String aggregateId) throws SQLException {
        requireNonNull(connection, "No connection provided");
        requireNonBlank(aggregateId, "No aggregateId provided");
        requireStarted();
        var type = registered(aggregateType);
        try (var statement = connection.prepareStatement(type.reads.lastEvent())) {
            statement.setString(1, aggregateId);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(read(aggregateType, resultSet)) : Optional.empty();
            }
        }
    }

    /**
     * The events with the given ids (the adapter's {@code loadEvents}): one statement on the event-id index, on the
     * caller's connection. An id with no event is skipped; a repeated id is returned once.
     *
     * @return the events found, in global order
     */
    public List<StoredEvent> findEvents(Connection connection, String aggregateType, Collection<UUID> eventIds) throws SQLException {
        requireNonNull(connection, "No connection provided");
        requireNonNull(eventIds, "No eventIds provided");
        requireStarted();
        var type = registered(aggregateType);
        if (eventIds.isEmpty()) {
            return List.of();
        }
        var ids = eventIds.toArray(UUID[]::new);
        for (var id : ids) {
            requireNonNull(id, "An eventId is null");
        }
        try (var statement = connection.prepareStatement(type.reads.findEvents())) {
            statement.setArray(1, connection.createArrayOf("uuid", ids));
            return readAll(aggregateType, statement);
        }
    }

    /**
     * Read events by global order (the adapter's {@code loadEventsByGlobalOrder}) on the caller's connection, streamed:
     * the statement runs with {@link GlobalOrderRead#fetchSize()}, which pgjdbc honours only while the connection's
     * autocommit is off (a unit of work's connection); with autocommit on, every row is fetched at once. Reads the
     * rows committed when the statement runs; it is not a subscription and makes no hole guarantee.
     * <p>
     * The caller must close the returned stream (try-with-resources), and before its transaction ends; closing it
     * closes the statement, never the connection. An event-type name never registered matches nothing; a read whose
     * names are all unknown, or with an empty range and no additional orders, returns an empty stream without a
     * statement. Additional orders are read first, in one statement, and merged into the streamed range in global order.
     *
     * @return the matching events, in global order
     */
    public java.util.stream.Stream<StoredEvent> loadByGlobalOrder(Connection connection, GlobalOrderRead read) throws SQLException {
        requireNonNull(connection, "No connection provided");
        return openGlobalOrderRead(connection, read, () -> {
        });
    }

    /**
     * {@link #loadByGlobalOrder(Connection, GlobalOrderRead)} on a connection borrowed from the engine's
     * {@code DataSource} with autocommit off, so the read is streamed in {@link GlobalOrderRead#fetchSize()} rows.
     * Closing the stream rolls the read-only transaction back and returns the connection; until then it is held.
     */
    public java.util.stream.Stream<StoredEvent> loadByGlobalOrder(GlobalOrderRead read) throws SQLException {
        requireNonNull(read, "No read provided");
        requireStarted();
        var connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            return openGlobalOrderRead(connection, read, () -> {
                try {
                    connection.rollback();
                } finally {
                    connection.close();
                }
            });
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
    }

    /**
     * @param release run when the stream closes, or at once when no stream is returned
     */
    private java.util.stream.Stream<StoredEvent> openGlobalOrderRead(Connection connection, GlobalOrderRead read, ResultSetStream.Release release) throws SQLException {
        requireNonNull(read, "No read provided");
        requireStarted();
        var type    = registered(read.aggregateType());
        Short[] typeIds = null;
        if (!read.eventTypes().isEmpty()) {
            var ids = new ArrayList<Short>(read.eventTypes().size());
            for (var name : read.eventTypes()) {
                var id = eventTypes.knownIdOf(name);
                if (id != null) {
                    ids.add(id);
                }
            }
            typeIds = ids.toArray(Short[]::new);
        }
        if (read.isEmpty() || (typeIds != null && typeIds.length == 0)) {
            release.run();
            return java.util.stream.Stream.empty();
        }
        PreparedStatement statement = null;
        try {
            var additional = List.<StoredEvent>of();
            if (!read.additionalGlobalOrders().isEmpty()) {
                try (var extra = connection.prepareStatement(type.reads.additional())) {
                    extra.setArray(1, connection.createArrayOf("bigint", read.additionalGlobalOrders().toArray(Long[]::new)));
                    extra.setLong(2, read.fromInclusive());
                    extra.setLong(3, read.toInclusive());
                    bindReadFilter(connection, extra, 4, typeIds, read.tenant());
                    additional = readAll(read.aggregateType(), extra);
                }
            }
            statement = connection.prepareStatement(type.reads.range());
            statement.setFetchSize(read.fetchSize());
            statement.setLong(1, read.fromInclusive());
            statement.setLong(2, read.toInclusive());
            bindReadFilter(connection, statement, 3, typeIds, read.tenant());
            var resultSet = statement.executeQuery();
            var opened    = statement;
            var range = ResultSetStream.of(resultSet, rs -> read(read.aggregateType(), rs), () -> {
                try {
                    opened.close();
                } finally {
                    release.run();
                }
            });
            return additional.isEmpty() ? range : ResultSetStream.merge(range, additional, Comparator.comparingLong(StoredEvent::globalOrder));
        } catch (SQLException | RuntimeException e) {
            try {
                if (statement != null) {
                    statement.close();
                }
            } catch (SQLException closing) {
                e.addSuppressed(closing);
            }
            try {
                release.run();
            } catch (SQLException releasing) {
                e.addSuppressed(releasing);
            }
            throw e;
        }
    }

    /** Binds {@link EventLogStorage.ReadStatements#FILTER_PARAMETERS} parameters: the type ids twice, the tenant twice */
    private static int bindReadFilter(Connection connection, PreparedStatement statement, int p, Short[] typeIds, String tenant) throws SQLException {
        for (var i = 0; i < 2; i++) {
            if (typeIds == null) {
                statement.setNull(p++, Types.ARRAY);
            } else {
                statement.setArray(p++, connection.createArrayOf("int2", typeIds));
            }
        }
        for (var i = 0; i < 2; i++) {
            statement.setString(p++, tenant);
        }
        return p;
    }

    /**
     * @return the highest {@code global_order} of the aggregate type's table (the adapter's
     * {@code findHighestGlobalEventOrderPersisted}), or empty for an empty table; read on the primary key. An order
     * drawn by a transaction that rolled back is never returned, but one of an uncommitted transaction is not seen
     * either, so a lower order may still commit
     */
    public OptionalLong findHighestGlobalOrder(Connection connection, String aggregateType) throws SQLException {
        requireNonNull(connection, "No connection provided");
        requireStarted();
        return readOptionalLong(connection, registered(aggregateType).reads.highest());
    }

    /**
     * @return the lowest {@code global_order} of the aggregate type's table (the adapter's
     * {@code findLowestGlobalEventOrderPersisted}), or empty for an empty table; read on the primary key
     */
    public OptionalLong findLowestGlobalOrder(Connection connection, String aggregateType) throws SQLException {
        requireNonNull(connection, "No connection provided");
        requireStarted();
        return readOptionalLong(connection, registered(aggregateType).reads.lowest());
    }

    private static OptionalLong readOptionalLong(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql); var resultSet = statement.executeQuery()) {
            resultSet.next();
            var value = resultSet.getLong(1);
            return resultSet.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
        }
    }

    /**
     * The events caused by an event (the adapter's {@code loadEventsCausedBy}): one statement per aggregate type
     * registered with this event log, each on the partial {@code caused_by_event_id} index the schema creates with
     * the event table (§14 question 8), on the caller's connection.
     *
     * @return the events whose {@code caused_by_event_id} is {@code causedByEventId}: aggregate types by event table
     * name, each in global order; empty if none
     */
    public List<StoredEvent> findCausedBy(Connection connection, UUID causedByEventId) throws SQLException {
        requireNonNull(connection, "No connection provided");
        requireNonNull(causedByEventId, "No causedByEventId provided");
        requireStarted();
        var types = new ArrayList<>(aggregateTypes.keySet());
        types.sort(Comparator.comparing(name -> aggregateTypes.get(name).tables.eventTable()));
        var events = new ArrayList<StoredEvent>();
        for (var aggregateType : types) {
            var type = registered(aggregateType);
            try (var statement = connection.prepareStatement(type.reads.causedBy())) {
                statement.setObject(1, causedByEventId);
                events.addAll(readAll(aggregateType, statement));
            }
        }
        return events;
    }

    private List<StoredEvent> readAll(String aggregateType, PreparedStatement statement) throws SQLException {
        try (var resultSet = statement.executeQuery()) {
            var events = new ArrayList<StoredEvent>();
            while (resultSet.next()) {
                events.add(read(aggregateType, resultSet));
            }
            return events;
        }
    }

    private StoredEvent read(String aggregateType, ResultSet resultSet) throws SQLException {
        return new StoredEvent(aggregateType,
                               resultSet.getLong(1),
                               resultSet.getLong(2),
                               resultSet.getObject(3, UUID.class),
                               resultSet.getObject(4, UUID.class),
                               resultSet.getObject(5, UUID.class),
                               eventTypes.nameOf(resultSet.getShort(6)),
                               resultSet.getShort(7),
                               resultSet.getObject(8, java.time.OffsetDateTime.class),
                               resultSet.getString(12),
                               resultSet.getString(9),
                               resultSet.getString(10),
                               resultSet.getString(11));
    }

    private RegisteredAggregateType registered(String aggregateType) throws SQLException {
        requireNonBlank(aggregateType, "No aggregateType provided");
        var type = aggregateTypes.get(aggregateType);
        if (type == null) {
            throw new IllegalArgumentException("Aggregate type '" + aggregateType + "' is not registered with this EventLog - call registerAggregateType first");
        }
        if (!type.verified) {
            try (var connection = dataSource.getConnection()) {
                type.verify(connection);
            }
        }
        return type;
    }

    private void requireStarted() {
        if (!started) {
            throw new IllegalStateException("The EventLog is not started - call start() first");
        }
    }

    private static final class RegisteredAggregateType {
        final AggregateTypeTables             tables;
        final EventLogStorage.TableStatements statements;
        final EventLogStorage.ReadStatements  reads;
        volatile boolean                      verified;

        RegisteredAggregateType(AggregateTypeTables tables) {
            this.tables = tables;
            this.statements = EventLogStorage.TableStatements.of(tables);
            this.reads = EventLogStorage.ReadStatements.of(tables.eventTable());
        }

        void verify(Connection connection) throws SQLException {
            EventLogSchema.verifyIdentityCache(connection, tables);
            EventLogSchema.verifyRegistration(connection, tables);
            verified = true;
        }
    }

    private record BoundAppend(AppendRequest request, RegisteredAggregateType type, Short[] typeIds) {
    }
}
