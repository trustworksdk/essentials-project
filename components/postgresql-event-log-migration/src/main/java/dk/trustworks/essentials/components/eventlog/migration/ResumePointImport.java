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
import dk.trustworks.essentials.components.eventlog.migration.ImportReport.*;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import org.slf4j.*;

import java.sql.*;
import java.util.*;

import static dk.trustworks.essentials.components.eventlog.migration.MigrationMutant.*;
import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The import of the old engine's resume points, as a {@link CutoverStep}: it runs in the cut-over
 * transaction, under the {@code SHARE} locks, after the final pass is copied and verified. Add it to
 * {@link MigrationPlan#cutoverSteps()}.
 * <p>
 * For every {@link ImportedSubscription} it reads the old resume point {@code R} from {@code durable_subscriptions} and
 * imports
 * <pre>
 * I = least(R, min{ g ∈ transient_subscriber_gaps(subscriber, type) : an event with global_order g exists })
 * </pre>
 * through {@link EventLogAdmin#importProgress}, once per instance name: an open transient gap whose event committed late
 * may never have been delivered, and the new engine keeps no per-subscription pending set, so the subscription replays
 * {@code [I, R)} (at least once, as on the old engine). An open gap that never filled does not force a replay.
 * <p>
 * A mapping with {@link ImportedSubscription#trustOldResumePoint()} imports {@code R} unchanged instead: the
 * operator states the old subscriber already handled its gap fills, which trades invariant I8 for no replay. The report
 * records the opt-in and the filled gaps it skips, and the run logs them at WARN.
 * The gaps' rows are looked up in the event log's table, which the cut-over has just verified equal to the old one.
 * <p>
 * It takes {@code SHARE} locks on the old subscription and gap tables too, so an old node still running a subscriber
 * cannot move a resume point under it. Then it checks every progress row it wrote: {@code resume_from = I},
 * {@code I <= R}, and {@code I <=} every open transient gap of the subscriber that has a row; for an opted-in mapping,
 * {@code resume_from = R}. A progress row that already
 * exists with another resume point is refused (an equal one is an idempotent rerun). Any refusal rolls the whole
 * cut-over back.
 * <p>
 * Not recovered, reported instead ({@link ImportReport}): late fills of permanent gaps below {@code R},
 * a source without gap tracking, the old subscribers no mapping names. {@link #report()} holds the last run's report;
 * it describes what was committed only if {@link Migration#cutover()} returned.
 */
public final class ResumePointImport implements CutoverStep {
    private static final Logger log = LoggerFactory.getLogger(ResumePointImport.class);

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    public static final String DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE = "durable_subscriptions";
    public static final String DEFAULT_TRANSIENT_GAPS_TABLE        = "transient_subscriber_gaps";
    public static final String DEFAULT_PERMANENT_GAPS_TABLE        = "permanent_gaps";

    private final EventLogAdmin              admin;
    private final List<ImportedSubscription> subscriptions;
    private final String                     durableSubscriptionsTable;
    private final String                     transientGapsTable;
    private final String                     permanentGapsTable;
    private       MigrationSeams             seams = MigrationSeams.NONE;
    private volatile ImportReport            report;

    private ResumePointImport(Builder builder) {
        this.admin = requireNonNull(builder.admin, "No admin provided");
        this.subscriptions = List.copyOf(builder.subscriptions);
        this.durableSubscriptionsTable = builder.durableSubscriptionsTable;
        this.transientGapsTable = builder.transientGapsTable;
        this.permanentGapsTable = builder.permanentGapsTable;
        var keys = new HashSet<String>();
        for (var s : subscriptions) {
            requireTrue(keys.add(s.definition().subscriber() + "\u0000" + s.aggregateType()),
                        "Subscriber '" + s.definition().subscriber() + "' of aggregate type '" + s.aggregateType() + "' is imported twice");
        }
    }

    /**
     * @param admin the event log's admin ({@link EventLog#admin()}), whose {@link EventLogAdmin#importProgress} writes the rows
     */
    public static Builder builder(EventLogAdmin admin) {
        return new Builder(admin);
    }

    /** Test seam: mutants. {@code CUTOVER_WITHOUT_VERIFY} also skips this step's checks */
    void seams(MigrationSeams seams) {
        this.seams = requireNonNull(seams, "No seams provided");
    }

    /**
     * @return the report of the last run; empty before the first. Describes what was committed only if the cut-over it
     * ran in committed
     */
    public Optional<ImportReport> report() {
        return Optional.ofNullable(report);
    }

    @Override
    public void run(Connection connection, List<CutoverReport.TypeCutover> copied) throws SQLException {
        report = importAll(connection, copied.stream().map(CutoverReport.TypeCutover::aggregateType).toList(), false);
    }

    /**
     * The import preview: per mapping, the old resume point {@code R}, the value the cut-over would
     * import {@code I}, the I8-safe value and the rows it replays (flagged when large, {@link ImportReport#LARGE_REPLAY_ROWS}),
     * the opt-in and the filled gaps it skips, and the replay length in values and rows, plus the old subscribers that are not mapped. Computed
     * by the same code as {@link #run}, in a {@code READ ONLY} transaction: it takes no {@code SHARE} lock, writes
     * nothing, and runs while the old engine does.
     * <p>
     * It sees both engines as they are now: the event log as the last copy epoch left it, so a gap filled by an event not
     * copied yet is not counted, and the old subscribers' resume points as last saved. The cut-over, which computes the
     * same under its locks after the final pass, may therefore import a lower {@code I} and replay more. The progress-row
     * checks are not run (they read the rows the import writes). Does not change {@link #report()}.
     *
     * @param connection     a connection to the database of both engines; its auto-commit and read-only flag are restored
     * @param aggregateTypes the aggregate types the migration plan copies
     * @throws IllegalStateException if a mapping is of an aggregate type the plan does not copy, or its old subscriber has
     *                               no resume point
     */
    public ImportReport preview(Connection connection, List<String> aggregateTypes) throws SQLException {
        requireNonNull(connection, "No connection provided");
        requireNonNull(aggregateTypes, "No aggregateTypes provided");
        var autoCommit = connection.getAutoCommit();
        var readOnly   = connection.isReadOnly();
        connection.setAutoCommit(false);
        connection.setReadOnly(true);
        try {
            return importAll(connection, List.copyOf(aggregateTypes), true);
        } finally {
            connection.rollback();
            connection.setReadOnly(readOnly);
            connection.setAutoCommit(autoCommit);
        }
    }

    private ImportReport importAll(Connection connection, List<String> types, boolean preview) throws SQLException {
        for (var s : subscriptions) {
            if (!types.contains(s.aggregateType())) {
                throw new IllegalStateException("Imported subscription '" + s.definition().subscriber() + "' is of aggregate type '" + s.aggregateType() +
                                                        "', which the migration plan does not copy " + types);
            }
        }
        var durableExists   = exists(connection, durableSubscriptionsTable);
        var gapTracking     = exists(connection, transientGapsTable);
        var permanentExists = exists(connection, permanentGapsTable);
        if (!preview) {
            lock(connection, durableExists, gapTracking, permanentExists);
        }

        var imported = new ArrayList<SubscriptionImport>();
        for (var s : subscriptions) {
            var computed = compute(connection, s, durableExists, gapTracking, permanentExists);
            if (!preview) {
                write(connection, s, computed);
            }
            imported.add(computed.report());
        }
        var unmapped = durableExists ? unmapped(connection, types) : List.<UnmappedSubscriber>of();
        var result   = new ImportReport(imported, unmapped, gapTracking);
        if (preview) {
            return result;
        }
        if (!gapTracking && !subscriptions.isEmpty()) {
            log.warn("The old engine's '{}' table does not exist: it tracked no gaps, so late commits it skipped cannot be identified; every resume point " +
                             "is imported unchanged", transientGapsTable);
        }
        for (var i : imported) {
            if (i.trustedOldResumePoint()) {
                log.warn("Old subscriber '{}' of '{}' is imported at its old resume point {} on the operator's statement that it handled its gap fills " +
                                 "(trustOldResumePoint): the I8-safe value {} would have replayed {} events; {} filled open transient gaps are skipped and " +
                                 "not delivered again{}: {}",
                         i.oldSubscriberId(), i.aggregateType(), i.oldResumeFrom(), i.safeResumeFrom(), i.safeReplayRows(), i.skippedFilledGapCount(),
                         i.skippedFilledGapCount() > i.skippedFilledGaps().size() ? " (lowest " + i.skippedFilledGaps().size() + " listed)" : "",
                         i.skippedFilledGaps());
            }
            log.info("Imported the resume point of old subscriber '{}' of '{}' as '{}' {}: old {}, imported {}; replays {} values, {} events; " +
                             "{} open transient gaps, {} filled; {} late-filled permanent gaps below the old resume point not delivered",
                     i.oldSubscriberId(), i.aggregateType(), i.subscriber(), i.instanceNames(), i.oldResumeFrom(), i.resumeFrom(), i.replayValues(), i.replayRows(),
                     i.openTransientGaps(), i.filledOpenTransientGaps(), i.lateFilledPermanentGapsBelowResume());
        }
        for (var u : unmapped) {
            log.warn("Old subscriber '{}' of '{}' (resume point {}) is not mapped and not imported; a new subscription of it starts where its StartFrom says",
                     u.subscriberId(), u.aggregateType(), u.resumeFrom());
        }
        return result;
    }

    /** One mapping's import, computed read-only; {@code lowestFilled} is the lowest open transient gap that has a row, or null */
    private record Computed(SubscriptionImport report, Long lowestFilled) {
    }

    private Computed compute(Connection connection, ImportedSubscription s, boolean durableExists, boolean gapTracking, boolean permanentExists) throws SQLException {
        var  type       = s.aggregateType();
        var  eventTable = AggregateTypeTables.of(type).eventTable();
        long oldResume  = oldResumePoint(connection, s, durableExists);

        long openGaps = 0, filledGaps = 0;
        Long lowestFilled = null, lowestOpen = null;
        if (gapTracking) {
            try (var statement = connection.prepareStatement(
                    "SELECT count(*), count(*) FILTER (WHERE e.filled), min(g.gap_global_event_order) FILTER (WHERE e.filled), min(g.gap_global_event_order) FROM " +
                            transientGapsTable + " g CROSS JOIN LATERAL (SELECT EXISTS (SELECT 1 FROM " + eventTable +
                            " x WHERE x.global_order = g.gap_global_event_order) AS filled) e WHERE g.subscriber_id = ? AND g.aggregate_type = ?")) {
                statement.setString(1, s.oldSubscriberId());
                statement.setString(2, type);
                try (var resultSet = statement.executeQuery()) {
                    resultSet.next();
                    openGaps = resultSet.getLong(1);
                    filledGaps = resultSet.getLong(2);
                    lowestFilled = (Long) resultSet.getObject(3);
                    lowestOpen = (Long) resultSet.getObject(4);
                }
            }
        }

        long safeResumeFrom = lowestFilled == null ? oldResume : Math.min(oldResume, lowestFilled);
        long resumeFrom;
        if (s.trustOldResumePoint()) {
            resumeFrom = oldResume;
        } else if (seams.has(IMPORT_RESUME_UNCHANGED)) {
            resumeFrom = oldResume;
        } else if (seams.has(IMPORT_LOWEST_GAP_UNFILTERED)) {
            resumeFrom = lowestOpen == null ? oldResume : Math.min(oldResume, lowestOpen);
        } else {
            resumeFrom = safeResumeFrom;
        }

        var  replaySql      = "SELECT count(*) FROM " + eventTable + " WHERE global_order >= ? AND global_order < ?";
        long replayRows     = queryLong(connection, replaySql, resumeFrom, oldResume);
        long safeReplayRows = safeResumeFrom == resumeFrom ? replayRows : queryLong(connection, replaySql, safeResumeFrom, oldResume);

        var  skipped      = new ArrayList<Long>();
        long skippedCount = 0;
        if (s.trustOldResumePoint() && gapTracking && lowestFilled != null) {
            try (var statement = connection.prepareStatement(
                    "SELECT g.gap_global_event_order, count(*) OVER () FROM " + transientGapsTable + " g WHERE g.subscriber_id = ? AND g.aggregate_type = ? " +
                            "AND g.gap_global_event_order < ? AND EXISTS (SELECT 1 FROM " + eventTable + " x WHERE x.global_order = g.gap_global_event_order) " +
                            "ORDER BY g.gap_global_event_order LIMIT " + ImportReport.MAX_LISTED_GAPS)) {
                statement.setString(1, s.oldSubscriberId());
                statement.setString(2, type);
                statement.setLong(3, oldResume);
                try (var resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        skipped.add(resultSet.getLong(1));
                        skippedCount = resultSet.getLong(2);
                    }
                }
            }
        }
        long lateFills  = 0;
        if (permanentExists) {
            try (var statement = connection.prepareStatement("SELECT count(*) FROM " + permanentGapsTable + " p WHERE p.aggregate_type = ? AND p.gap_global_event_order < ? " +
                                                                     "AND EXISTS (SELECT 1 FROM " + eventTable + " x WHERE x.global_order = p.gap_global_event_order)")) {
                statement.setString(1, type);
                statement.setLong(2, oldResume);
                try (var resultSet = statement.executeQuery()) {
                    resultSet.next();
                    lateFills = resultSet.getLong(1);
                }
            }
        }
        return new Computed(new SubscriptionImport(s.oldSubscriberId(), type, s.definition().subscriber(), s.instanceNames(), oldResume, resumeFrom,
                                                   oldResume - resumeFrom, replayRows, openGaps, filledGaps, lateFills, safeResumeFrom, safeReplayRows,
                                                   s.trustOldResumePoint(), skipped, skippedCount), lowestFilled);
    }

    /** Writes the computed resume point as each instance's progress row, then checks the rows */
    private void write(Connection connection, ImportedSubscription s, Computed computed) throws SQLException {
        var resumeFrom = computed.report().resumeFrom();
        for (var instance : s.instanceNames()) {
            var stored = admin.importProgress(s.definition(), s.model(), instance, resumeFrom, connection);
            if (stored != resumeFrom) {
                throw new IllegalStateException("The progress row of '" + s.definition().subscriber() + "' of '" + s.aggregateType() + "' (instance '" + instance +
                                                        "') already exists with resume point " + stored + ", not the imported " + resumeFrom +
                                                        ": refusing to import over it. Delete the row (EventLogAdmin.deleteRetiredInstance) or drop the mapping");
            }
        }
        if (!seams.has(MigrationMutant.CUTOVER_WITHOUT_VERIFY)) {
            check(connection, s, computed.report().oldResumeFrom(), computed.lowestFilled());
            if (s.trustOldResumePoint()) {
                checkTrusted(connection, s, computed.report().oldResumeFrom());
            }
        }
    }

    /**
     * The progress-row structural checks, read back from {@code el_progress} and against bounds computed apart from
     * the imported value: {@code 1 <= resume_from <= R}, and {@code resume_from <=} the lowest open transient gap that
     * has a row, unless the mapping opted in to {@code R} ({@link #checkTrusted})
     */
    private void check(Connection connection, ImportedSubscription s, long oldResume, Long lowestFilled) throws SQLException {
        for (var instance : s.instanceNames()) {
            var stored = storedResumeFrom(connection, s, instance);
            var key    = key(s, instance);
            if (stored == null) {
                throw new IllegalStateException("Import check failed: no progress row of " + key);
            }
            if (stored < 1 || stored > oldResume) {
                throw new IllegalStateException("Import check failed: the progress row of " + key + " resumes from " + stored + ", above the old resume point " + oldResume);
            }
            if (lowestFilled != null && stored > lowestFilled && !s.trustOldResumePoint()) {
                throw new IllegalStateException("Import check failed: the progress row of " + key + " resumes from " + stored +
                                                        ", above open transient gap " + lowestFilled + " of old subscriber '" + s.oldSubscriberId() +
                                                        "', whose event committed late and may never have been delivered");
            }
        }
    }

    /** The opt-in's check: every progress row resumes from the old resume point {@code R} exactly */
    private void checkTrusted(Connection connection, ImportedSubscription s, long oldResume) throws SQLException {
        for (var instance : s.instanceNames()) {
            var stored = storedResumeFrom(connection, s, instance);
            if (stored == null || stored != oldResume) {
                throw new IllegalStateException("Import check failed: the progress row of " + key(s, instance) + " resumes from " + stored +
                                                        ", not the old resume point " + oldResume + " its mapping opted in to (trustOldResumePoint)");
            }
        }
    }

    private static Long storedResumeFrom(Connection connection, ImportedSubscription s, String instance) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT resume_from FROM " + EventLogSchema.PROGRESS_TABLE +
                                                                 " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ?")) {
            statement.setString(1, s.definition().subscriber());
            statement.setString(2, s.aggregateType());
            statement.setString(3, instance);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    private static String key(ImportedSubscription s, String instance) {
        return "'" + s.definition().subscriber() + "' of '" + s.aggregateType() + "' (instance '" + instance + "')";
    }

    private long oldResumePoint(Connection connection, ImportedSubscription s, boolean durableExists) throws SQLException {
        if (durableExists) {
            try (var statement = connection.prepareStatement("SELECT resume_from_and_including_global_eventorder FROM " + durableSubscriptionsTable +
                                                                     " WHERE subscriber_id = ? AND aggregate_type = ?")) {
                statement.setString(1, s.oldSubscriberId());
                statement.setString(2, s.aggregateType());
                try (var resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        var value = (Long) resultSet.getObject(1);
                        if (value != null && value >= 1) {
                            return value;
                        }
                        throw new IllegalStateException("Old subscriber '" + s.oldSubscriberId() + "' of '" + s.aggregateType() + "' has no resume point (" + value + ")");
                    }
                }
            }
        }
        throw new IllegalStateException("Old subscriber '" + s.oldSubscriberId() + "' of '" + s.aggregateType() + "' has no row in '" + durableSubscriptionsTable +
                                                "': nothing to import. Check the mapping, or drop it and let the new subscription start where its StartFrom says");
    }

    private List<UnmappedSubscriber> unmapped(Connection connection, List<String> types) throws SQLException {
        var mapped = new HashSet<String>();
        subscriptions.forEach(s -> mapped.add(s.oldSubscriberId() + "\u0000" + s.aggregateType()));
        var result = new ArrayList<UnmappedSubscriber>();
        try (var statement = connection.prepareStatement("SELECT subscriber_id, aggregate_type, resume_from_and_including_global_eventorder FROM " + durableSubscriptionsTable +
                                                                 " WHERE aggregate_type = ANY (?) ORDER BY aggregate_type, subscriber_id")) {
            statement.setArray(1, connection.createArrayOf("text", types.toArray()));
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if (!mapped.contains(resultSet.getString(1) + "\u0000" + resultSet.getString(2))) {
                        result.add(new UnmappedSubscriber(resultSet.getString(1), resultSet.getString(2), (Long) resultSet.getObject(3)));
                    }
                }
            }
        }
        return result;
    }

    private void lock(Connection connection, boolean durableExists, boolean gapTracking, boolean permanentExists) throws SQLException {
        var tables = new ArrayList<String>();
        if (durableExists) {
            tables.add(durableSubscriptionsTable);
        }
        if (gapTracking) {
            tables.add(transientGapsTable);
        }
        if (permanentExists) {
            tables.add(permanentGapsTable);
        }
        if (tables.isEmpty()) {
            return;
        }
        Collections.sort(tables);
        try (var statement = connection.createStatement()) {
            statement.execute("LOCK TABLE " + String.join(", ", tables) + " IN SHARE MODE");
        } catch (SQLException e) {
            if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                throw new CutoverLockTimeoutException("Old subscribers still running: the SHARE locks on " + tables + " were not granted within the lock timeout. " +
                                                              "Stop every old-engine node and run the cut-over again; nothing was copied", e);
            }
            throw e;
        }
    }

    private static boolean exists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, table);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private static long queryLong(Connection connection, String sql, long... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var i = 0; i < parameters.length; i++) {
                statement.setLong(i + 1, parameters[i]);
            }
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    public static final class Builder {
        private final EventLogAdmin              admin;
        private final List<ImportedSubscription> subscriptions             = new ArrayList<>();
        private       String                     durableSubscriptionsTable = DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE;
        private       String                     transientGapsTable        = DEFAULT_TRANSIENT_GAPS_TABLE;
        private       String                     permanentGapsTable        = DEFAULT_PERMANENT_GAPS_TABLE;

        private Builder(EventLogAdmin admin) {
            this.admin = requireNonNull(admin, "No admin provided");
        }

        /** Adds one mapping; an old subscriber with none is reported, not imported */
        public Builder addSubscription(ImportedSubscription subscription) {
            subscriptions.add(requireNonNull(subscription, "No subscription provided"));
            return this;
        }

        public Builder setSubscriptions(List<ImportedSubscription> subscriptions) {
            this.subscriptions.clear();
            requireNonNull(subscriptions, "No subscriptions provided").forEach(this::addSubscription);
            return this;
        }

        /** The old engine's durable subscriptions table, if not {@value #DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE} */
        public Builder setDurableSubscriptionsTable(String table) {
            this.durableSubscriptionsTable = validated(table);
            return this;
        }

        /** The old gap handler's transient gaps table, if not {@value #DEFAULT_TRANSIENT_GAPS_TABLE} */
        public Builder setTransientGapsTable(String table) {
            this.transientGapsTable = validated(table);
            return this;
        }

        /** The old gap handler's permanent gaps table, if not {@value #DEFAULT_PERMANENT_GAPS_TABLE} */
        public Builder setPermanentGapsTable(String table) {
            this.permanentGapsTable = validated(table);
            return this;
        }

        public ResumePointImport build() {
            return new ResumePointImport(this);
        }

        private static String validated(String table) {
            requireNonBlank(table, "No table provided");
            PostgresqlUtil.checkIsValidTableOrColumnName(table, "old subscription table");
            return table;
        }
    }
}
