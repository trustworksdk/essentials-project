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
import dk.trustworks.essentials.components.eventlog.migration.VerificationReport.*;
import org.slf4j.*;

import java.sql.*;
import java.time.*;
import java.util.*;

import static dk.trustworks.essentials.components.eventlog.migration.MigrationMutant.VERIFY_WITHOUT_PAYLOAD;

/**
 * The migration's verification (design phase 9 §6): per window of {@code global_order}, the row count and the hash of
 * every column on both sides (§6.1); a differing window bisected down to the rows that differ; the structural checks of
 * heads and identity sequences. Online it covers the copied ranges not verified yet (§6.2); under the cut-over exactly
 * the range the final pass copied (§16.7), plus any range below it no online run verified. The verified maximum is kept
 * in {@code el_migration.report} with the windows' hashes.
 */
final class Verifier {
    private static final Logger log = LoggerFactory.getLogger(Verifier.class);

    /** A differing range this narrow is compared row by row */
    static final int BISECT_LEAF        = 64;
    /** The rows a report names per type; bisection stops there */
    static final int MAX_DIFFERING_ROWS = 100;

    static final String VERIFIED_THROUGH = "SELECT coalesce((report::jsonb ->> 'verifiedThrough')::bigint, 0) FROM " + EventLogSchema.MIGRATION_TABLE +
            " WHERE aggregate_type = ?";
    /** Records a verification: the verified maximum, the last run's summary, and the windows' hashes (appended or replaced) */
    static final String RECORD = "UPDATE " + EventLogSchema.MIGRATION_TABLE + " SET report = (coalesce(report::jsonb, '{}'::jsonb) || jsonb_build_object(" +
            "'verifiedThrough', ?::bigint, 'lastVerification', ?::jsonb || jsonb_build_object('at', now()), " +
            "'windows', CASE WHEN ? THEN ?::jsonb ELSE coalesce(report::jsonb -> 'windows', '[]'::jsonb) || ?::jsonb END))::json WHERE aggregate_type = ?";

    private final MigrationPlan  plan;
    private final MigrationSeams seams;

    Verifier(MigrationPlan plan, MigrationSeams seams) {
        this.plan = plan;
        this.seams = seams;
    }

    /**
     * Online, or at any time after the cut-over: per type, {@code (from, copied_through]}, where {@code from} is the
     * verified maximum (or 0 when {@code fromStart}). Records the outcome; a difference lowers the verified maximum to
     * the range's start, so that the cut-over verifies it again.
     */
    VerificationReport online(Connection connection, boolean fromStart) throws SQLException {
        var start = System.nanoTime();
        var types = new ArrayList<TypeVerification>();
        for (var table : plan.tables()) {
            var status = Migration.status(connection, table.aggregateType(), false)
                                  .orElseThrow(() -> new IllegalStateException("Aggregate type '" + table.aggregateType() + "' was never prepared: run prepare() first"));
            var storage  = Migration.storage(connection, table);
            var verified = verifiedThrough(connection, table.aggregateType());
            var to       = status.copiedThrough();
            var from     = fromStart ? 0 : Math.min(verified, to);
            var type     = verify(connection, table, storage, from, to, false);
            if (to > from || !type.equal()) {
                record(connection, type, VerificationReport.Mode.ONLINE, type.equal() ? Math.max(verified, to) : Math.min(verified, from), fromStart);
            }
            types.add(type);
        }
        var report = new VerificationReport(VerificationReport.Mode.ONLINE, types, Duration.ofNanos(System.nanoTime() - start));
        if (report.equal()) {
            log.info("Migration verification (online) equal in {}:\n{}", report.took(), report.describe());
        } else {
            log.error("Migration verification (online) found differences:\n{}", report.describe());
        }
        return report;
    }

