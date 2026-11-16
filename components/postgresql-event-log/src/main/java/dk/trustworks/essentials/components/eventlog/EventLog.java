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
    /** The database's shared catch-up permits (phase 5 §4), and this event log's own for the per-event-log mutant */
    private volatile CatchUpPermits                                   permits;
    private volatile CatchUpPermits                                   ownPermits;

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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        started = false;
    }

    /**
     * @return the subscription settings
     */
    public EventLogSettings settings() {
        return settings;
    }

    /**
     * Start the phase-2 subscription (design §1.2): {@code FRESH}, periodic progress, non-exclusive, one handler,
     * backstop wake-up only. <b>Temporary</b>: the phase-8 subscription API replaces it.
     * <p>
     * Progress is kept per {@code (subscriber, aggregateType, instanceName)}; two JVMs started with the same instance
     * name overwrite each other's progress (no lease in phase 2). A new subscription starts at {@code startFrom}; an
     * existing one resumes from its stored point.
     *
     * @param startFrom the first {@code global_order} a new subscription receives; 1 for everything
     * @return the running subscription
     */
    public Subscription subscribe(String subscriber, String aggregateType, String instanceName, long startFrom, Subscription.Handler handler) throws SQLException {
        return subscribe(subscriber, aggregateType, instanceName, startFrom, EventTypeFilter.all(), handler);
    }

    /**
     * As {@link #subscribe(String, String, String, long, Subscription.Handler)}, receiving only the event types of
     * {@code filter} (design phase 5 §1.2): the others count as handled without reaching the handler. <b>Temporary</b>.
     */
    public Subscription subscribe(String subscriber, String aggregateType, String instanceName, long startFrom, EventTypeFilter filter, Subscription.Handler handler)
            throws SQLException {
        return subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key(subscriber, aggregateType, instanceName), startFrom, handler, TestSeams.NONE).withFilter(filter));
    }

    /**
     * Start a phase-4 subscription whose pages are applied in the engine's transaction (design phase 4 §3.1): every page
     * of at most {@link EventLogSettings#pageSize()} events runs in a transaction on a connection of this event log's
     * {@code DataSource}, and its last statement moves the resume point, so the handler's writes and the progress commit
     * together. Non-exclusive, backstop wake-up only. <b>Temporary</b>: the phase-8 subscription API replaces it.
     *
     * @param startFrom the first {@code global_order} a new subscription receives; 1 for everything
     * @param mode      {@code CONTIGUOUS} (strict {@code global_order}, the view-projection default) or {@code FRESH}
     * @return the running subscription
     */
    public Subscription subscribe(String subscriber, String aggregateType, String instanceName, long startFrom, Subscription.ConsistencyMode mode,
                                  Subscription.PageHandler handler) throws SQLException {
        return subscribe(subscriber, aggregateType, instanceName, startFrom, mode, EventTypeFilter.all(), handler);
    }

    /**
     * As {@link #subscribe(String, String, String, long, Subscription.ConsistencyMode, Subscription.PageHandler)},
     * receiving only the event types of {@code filter} (design phase 5 §1.2). <b>Temporary</b>.
     */
    public Subscription subscribe(String subscriber, String aggregateType, String instanceName, long startFrom, Subscription.ConsistencyMode mode,
                                  EventTypeFilter filter, Subscription.PageHandler handler) throws SQLException {
        return subscribe(Subscription.SubscriptionSpec.inTransaction(new ProgressStore.Key(subscriber, aggregateType, instanceName), startFrom, mode, handler,
                                                                     TestSeams.NONE).withFilter(filter));
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
        var catchUp       = new Subscription.CatchUpContext(eventTypes, permits, ownPermits, () -> eventTypeIndex.valid(aggregateType));
        var subscription  = new Subscription(spec, readerLoop, EventLogStorage.TailStatements.of(relation), rs -> read(aggregateType, rs), progressStore, settings,
                                                  observer, catchUp);
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
     *
     * @return the aggregate type's validated names
     */
    public AggregateTypeTables registerAggregateType(String aggregateType) throws SQLException {
        requireStarted();
        var tables = AggregateTypeTables.of(aggregateType);
        var existing = aggregateTypes.get(aggregateType);
        if (existing != null) {
            return existing.tables;
        }
        ddl.execute(tables, EventLogSchemaContributor.aggregateTypeStatements(tables));
        var registered = new RegisteredAggregateType(tables);
        if (aggregateTypes.putIfAbsent(aggregateType, registered) == null && settings.eventTypeIndexed().contains(aggregateType)) {
            eventTypeIndex.ensure(tables);
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
                        conflicts.add(new StreamAppendConflictException(request.aggregateType(), request.aggregateId(), request.expectedEventOrder(),
                                                                        currentVersion(connection, append.type, request.aggregateId())));
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

    private static Long currentVersion(Connection connection, RegisteredAggregateType type, String aggregateId) throws SQLException {
        try (var statement = connection.prepareStatement(type.statements.headVersion())) {
            statement.setString(1, aggregateId);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : AppendRequest.NEW_STREAM;
            }
        }
    }

    /**
     * Tell the engine that a transaction which appended to {@code aggregateTypes} has committed (design phase 6 §2.2):
     * each type active in this event log's reader is woken, so its subscriptions see the commit at once instead of
     * within the backstop interval. Call it after {@code commit()} returns; with foundation's {@code UnitOfWork} the
     * engine's commit callback does it. A hint, never a statement of fact: a call before the commit, or for a
     * transaction that rolled back, costs one read; no call costs latency up to the backstop interval, never an event
     * (I7). Never blocks and never throws for an unknown or inactive type. <b>Temporary</b>: phase 8's API absorbs it.
     *
     * @param aggregateTypes the aggregate types the committed transaction appended to
     */
    public void afterCommit(Collection<String> aggregateTypes) {
        requireNonNull(aggregateTypes, "No aggregateTypes provided");
        for (var aggregateType : aggregateTypes) {
            if (aggregateType != null) {
                readerLoop.wake(aggregateType);
            }
        }
    }

    /**
     * {@link #afterCommit(Collection)} for the aggregate types of {@code committedRequests}, the requests the committed
     * transaction appended. <b>Temporary</b>.
     */
    public void afterCommit(List<AppendRequest> committedRequests) {
        requireNonNull(committedRequests, "No committedRequests provided");
        for (var request : committedRequests) {
            if (request != null) {
                readerLoop.wake(request.aggregateType());
            }
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
        volatile boolean                      verified;

        RegisteredAggregateType(AggregateTypeTables tables) {
            this.tables = tables;
            this.statements = EventLogStorage.TableStatements.of(tables);
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
