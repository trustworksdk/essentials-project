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

package dk.trustworks.essentials.components.eventlog.migration;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.migration.PreflightReport.*;
import dk.trustworks.essentials.components.eventlog.migration.MigrationSeams.Hook;
import org.slf4j.*;

import java.sql.*;
import java.time.*;
import java.util.*;

import static dk.trustworks.essentials.components.eventlog.migration.MigrationMutant.*;
import static dk.trustworks.essentials.components.eventlog.migration.MigrationStorage.*;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The one-way move of aggregate types from {@code PostgresqlEventStore}'s tables to the event log's (design
 * {@code docs/plans/event-store-engine-phase9.md}), as an operator runs it (§5):
 * <ol>
 *     <li>{@link #preflight()}: read-only checks and a report (§3.1)</li>
 *     <li>{@link #prepare()}: pre-flight, then register each aggregate type with the event log and mark it
 *     {@code COPYING} in {@code el_migration}, which keeps every event log node off its tables (§3.8)</li>
 *     <li>{@link #copyEpoch()}, as often as wanted while the old engine runs: copy what can no longer change (§3.3), in
 *     batches whose checkpoint commits with them (§3.7); reruns resume</li>
 *     <li>stop every old-engine node, then {@link #cutover()}: one transaction under {@code SHARE} locks on the old
 *     tables, copying the delta, writing heads, setting the identity sequences, running the {@link CutoverStep}s and
 *     marking the types {@code CUT_OVER} (§3.5)</li>
 * </ol>
 * The old tables are only read, and {@code SHARE}-locked during the cut-over. Not thread-safe: one migration runs at a
 * time, and the checkpoint's compare-and-set refuses a second concurrent copier.
 */
public final class Migration {
    private static final Logger log = LoggerFactory.getLogger(Migration.class);

    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String UNIQUE_VIOLATION   = "23505";
    private static final String UNDEFINED_TABLE    = "42P01";

    private final MigrationPlan  plan;
    private       MigrationSeams seams = MigrationSeams.NONE;

    public Migration(MigrationPlan plan) {
        this.plan = requireNonNull(plan, "No plan provided");
    }

    /** Test seam: mutants, crash hooks, interning fault */
    void seams(MigrationSeams seams) {
        this.seams = requireNonNull(seams, "No seams provided");
    }

    // ------------------------------------------------------------------ pre-flight (§3.1)

    /**
     * Read-only: the column types, identity sequences and §2.4's refusals of every old table (one scan per table), the
     * event-type id space, and the target's state. Writes nothing.
     */
    public PreflightReport preflight() throws SQLException {
        try (var connection = connection()) {
            var reports = new ArrayList<TableReport>();
            for (var table : plan.tables()) {
                reports.add(preflight(connection, table));
            }
            var planRefusals = new ArrayList<Refusal>();
            var types        = distinctEventTypes(connection, reports);
            if (types > Short.MAX_VALUE) {
                planRefusals.add(Refusal.of(RefusalKind.TOO_MANY_EVENT_TYPES, types + " distinct event types over the old tables and " +
                        EventLogSchema.EVENT_TYPES_TABLE + ", but the event log has " + Short.MAX_VALUE + " ids"));
            }
            var report = new PreflightReport(reports, planRefusals);
            log.info("Migration pre-flight {}:\n{}", report.passed() ? "passed" : "REFUSED", report.describe());
            return report;
        }
    }

    private TableReport preflight(Connection connection, SourceTable table) throws SQLException {
        var                 refusals = new ArrayList<Refusal>();
        var                 warnings = new ArrayList<String>();
        AggregateTypeTables target   = null;
        try {
            target = AggregateTypeTables.of(table.aggregateType());
        } catch (RuntimeException e) {
            refusals.add(Refusal.of(RefusalKind.AGGREGATE_TYPE_NAME, e.getMessage()));
        }
        var catalog = SourceCatalog.read(connection, table);
        if (!catalog.exists()) {
            refusals.add(Refusal.of(RefusalKind.SOURCE_TABLE_MISSING, "Old table '" + table.tableName() + "' does not exist"));
            return new TableReport(table.aggregateType(), table.tableName(), Map.of(), 0, 0, 0, 0, refusals, warnings);
        }
        var c = table.columns();
        for (var column : c.all()) {
            if (!catalog.columnTypes().containsKey(column)) {
                refusals.add(Refusal.of(RefusalKind.COLUMN_MISSING, "Old table '" + table.tableName() + "' has no column '" + column + "'"));
            }
        }
        if (refusals.stream().anyMatch(r -> r.kind() == RefusalKind.COLUMN_MISSING)) {
            return new TableReport(table.aggregateType(), table.tableName(), catalog.columnTypes(), 0, 0, catalog.sequenceLastValue(), 0, refusals, warnings);
        }
        for (var column : List.of(c.aggregateId(), c.eventId(), c.causedByEventId(), c.correlationId())) {
            if (!SourceCatalog.isIdType(catalog.columnTypes().get(column))) {
                refusals.add(Refusal.of(RefusalKind.UNSUPPORTED_COLUMN_TYPE, "Column '" + column + "' is " + catalog.columnTypes().get(column) + ", not text or uuid"));
            }
        }
        for (var column : List.of(c.eventPayload(), c.eventMetaData())) {
            if (!SourceCatalog.isJsonType(catalog.columnTypes().get(column))) {
                refusals.add(Refusal.of(RefusalKind.UNSUPPORTED_COLUMN_TYPE, "Column '" + column + "' is " + catalog.columnTypes().get(column) + ", not json or jsonb"));
            }
        }
        if (!refusals.isEmpty() && refusals.stream().anyMatch(r -> r.kind() == RefusalKind.UNSUPPORTED_COLUMN_TYPE)) {
            return new TableReport(table.aggregateType(), table.tableName(), catalog.columnTypes(), 0, 0, catalog.sequenceLastValue(), 0, refusals, warnings);
        }
        if (catalog.sequence() == null) {
            refusals.add(Refusal.of(RefusalKind.NO_IDENTITY_SEQUENCE, "Column '" + c.globalOrder() + "' of '" + table.tableName() + "' has no identity sequence"));
        } else if (catalog.sequenceCache() != 1) {
            refusals.add(Refusal.of(RefusalKind.SEQUENCE_CACHE_NOT_ONE, "Sequence " + catalog.sequence() + " has cache " + catalog.sequenceCache() +
                    "; the online copy needs cache 1 (values handed out in order across sessions, §3.3). ALTER SEQUENCE ... CACHE 1 first"));
        }
        if (catalog.isUuid(c.aggregateId())) {
            warnings.add("aggregate_id is uuid: the event log stores its lower-case text form, so the application's AggregateIdSerializer must produce " +
                                 "lower-case canonical text, or loading a stream finds nothing (§2.4)");
        }
        var storage = new MigrationStorage(table, catalog, AggregateTypeTables.of("x"));
        long rows, max, nonUuidCauses;
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(storage.preflightScan())) {
            resultSet.next();
            rows = resultSet.getLong(1);
            max = resultSet.getLong(2);
            nonUuidCauses = resultSet.getLong(3);
            var column = 4;
            for (var kind : storage.refusalPredicates().keySet()) {
                var count = resultSet.getLong(column++);
                var array = resultSet.getArray(column++);
                if (count > 0) {
                    var samples = new ArrayList<Long>();
                    if (array != null) {
                        for (var value : (Object[]) array.getArray()) {
                            samples.add(((Number) value).longValue());
                        }
                    }
                    refusals.add(new Refusal(kind, describe(kind), count, samples));
                }
            }
        }
        if (nonUuidCauses > 0) {
            warnings.add(nonUuidCauses + " caused_by_event_id values are not canonical UUIDs and are copied as null (§2.4, decision 5)");
        }
        if (target != null) {
            targetChecks(connection, table, target, refusals, warnings);
        }
        return new TableReport(table.aggregateType(), table.tableName(), catalog.columnTypes(), rows, max, catalog.sequenceLastValue(), nonUuidCauses, refusals,
                               warnings);
    }

    private static String describe(RefusalKind kind) {
        return switch (kind) {
            case EVENT_ID_NOT_CANONICAL_UUID -> "event_id values that are not lower-case canonical UUIDs (refused, not normalised: EventId equality would change)";
            case CORRELATION_ID_NOT_CANONICAL_UUID -> "correlation_id values that are not lower-case canonical UUIDs";
            case EVENT_REVISION_NOT_SMALLINT -> "event_revision values that do not parse as, or do not fit in, smallint";
            case REQUIRED_VALUE_NULL -> "null aggregate_id, event_order, event_id, event_type, event_revision, timestamp, payload or metadata, all required by the event log";
            default -> kind.name();
        };
    }

    private void targetChecks(Connection connection, SourceTable table, AggregateTypeTables target, List<Refusal> refusals, List<String> warnings) throws SQLException {
        var status       = status(connection, table.aggregateType(), false);
        var targetExists = exists(connection, target.eventTable());
        var targetMax    = targetExists ? queryLong(connection, "SELECT coalesce(max(global_order), 0) FROM " + target.eventTable()) : 0;
        if (status.isPresent()) {
            var s = status.get();
            if (s.state() == MigrationStatus.State.CUT_OVER) {
                refusals.add(Refusal.of(RefusalKind.ALREADY_CUT_OVER, "Aggregate type '" + table.aggregateType() + "' was cut over at " + s.cutOverAt()));
            } else if (!s.sourceTable().equalsIgnoreCase(table.tableName())) {
                refusals.add(Refusal.of(RefusalKind.OTHER_SOURCE_TABLE, "An earlier run copies '" + table.aggregateType() + "' from '" + s.sourceTable() + "', not '" +
                        table.tableName() + "'"));
            } else if (targetMax > s.copiedThrough()) {
                refusals.add(Refusal.of(RefusalKind.TARGET_NOT_EMPTY, target.eventTable() + " holds global_order " + targetMax + ", above this migration's copied_through " +
                        s.copiedThrough() + ": something other than this migration wrote it"));
            } else {
                warnings.add("rerun: an earlier run copied through global_order " + s.copiedThrough() + " in " + s.epochs() + " epochs; the copy resumes there");
            }
        } else if (targetMax > 0 || (targetExists && queryLong(connection, "SELECT count(*) FROM (SELECT 1 FROM " + target.eventTable() + " LIMIT 1) x") > 0)) {
            refusals.add(Refusal.of(RefusalKind.TARGET_NOT_EMPTY, target.eventTable() + " already holds events, and no migration of '" + table.aggregateType() +
                    "' wrote them"));
        }
    }

    private long distinctEventTypes(Connection connection, List<TableReport> reports) throws SQLException {
        var parts = new ArrayList<String>();
        for (var i = 0; i < plan.tables().size(); i++) {
            var table = plan.tables().get(i);
            if (reports.get(i).columnTypes().containsKey(table.columns().eventType())) {
                parts.add("SELECT " + table.columns().eventType() + "::text FROM " + table.tableName());
            }
        }
        if (exists(connection, EventLogSchema.EVENT_TYPES_TABLE)) {
            parts.add("SELECT name FROM " + EventLogSchema.EVENT_TYPES_TABLE);
        }
        if (parts.isEmpty()) {
            return 0;
        }
        return queryLong(connection, "SELECT count(*) FROM (" + String.join(" UNION ", parts) + ") x");
    }

    // ------------------------------------------------------------------ prepare (§5 step 2)

    /**
     * Pre-flight, then per aggregate type: register it with the event log (its tables, through the engine's own
     * schema ownership) and insert its {@code el_migration} row in state {@code COPYING}. Idempotent: a type that already
     * has its row is left as it is.
     *
     * @return the pre-flight report
     * @throws PreflightRefusedException if pre-flight refused; nothing was written
     */
    public PreflightReport prepare() throws SQLException {
        var report = preflight();
        if (!report.passed()) {
            throw new PreflightRefusedException(report);
        }
        try (var connection = connection()) {
            for (var table : plan.tables()) {
                if (status(connection, table.aggregateType(), false).isPresent()) {
                    continue;
                }
                // Register first: the engine's guard refuses a type whose row is COPYING, the tool's own registration included
                plan.eventLog().registerAggregateType(table.aggregateType());
                try (var statement = connection.prepareStatement(INSERT_MIGRATION)) {
                    statement.setString(1, table.aggregateType());
                    statement.setString(2, table.tableName());
                    statement.executeUpdate();
                }
                log.info("Prepared the migration of '{}' from '{}'", table.aggregateType(), table.tableName());
            }
        }
        return report;
    }

    // ------------------------------------------------------------------ online epochs (§3.3)

    /**
     * One online epoch over every aggregate type of the plan: read each old table's {@code M = max(global_order)} and the
     * clock {@code T0}, wait until no other transaction that started at or before {@code T0} is running, then copy
     * {@code (copied_through, M]} in batches. Each batch commits with its checkpoint, so a rerun after a crash resumes.
     *
     * @return the epoch's report; on a wait timeout nothing was copied and the report names the blockers
     * @throws IllegalStateException if a type is not {@code COPYING} (not prepared, or already cut over), a row written
     *                               after pre-flight cannot be copied, or the target was written by someone else
     */
    public EpochReport copyEpoch() throws SQLException {
        try (var connection = connection()) {
            var maxima = new long[plan.tables().size()];
            for (var i = 0; i < maxima.length; i++) {
                var table = plan.tables().get(i);
                requireCopying(connection, table);
                maxima[i] = queryLong(connection, storage(connection, table).maxGlobalOrder());
            }
            OffsetDateTime t0;
            try (var statement = connection.prepareStatement(CLOCK); var resultSet = statement.executeQuery()) {
                resultSet.next();
                t0 = resultSet.getObject(1, OffsetDateTime.class);
            }
            var waitStart = System.nanoTime();
            if (!seams.has(EPOCH_WITHOUT_WAIT)) {
                var deadline = waitStart + plan.settings().epochWaitTimeout().toNanos();
                while (true) {
                    var blockers = olderTransactions(connection, t0);
                    if (blockers.isEmpty()) {
                        break;
                    }
                    if (System.nanoTime() - deadline >= 0) {
                        var waited = Duration.ofNanos(System.nanoTime() - waitStart);
                        log.warn("Migration epoch: transactions older than the epoch's start {} are still running after {}; nothing copied. Blockers: {}", t0, waited,
                                 blockers);
                        return new EpochReport(List.of(), waited, true, blockers, Duration.ZERO);
                    }
                    sleep(plan.settings().epochWaitPoll());
                }
            }
            var waited    = Duration.ofNanos(System.nanoTime() - waitStart);
            var copyStart = System.nanoTime();
            var throttle  = Throttle.of(plan.settings().maxEventsPerSecond());
            throttle.start();
            var types = new ArrayList<EpochReport.TypeEpoch>();
            for (var i = 0; i < maxima.length; i++) {
                var table   = plan.tables().get(i);
                var storage = storage(connection, table);
                var from    = requireCopying(connection, table).copiedThrough();
                long rows   = 0;
                for (long lo = from; lo < maxima[i]; ) {
                    var hi = Math.min(lo + plan.settings().batchSize(), maxima[i]);
                    var copied = onlineBatch(connection, table, storage, lo, hi);
                    rows += copied;
                    throttle.afterBatch(copied);
                    lo = hi;
                }
                execute(connection, COUNT_EPOCH, table.aggregateType());
                types.add(new EpochReport.TypeEpoch(table.aggregateType(), from, Math.max(from, maxima[i]), rows));
            }
            var report = new EpochReport(types, waited, false, List.of(), Duration.ofNanos(System.nanoTime() - copyStart));
            log.info("Migration epoch: waited {}, copied {} rows in {} ({} events/s): {}", waited, report.rows(), report.copyTime(),
                     report.copyTime().isZero() ? 0 : (long) (report.rows() / (report.copyTime().toNanos() / 1e9)), types);
            return report;
        }
    }

    /** §3.7: one batch transaction, its checkpoint included */
    private long onlineBatch(Connection connection, SourceTable table, MigrationStorage storage, long lo, long hi) throws SQLException {
        intern(connection, table, storage, lo, hi);
        connection.setAutoCommit(false);
        try {
            long copied;
            execute(connection, "SET LOCAL synchronous_commit = off");
            if (seams.has(CHECKPOINT_OUTSIDE_BATCH)) {
                checkpoint(connection, table, lo, hi);
                connection.commit();
                seams.at(Hook.BATCH_BEFORE_CHECKPOINT, connection);
                execute(connection, "SET LOCAL synchronous_commit = off");
                copied = copyRows(connection, table, storage, lo, hi);
            } else {
                copied = copyRows(connection, table, storage, lo, hi);
                seams.at(Hook.BATCH_BEFORE_CHECKPOINT, connection);
                checkpoint(connection, table, lo, hi);
            }
            connection.commit();
            return copied;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void checkpoint(Connection connection, SourceTable table, long lo, long hi) throws SQLException {
        try (var statement = connection.prepareStatement(CHECKPOINT)) {
            statement.setLong(1, hi);
            statement.setString(2, table.aggregateType());
            statement.setLong(3, lo);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("The checkpoint of '" + table.aggregateType() + "' moved from " + lo + " while this batch ran: another migration run is " +
                                                        "copying the same type, or the type was cut over");
            }
        }
    }

    /** §3.4: the range's event types not interned yet, each in its own committed transaction */
    private void intern(Connection connection, SourceTable table, MigrationStorage storage, long lo, long hi) throws SQLException {
        try (var statement = connection.prepareStatement(storage.distinctEventTypes())) {
            statement.setLong(1, lo);
            statement.setLong(2, hi);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    var name = resultSet.getString(1);
                    if (name != null && seams.internNames().test(name)) {
                        plan.eventLog().eventTypes().idOf(name);
                    }
                }
            }
        }
    }

    /** Copies {@code (lo, hi]} on the caller's transaction: count, insert-select, row-count check, heads */
    private long copyRows(Connection connection, SourceTable table, MigrationStorage storage, long lo, long hi) throws SQLException {
        long expected, invalid;
        try (var statement = connection.prepareStatement(storage.countRange())) {
            statement.setLong(1, lo);
            statement.setLong(2, hi);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                expected = resultSet.getLong(1);
                invalid = resultSet.getLong(2);
            }
        }
        if (invalid > 0) {
            throw new IllegalStateException(invalid + " rows of '" + table.tableName() + "' in global_order (" + lo + ", " + hi + "] cannot be copied (written after " +
                                                    "pre-flight?); run preflight() for the details");
        }
        int inserted;
        try (var statement = connection.prepareStatement(storage.insertRange(seams.has(PAYLOAD_VIA_JSONB)))) {
            statement.setLong(1, lo);
            statement.setLong(2, hi);
            inserted = statement.executeUpdate();
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new IllegalStateException("Copying global_order (" + lo + ", " + hi + "] of '" + table.tableName() + "' hit a row that is already in the event " +
                                                        "log's table: something other than this migration wrote it. The migration stops instead of hiding the divergence",
                                                e);
            }
            throw e;
        }
        if (!seams.has(TYPE_JOIN_DROPS_ROW) && inserted != expected) {
            throw new IllegalStateException("Copying global_order (" + lo + ", " + hi + "] of '" + table.tableName() + "' inserted " + inserted + " rows, but the range " +
                                                    "holds " + expected + ": an event type without an id dropped rows from the join (§3.4). The batch is rolled back");
        }
        if (!seams.has(HEADS_NOT_WRITTEN) && inserted > 0) {
            try (var statement = connection.prepareStatement(storage.upsertHeads())) {
                statement.setLong(1, lo);
                statement.setLong(2, hi);
                statement.executeUpdate();
            }
        }
        return inserted;
    }

    // ------------------------------------------------------------------ cut-over (§3.5)

    /**
     * The final pass, with every old-engine node stopped: in one transaction, {@code SHARE}-lock the old tables (in name
     * order, under {@code lockTimeout}), copy each type's {@code (copied_through, max]}, write their heads, set each
     * event table's identity sequence to {@code greatest(old sequence last_value, max copied global_order)}, run the
     * verify exactly the range the final pass copied, plus any range below it no online {@link #verify()} found equal,
     * and the heads and identity sequences (§6.2, §16.7), run the plan's {@link CutoverStep}s, and mark every type
     * {@code CUT_OVER}. Any failure rolls all of it back to the state after the last epoch.
     *
     * @throws CutoverLockTimeoutException if writers still hold the old tables; nothing was copied
     * @throws VerificationFailedException if the verification found a difference; its report names the windows and rows
     * @throws IllegalStateException       if a type is not {@code COPYING}
     */
    public CutoverReport cutover() throws SQLException {
        try (var connection = connection()) {
            var start = System.nanoTime();
            connection.setAutoCommit(false);
            try {
                execute(connection, "SET LOCAL lock_timeout = '" + plan.settings().lockTimeout().toMillis() + "ms'");
                if (!seams.has(CUTOVER_WITHOUT_LOCK)) {
                    var tables = plan.tables().stream().map(SourceTable::tableName).sorted().toList();
                    try {
                        execute(connection, "LOCK TABLE " + String.join(", ", tables) + " IN SHARE MODE");
                    } catch (SQLException e) {
                        if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                            throw new CutoverLockTimeoutException("Writers still running: the SHARE locks on " + tables + " were not granted within " +
                                                                          plan.settings().lockTimeout() + ". Stop every old-engine node and run the cut-over again; " +
                                                                          "nothing was copied", e);
                        }
                        throw e;
                    }
                }
                var lockWait = Duration.ofNanos(System.nanoTime() - start);
                seams.at(Hook.CUTOVER_LOCKED, connection);
                var statuses = new ArrayList<MigrationStatus>();
                for (var table : plan.tables()) {
                    var status = status(connection, table.aggregateType(), true)
                            .orElseThrow(() -> new IllegalStateException("Aggregate type '" + table.aggregateType() + "' was never prepared"));
                    if (status.state() != MigrationStatus.State.COPYING) {
                        throw new IllegalStateException("Aggregate type '" + table.aggregateType() + "' is already " + status.state());
                    }
                    statuses.add(status);
                }
                var copied = new ArrayList<long[]>();
                for (var i = 0; i < plan.tables().size(); i++) {
                    var table   = plan.tables().get(i);
                    var storage = storage(connection, table);
                    var from    = statuses.get(i).copiedThrough();
                    var to      = Math.max(from, queryLong(connection, storage.maxGlobalOrder()));
                    long rows   = 0;
                    for (long lo = from; lo < to; ) {
                        var hi = Math.min(lo + plan.settings().batchSize(), to);
                        intern(connection, table, storage, lo, hi);
                        rows += copyRows(connection, table, storage, lo, hi);
                        lo = hi;
                    }
                    copied.add(new long[]{from, to, rows});
                }
                var types = new ArrayList<CutoverReport.TypeCutover>();
                for (var i = 0; i < plan.tables().size(); i++) {
                    var table  = plan.tables().get(i);
                    var target = AggregateTypeTables.of(table.aggregateType());
                    var catalog = SourceCatalog.read(connection, table);
                    var newMax = queryLong(connection, "SELECT coalesce(max(global_order), 0) FROM " + target.eventTable());
                    var value  = seams.has(SEQUENCE_AT_MAX_ROW) ? newMax : Math.max(catalog.sequenceLastValue(), newMax);
                    if (value > 0) {
                        try (var statement = connection.prepareStatement(SET_SEQUENCE)) {
                            statement.setString(1, target.eventTable());
                            statement.setLong(2, value);
                            statement.executeQuery().close();
                        }
                    }
                    types.add(new CutoverReport.TypeCutover(table.aggregateType(), copied.get(i)[0], copied.get(i)[1], copied.get(i)[2], value));
                }
                if (!seams.has(CUTOVER_WITHOUT_VERIFY)) {
                    new Verifier(plan, seams).cutoverStep().run(connection, List.copyOf(types));
                }
                for (var step : plan.cutoverSteps()) {
                    step.run(connection, List.copyOf(types));
                }
                for (var type : types) {
                    try (var statement = connection.prepareStatement(CUT_OVER)) {
                        statement.setLong(1, type.to());
                        statement.setString(2, type.aggregateType());
                        if (statement.executeUpdate() != 1) {
                            throw new IllegalStateException("Aggregate type '" + type.aggregateType() + "' left COPYING during the cut-over");
                        }
                    }
                }
                seams.at(Hook.CUTOVER_BEFORE_COMMIT, connection);
                connection.commit();
                var report = new CutoverReport(types, lockWait, Duration.ofNanos(System.nanoTime() - start));
                log.info("Migration cut over in {} (lock wait {}): {}. Old-engine nodes must stay stopped; to make sure, an operator may run: {}", report.stop(),
                         lockWait, types, revokeStatements());
                return report;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * @return the {@code REVOKE} statements an operator may run after the cut-over to keep a forgotten old node from
     * appending (§3.5, decision 6). The migration never runs them
     */
    public List<String> revokeStatements() {
        return plan.tables().stream().map(t -> "REVOKE INSERT ON " + t.tableName() + " FROM <old application role>").toList();
    }

    // ------------------------------------------------------------------ verification (§6)

    /**
     * Online verification (§6.2), while the old engine runs or at any time after: per aggregate type, the copied range
     * not verified yet, {@code (verified maximum, copied_through]}, hashed per window over every column on both sides;
     * a differing window is bisected down to its rows. Then the heads of the range's streams. The outcome and the
     * windows' hashes are recorded in {@code el_migration.report}; the cut-over later verifies only what it copies,
     * plus any range no online run verified equal.
     *
     * @return the report; {@link VerificationReport#equal()} says whether the copy is equal
     * @throws IllegalStateException if a type was never prepared
     */
    public VerificationReport verify() throws SQLException {
        try (var connection = connection()) {
            return new Verifier(plan, seams).online(connection, false);
        }
    }

    /**
     * As {@link #verify()}, but over every copied row, {@code (0, copied_through]}, whatever was verified before, and
     * with a check that no head lacks a stream. Replaces the recorded windows when equal; a difference lowers the
     * verified maximum, so that the cut-over verifies the range again.
     */
    public VerificationReport verifyAll() throws SQLException {
        try (var connection = connection()) {
            return new Verifier(plan, seams).online(connection, true);
        }
    }

    /**
     * {@code verify --after-cutover} (§6.3, decision 6): per cut-over aggregate type, the old table's rows above the
     * copied maximum recorded at the cut-over. Such a row was appended by an old-engine node that should have been
     * stopped; it exists only in the old table and is reported, never merged. Read-only.
     *
     * @throws IllegalStateException if a type is not {@code CUT_OVER}
     */
    public LateRowsReport verifyAfterCutover() throws SQLException {
        try (var connection = connection()) {
            var types = new ArrayList<LateRowsReport.TypeLateRows>();
            for (var table : plan.tables()) {
                var status = status(connection, table.aggregateType(), false)
                        .filter(s -> s.state() == MigrationStatus.State.CUT_OVER)
                        .orElseThrow(() -> new IllegalStateException("Aggregate type '" + table.aggregateType() + "' is not cut over"));
                var  storage = storage(connection, table);
                long count, max;
                try (var statement = connection.prepareStatement(storage.lateRows())) {
                    statement.setLong(1, status.copiedThrough());
                    try (var resultSet = statement.executeQuery()) {
                        resultSet.next();
                        count = resultSet.getLong(1);
                        max = resultSet.getLong(2);
                    }
                }
                var samples = new ArrayList<LateRowsReport.LateRow>();
                if (count > 0) {
                    try (var statement = connection.prepareStatement(storage.lateRowSamples())) {
                        statement.setLong(1, status.copiedThrough());
                        try (var resultSet = statement.executeQuery()) {
                            while (resultSet.next()) {
                                samples.add(new LateRowsReport.LateRow(resultSet.getLong(1), resultSet.getObject(2, OffsetDateTime.class)));
                            }
                        }
                    }
                    log.error("LATE ROWS: {} rows of '{}' above the copied maximum {} (up to global_order {}), written after the cut-over by an old-engine node " +
                                      "that should have been stopped. They exist only in the old table and are not merged. First: {}", count, table.tableName(),
                              status.copiedThrough(), max, samples);
                }
                types.add(new LateRowsReport.TypeLateRows(table.aggregateType(), table.tableName(), status.copiedThrough(), count, max, samples));
            }
            return new LateRowsReport(types);
        }
    }

    // ------------------------------------------------------------------ status

    /**
     * @return the aggregate type's {@code el_migration} row, if it has one
     */
    public Optional<MigrationStatus> status(String aggregateType) throws SQLException {
        try (var connection = connection()) {
            return status(connection, aggregateType, false);
        }
    }

    static Optional<MigrationStatus> status(Connection connection, String aggregateType, boolean forUpdate) throws SQLException {
        if (!forUpdate && !exists(connection, EventLogSchema.MIGRATION_TABLE)) {
            return Optional.empty();
        }
        try (var statement = connection.prepareStatement(forUpdate ? MIGRATION_STATUS_FOR_UPDATE : MIGRATION_STATUS)) {
            statement.setString(1, aggregateType);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new MigrationStatus(resultSet.getString(1), MigrationStatus.State.valueOf(resultSet.getString(2)), resultSet.getString(3),
                                                       resultSet.getLong(4), resultSet.getInt(5), resultSet.getObject(6, OffsetDateTime.class),
                                                       resultSet.getObject(7, OffsetDateTime.class)));
            }
        }
    }

    private static MigrationStatus requireCopying(Connection connection, SourceTable table) throws SQLException {
        var status = status(connection, table.aggregateType(), false)
                .orElseThrow(() -> new IllegalStateException("Aggregate type '" + table.aggregateType() + "' was never prepared: run prepare() first"));
        if (status.state() != MigrationStatus.State.COPYING) {
            throw new IllegalStateException("Aggregate type '" + table.aggregateType() + "' is " + status.state() + "; the copy is over");
        }
        if (!status.sourceTable().equalsIgnoreCase(table.tableName())) {
            throw new IllegalStateException("Aggregate type '" + table.aggregateType() + "' is being copied from '" + status.sourceTable() + "', not '" + table.tableName() + "'");
        }
        return status;
    }

    // ------------------------------------------------------------------ helpers

    private Connection connection() throws SQLException {
        var connection = plan.dataSource().getConnection();
        try {
            connection.setAutoCommit(true);
            // Each statement must see the event types interned on the registry's own connection (§3.4)
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            return connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    static MigrationStorage storage(Connection connection, SourceTable table) throws SQLException {
        var catalog = SourceCatalog.read(connection, table);
        if (!catalog.exists()) {
            throw new IllegalStateException("Old table '" + table.tableName() + "' does not exist");
        }
        return new MigrationStorage(table, catalog, AggregateTypeTables.of(table.aggregateType()));
    }

    private static List<EpochReport.Blocker> olderTransactions(Connection connection, OffsetDateTime t0) throws SQLException {
        var blockers = new ArrayList<EpochReport.Blocker>();
        try (var statement = connection.prepareStatement(OLDER_TRANSACTIONS)) {
            statement.setObject(1, t0);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    blockers.add(new EpochReport.Blocker(resultSet.getInt(1), resultSet.getString(2), resultSet.getObject(3, OffsetDateTime.class), resultSet.getString(4)));
                }
            }
        }
        return blockers;
    }

    private static boolean exists(Connection connection, String relation) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, relation);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql, String... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            statement.execute();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for older transactions", e);
        }
    }
}