    /**
     * The cut-over's step (§3.5 step 3): per type, {@code (min(verified maximum, from), to]} and the identity sequence.
     * Throws {@link VerificationFailedException} on any difference, which rolls the cut-over back.
     */
    CutoverStep cutoverStep() {
        return (connection, copied) -> {
            var start = System.nanoTime();
            var types = new ArrayList<TypeVerification>();
            for (var cut : copied) {
                var table    = plan.tables().stream().filter(t -> t.aggregateType().equals(cut.aggregateType())).findFirst().orElseThrow();
                var storage  = Migration.storage(connection, table);
                var verified = verifiedThrough(connection, table.aggregateType());
                var from     = Math.min(verified, cut.from());
                types.add(verify(connection, table, storage, from, cut.to(), true));
            }
            var report = new VerificationReport(VerificationReport.Mode.CUTOVER, types, Duration.ofNanos(System.nanoTime() - start));
            if (!report.equal()) {
                log.error("Migration verification (cut-over) found differences; rolling the cut-over back:\n{}", report.describe());
                throw new VerificationFailedException(report);
            }
            for (var type : types) {
                record(connection, type, VerificationReport.Mode.CUTOVER, type.to(), false);
            }
            log.info("Migration verification (cut-over) equal in {}:\n{}", report.took(), report.describe());
        };
    }

    private TypeVerification verify(Connection connection, SourceTable table, MigrationStorage storage, long from, long to, boolean underCutover) throws SQLException {
        var withPayload = !seams.has(VERIFY_WITHOUT_PAYLOAD);
        var oldHash     = storage.oldRangeHash(withPayload);
        var newHash     = storage.newRangeHash(withPayload);
        var windows     = new ArrayList<Window>();
        var differing   = new ArrayList<RowDifference>();
        long rows       = 0;
        for (long lo = from; lo < to; ) {
            var hi       = Math.min(lo + plan.settings().verifyWindow(), to);
            var oldSide  = hash(connection, oldHash, lo, hi);
            var newSide  = hash(connection, newHash, lo, hi);
            var window   = new Window(lo, hi, oldSide.rows(), newSide.rows(), oldSide.md5(), newSide.md5());
            rows += oldSide.rows();
            windows.add(window);
            if (!window.equal()) {
                bisect(connection, storage, withPayload, lo, hi, differing);
            }
            lo = hi;
        }
        var failures = new ArrayList<String>();
        if (to > from) {
            try (var statement = connection.prepareStatement(storage.headMismatches())) {
                statement.setLong(1, from);
                statement.setLong(2, to);
                try (var resultSet = statement.executeQuery()) {
                    resultSet.next();
                    var count = resultSet.getLong(1);
                    if (count > 0) {
                        failures.add(count + " streams with events in (" + from + ", " + to + "] whose head is missing or differs from max(event_order), e.g. " +
                                             Arrays.toString((Object[]) resultSet.getArray(2).getArray()));
                    }
                }
            }
        }
        if (!underCutover) {
            var orphans = queryLong(connection, storage.orphanHeads());
            if (orphans > 0) {
                failures.add(orphans + " heads without a stream");
            }
        } else {
            // §3.6: the identity continues above the old sequence, not above the highest copied row
            var catalog   = SourceCatalog.read(connection, table);
            var targetMax = queryLong(connection, storage.targetMaxGlobalOrderUpTo(), to);
            var expected  = Math.max(catalog.sequenceLastValue(), targetMax);
            var actual    = queryLong(connection, storage.targetSequenceLastValue());
            if (actual < expected) {
                failures.add("the event log's identity sequence stands at " + actual + ", below greatest(old sequence last_value " + catalog.sequenceLastValue() +
                                     ", max copied global_order " + targetMax + ") = " + expected + ": new events would be drawn below an old resume point");
            }
        }
        return new TypeVerification(table.aggregateType(), from, to, rows, windows, differing, failures);
    }

    private record Hash(long rows, String md5) {
    }

    private static Hash hash(Connection connection, String sql, long lo, long hi) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lo);
            statement.setLong(2, hi);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return new Hash(resultSet.getLong(1), resultSet.getString(2));
            }
        }
    }

    /** §6.1: halves a differing range until it is narrow enough to compare row by row */
    private void bisect(Connection connection, MigrationStorage storage, boolean withPayload, long lo, long hi, List<RowDifference> differing) throws SQLException {
        if (differing.size() >= MAX_DIFFERING_ROWS) {
            return;
        }
        if (hi - lo <= BISECT_LEAF) {
            compareRows(connection, storage, withPayload, lo, hi, differing);
            return;
        }
        var mid = lo + (hi - lo) / 2;
        for (var half : new long[][]{{lo, mid}, {mid, hi}}) {
            if (!hash(connection, storage.oldRangeHash(withPayload), half[0], half[1]).equals(hash(connection, storage.newRangeHash(withPayload), half[0], half[1]))) {
                bisect(connection, storage, withPayload, half[0], half[1], differing);
            }
        }
    }

    private static void compareRows(Connection connection, MigrationStorage storage, boolean withPayload, long lo, long hi, List<RowDifference> differing) throws SQLException {
        var oldRows = rowHashes(connection, storage.oldRowHashes(withPayload), lo, hi);
        var newRows = rowHashes(connection, storage.newRowHashes(withPayload), lo, hi);
        var orders  = new TreeSet<Long>(oldRows.keySet());
        orders.addAll(newRows.keySet());
        for (var order : orders) {
            if (differing.size() >= MAX_DIFFERING_ROWS) {
                return;
            }
            var o = oldRows.get(order);
            var n = newRows.get(order);
            if (n == null) {
                differing.add(new RowDifference(order, RowDifference.Kind.MISSING_IN_EVENT_LOG));
            } else if (o == null) {
                differing.add(new RowDifference(order, RowDifference.Kind.ONLY_IN_EVENT_LOG));
            } else if (!o.equals(n)) {
                differing.add(new RowDifference(order, RowDifference.Kind.DIFFERENT));
            }
        }
    }

    private static Map<Long, String> rowHashes(Connection connection, String sql, long lo, long hi) throws SQLException {
        var rows = new HashMap<Long, String>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lo);
            statement.setLong(2, hi);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.put(resultSet.getLong(1), resultSet.getString(2));
                }
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ the record in el_migration.report

    static long verifiedThrough(Connection connection, String aggregateType) throws SQLException {
        try (var statement = connection.prepareStatement(VERIFIED_THROUGH)) {
            statement.setString(1, aggregateType);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
        }
    }

    /** Only numbers and hex hashes go into the JSON, so it is built as text */
    private static void record(Connection connection, TypeVerification type, VerificationReport.Mode mode, long verifiedThrough, boolean replaceWindows) throws SQLException {
        var summary = "{\"mode\": \"" + mode + "\", \"from\": " + type.from() + ", \"to\": " + type.to() + ", \"rows\": " + type.rows() + ", \"equal\": " + type.equal() +
                ", \"differingWindows\": " + type.differingWindows().size() + ", \"differingRows\": " +
                type.differingRows().stream().map(r -> String.valueOf(r.globalOrder())).toList() + ", \"structuralFailures\": " + type.structuralFailures().size() + "}";
        var windows = type.equal() ? type.windows().stream()
                                         .map(w -> "{\"from\": " + w.from() + ", \"to\": " + w.to() + ", \"rows\": " + w.oldRows() + ", \"md5\": \"" + w.oldMd5() + "\"}")
                                         .toList().toString()
                                   : "[]";
        try (var statement = connection.prepareStatement(RECORD)) {
            statement.setLong(1, verifiedThrough);
            statement.setString(2, summary);
            statement.setBoolean(3, replaceWindows && type.equal());
            statement.setString(4, windows);
            statement.setString(5, windows);
            statement.setString(6, type.aggregateType());
            statement.executeUpdate();
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
}
