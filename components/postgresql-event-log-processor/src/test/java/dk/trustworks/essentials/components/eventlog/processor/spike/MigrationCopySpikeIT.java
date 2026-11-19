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

package dk.trustworks.essentials.components.eventlog.processor.spike;

import com.zaxxer.hikari.HikariDataSource;
import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.migration.MigrationSourceTables;
import dk.trustworks.essentials.components.eventlog.migration.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.NoEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring.SpringTransactionAwareEventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.transaction.spring.UnitOfWorkTransactionIsolation;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.PGConnection;
import org.springframework.jdbc.datasource.*;

import java.io.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * How fast the
 * old engine's tables copy into the event log's, how long the stop lasts, and what the copy costs the old engine.
 * <p>
 * Source: 10 aggregate types built by {@link PostgresqlEventStore} ({@code TEXT} ids, {@code JSONB}, as the starter
 * wires it), a few real appends per type, then a bulk SQL generator whose rows have the real appends' shape (same event
 * type names, revision, metadata, payload keys, ~1 KB payload; checked and recorded per arm), with 20 % holes in
 * {@code global_order}. Target: {@link EventLog#registerAggregateType}. The copy SQL is hand-written here (the
 * migration module has the real one).
 * <p>
 * Arms ({@code -Dspike.p9.arms}, comma separated): {@code IS_1000}, {@code IS_10000}, {@code IS_100000} (same-database
 * {@code INSERT ... SELECT} per {@code global_order} range of that many values), {@code COPY_10000} ({@code COPY}
 * out through pgjdbc's {@code CopyManager} and back into a temp staging table, then {@code INSERT ... SELECT} with the
 * type join), {@code ONLINE} (epochs, at batch 10 000, against an old engine appending 1 000 ev/s, then a 10 000 event
 * delta over 10 types copied by the final pass under {@code SHARE} locks, with delta verify and an import of 50
 * subscriptions). Every bulk arm also runs the online verify over every window. Fresh container
 * ({@code fsync=on}) per (run, arm). Results {@code target/spike-results/p9-a-*.txt}. Knobs: {@code -Dspike.p9.events}
 * (1 000 000), {@code -Dspike.p9.runs} (2).
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class MigrationCopySpikeIT {
    private static final String       BULK     = "p9-a-bulk.txt";
    private static final String       ONLINE   = "p9-a-online.txt";
    private static final String       ONLINE_G = "p9-g-online-throttled.txt";
    private static final int          TYPES    = 10;
    private static final long         EVENTS   = Long.getLong("spike.p9.events", 1_000_000);
    private static final int          RUNS     = Integer.getInteger("spike.p9.runs", 2);
    private static final List<String> ARMS     = List.of(System.getProperty("spike.p9.arms", "IS_1000,IS_10000,IS_100000,COPY_10000,ONLINE").split(","));
    private static final long         WINDOW   = 100_000;
    private static final String       UUID_RE  = "'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'";
    private static final String       CAUSE_OLD = "CASE WHEN o.caused_by_event_id ~ " + UUID_RE + " THEN o.caused_by_event_id END";

    // ------------------------------------------------------------------ the payload the real appends write

    record EntryOpened(String entryId, String account, long amountMinor, String currency, String description, List<String> tags) {
    }

    record EntryAmended(String entryId, String account, long amountMinor, String currency, String description, List<String> tags) {
    }

    record EntryClosed(String entryId, String account, long amountMinor, String currency, String description, List<String> tags) {
    }

    private static String description(int n) {
        var b = new StringBuilder();
        while (b.length() < 800) b.append(UUID.nameUUIDFromBytes(Integer.toString(n + b.length()).getBytes()).toString().replace("-", ""));
        return b.substring(0, 800);
    }

    // ------------------------------------------------------------------ test

    @Test
    void migrationCopy() throws Exception {
        header(BULK, "BULK COPY + ONLINE VERIFY");
        header(ONLINE, "ONLINE EPOCHS + CUT-OVER");
        for (int run = 1; run <= RUNS; run++) {
            var arms = new ArrayList<>(ARMS);
            if (run % 2 == 0) Collections.reverse(arms);
            for (var arm : arms) {
                arm = arm.trim();
                try (var db = SpikeDb.start("p9a_r" + run + "_" + arm.toLowerCase(Locale.ROOT), 60, true); var src = new Source(db)) {
                    src.build();
                    if (arm.equals("ONLINE")) online(db, src, run);
                    else if (arm.startsWith("ONLINE_T")) onlineThrottled(db, src, run, Long.parseLong(arm.substring("ONLINE_T".length())));
                    else bulk(db, src, run, arm);
                }
            }
        }
    }

    // ------------------------------------------------------------------ bulk arms

    private void bulk(SpikeDb db, Source src, int run, String arm) throws Exception {
        boolean copy  = arm.startsWith("COPY_");
        int     batch = Integer.parseInt(arm.substring(arm.indexOf('_') + 1));
        try (var target = new Target(db, src)) {
            db.checkpoint();
            db.resetStatistics();
            long cpu0 = db.cpuMicros();
            long t0   = System.nanoTime();
            long rows = 0;
            for (var type : src.types) {
                long max = SpikeDb.queryLong(target.copier, "SELECT coalesce(max(global_order), 0) FROM " + src.table(type));
                rows += target.copyRange(type, 0, max, batch, copy);
            }
            double seconds = (System.nanoTime() - t0) / 1e9;
            long   cpu     = db.cpuMicros() - cpu0;
            long   wal     = db.walSinceReset();
            for (var type : src.types) target.setval(type);
            var stmts = db.statements();
            long srcSize = src.size(), tgtSize = target.size();
            SpikeDb.record(BULK, String.format("BULK run %d | %-10s | copied %d rows in %.1f s = %.0f events/s | server CPU %.1f s | WAL %.0f B/event (%.2f GB) | source %.0f MB, target %.0f MB "
                                                       + "(events+heads; ratio %.2f)",
                                               run, arm, rows, seconds, rows / seconds, cpu / 1e6, wal / (double) rows, wal / 1e9, srcSize / 1e6, tgtSize / 1e6, tgtSize / (double) srcSize));
            stmts.stream().filter(s -> !s.utility()).sorted(Comparator.comparingDouble(SpikeDb.Statement::totalExecMs).reversed()).limit(5).forEach(s -> {
                var q = s.query().replaceAll("\\s+", " ");
                SpikeDb.record(BULK, String.format("     calls %7d rows %8d exec %9.1f ms wal %11d B | %s", s.calls(), s.rows(), s.totalExecMs(), s.walBytes(), q.substring(0, Math.min(120, q.length()))));
            });
            var v = target.verify(null);
            SpikeDb.record(BULK, String.format("VERIFY run %d | %-10s | %d windows of %d values, %d rows per side, mismatching windows %d | %.1f s = %.0f events/s (both sides hashed) | heads ok %s, sequences ok %s",
                                               run, arm, v.windows, WINDOW, v.rows, v.mismatches, v.seconds, v.rows / v.seconds, target.headsOk(), target.sequencesOk()));
            if (v.mismatches > 0) throw new IllegalStateException("verification mismatch in arm " + arm);
        }
    }

    // ------------------------------------------------------------------ online arm

    private void online(SpikeDb db, Source src, int run) throws Exception {
        try (var target = new Target(db, src); var appender = new Appender(src)) {
            db.checkpoint();
            appender.start();
            Thread.sleep(5_000); // warm-up
            appender.mark("baseline-before");
            Thread.sleep(30_000);
            appender.mark("epochs");
            var copiedThrough = new long[TYPES];
            epochs(target, src, run, copiedThrough, "under load");
            appender.mark("baseline-after");
            Thread.sleep(30_000);
            appender.mark("end");
            appender.stop();
            epochs(target, src, run, copiedThrough, "catch-up, appender stopped");
            var lat = appender.byPhase();
            var before = lat.get("baseline-before");
            var during = lat.get("epochs");
            var after  = lat.get("baseline-after");
            SpikeDb.record(ONLINE, String.format("APPEND run %d | old-engine appends of 1 event, paced 1 000 ev/s on 4 threads over 10 types | baseline-before n=%d p50 %.2f p99 %.2f ms | during epochs n=%d p50 %.2f p99 %.2f ms "
                                                         + "| baseline-after n=%d p50 %.2f p99 %.2f ms | p99 ratio during/mean(baselines) %.2f | achieved rate during epochs %.0f ev/s | errors %d",
                                                 run, before.n, before.p50, before.p99, during.n, during.p50, during.p99, after.n, after.p50, after.p99,
                                                 during.p99 / ((before.p99 + after.p99) / 2), during.n / Math.max(during.seconds, 1e-9), appender.errors.get()));

            // the final pass: a 10 000-event delta (1 000 per type) appended by the old engine, 50 subscriptions to import
            src.appendDelta(1_000);
            src.createSubscriptions(5, copiedThrough);
            db.checkpoint();
            db.resetStatistics();
            var steps = new LinkedHashMap<String, Double>();
            long deltaRows = 0;
            long stopStart = System.nanoTime();
            var  c         = target.copier;
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                long t = System.nanoTime();
                s.execute("SET LOCAL lock_timeout = '10s'");
                var tables = new ArrayList<String>();
                for (var type : src.types) tables.add(src.table(type));
                Collections.sort(tables);
                s.execute("LOCK TABLE " + String.join(", ", tables) + " IN SHARE MODE");
                steps.put("lock", ms(t));
                t = System.nanoTime();
                var max = new long[TYPES];
                for (int i = 0; i < TYPES; i++) {
                    var type = src.types.get(i);
                    max[i] = SpikeDb.queryLong(c, "SELECT coalesce(max(global_order), 0) FROM " + src.table(type));
                    deltaRows += target.copyBatch(type, copiedThrough[i], max[i], false);
                }
                steps.put("copy delta + heads", ms(t));
                t = System.nanoTime();
                for (var type : src.types) target.setval(type);
                steps.put("setval", ms(t));
                t = System.nanoTime();
                long windowRows = 0, mismatch = 0;
                for (int i = 0; i < TYPES; i++) {
                    for (long w = (copiedThrough[i] / WINDOW) * WINDOW; w < max[i]; w += WINDOW) {
                        var r = target.compareWindow(src.types.get(i), w, w + WINDOW);
                        windowRows += r[0];
                        mismatch += r[1];
                    }
                }
                steps.put("verify touched 100k windows (" + windowRows + " rows/side, mismatches " + mismatch + ")", ms(t));
                t = System.nanoTime();
                long exactRows = 0, exactMismatch = 0;
                for (int i = 0; i < TYPES; i++) {
                    var r = target.compareWindow(src.types.get(i), copiedThrough[i], max[i]);
                    exactRows += r[0];
                    exactMismatch += r[1];
                }
                steps.put("ALT: verify exact delta range only (" + exactRows + " rows/side, mismatches " + exactMismatch + ")", ms(t));
                t = System.nanoTime();
                boolean structural = target.headsOkFor(copiedThrough) && target.sequencesOk();
                steps.put("structural checks on delta (" + structural + ")", ms(t));
                t = System.nanoTime();
                long imported = target.importSubscriptions();
                steps.put("import " + imported + " subscriptions", ms(t));
                t = System.nanoTime();
                s.execute("UPDATE spike_migration SET state = 'CUT_OVER'");
                c.commit();
                steps.put("state + commit", ms(t));
                if (mismatch + exactMismatch > 0 || !structural || imported != 50) throw new IllegalStateException("cut-over verification failed");
            } finally {
                c.setAutoCommit(true);
            }
            double stopMs = ms(stopStart);
            double altMs  = 0;
            for (var e : steps.entrySet()) if (!e.getKey().startsWith("ALT")) altMs += e.getKey().startsWith("verify touched") ? 0 : e.getValue();
            for (var e : steps.entrySet()) if (e.getKey().startsWith("ALT")) altMs += e.getValue();
            double designMs = stopMs - steps.entrySet().stream().filter(e -> e.getKey().startsWith("ALT")).mapToDouble(Map.Entry::getValue).sum();
            SpikeDb.record(ONLINE, String.format("CUTOVER run %d | delta %d rows over %d types | stop as designed (touched windows verified) %.0f ms | stop with exact-range verify instead %.0f ms "
                                                         + "| measured wall (both verifies) %.0f ms | WAL %d B", run, deltaRows, TYPES, designMs, altMs, stopMs, db.walSinceReset()));
            steps.forEach((k, v) -> SpikeDb.record(ONLINE, String.format("     %-90s %8.1f ms", k, v)));
            var v = target.verify(null);
            SpikeDb.record(ONLINE, String.format("VERIFY run %d | ONLINE | after cut-over, every window: %d rows per side, mismatching windows %d | %.0f events/s | heads ok %s",
                                                 run, v.rows, v.mismatches, v.rows / v.seconds, target.headsOk()));
            if (v.mismatches > 0) throw new IllegalStateException("verification mismatch after cut-over");
        }
    }

    /**
     * Gate 9-G: the append p99 line re-measured with the real {@link Migration} API (not the spike's hand-written
     * copy),
     * its online copy throttled by {@link CopySettings#withMaxEventsPerSecond}; arm {@code ONLINE_T<rate>}, {@code 0} unthrottled.
     * Same phases as {@link #online}: warm-up, 30 s baseline, epochs until a delta under 5 000 rows, 30 s baseline.
     */
    private void onlineThrottled(SpikeDb db, Source src, int run, long rate) throws Exception {
        try (var pool = db.pool("reader", 4); var appender = new Appender(src)) {
            var eventLog = EventLog.builder().setDataSource(pool).build();
            eventLog.start();
            try {
                var tables = new ArrayList<SourceTable>();
                for (var type : src.types) tables.add(MigrationSourceTables.from(src.eventStore.getAggregateEventStreamConfiguration(type)));
                var migration = new Migration(new MigrationPlan(pool, eventLog, tables, CopySettings.defaults().withMaxEventsPerSecond(rate)));
                migration.prepare();
                db.checkpoint();
                appender.start();
                Thread.sleep(5_000); // warm-up
                appender.mark("baseline-before");
                Thread.sleep(30_000);
                appender.mark("epochs");
                long copied = 0;
                long t0     = System.nanoTime();
                for (int epoch = 1; epoch <= 6; epoch++) {
                    var report = migration.copyEpoch();
                    copied += report.rows();
                    SpikeDb.record(ONLINE_G, String.format("EPOCH run %d | ONLINE_T%d | epoch %d | %d rows | wait %d ms | copy %.2f s = %.0f events/s", run, rate, epoch, report.rows(),
                                                           report.waited().toMillis(), report.copyTime().toMillis() / 1e3, report.rows() / Math.max(report.copyTime().toMillis() / 1e3, 1e-3)));
                    if (report.rows() < 5_000) break;
                }
                double epochsS = (System.nanoTime() - t0) / 1e9;
                appender.mark("baseline-after");
                Thread.sleep(30_000);
                appender.mark("end");
                appender.stop();
                var lat    = appender.byPhase();
                var before = lat.get("baseline-before");
                var during = lat.get("epochs");
                var after  = lat.get("baseline-after");
                var verify = migration.verify();
                SpikeDb.record(ONLINE_G, String.format("APPEND run %d | ONLINE_T%d (maxEventsPerSecond %d) | copied %d rows in %.1f s = %.0f events/s overall | baseline-before n=%d p50 %.2f p99 %.2f ms "
                                                               + "| during epochs n=%d p50 %.2f p99 %.2f ms | baseline-after n=%d p50 %.2f p99 %.2f ms | p99 ratio during/mean(baselines) %.2f "
                                                               + "| achieved append rate during epochs %.0f ev/s | errors %d | verify equal %s",
                                                       run, rate, rate, copied, epochsS, copied / epochsS, before.n, before.p50, before.p99, during.n, during.p50, during.p99, after.n, after.p50, after.p99,
                                                       during.p99 / ((before.p99 + after.p99) / 2), during.n / Math.max(during.seconds, 1e-9), appender.errors.get(), verify.equal()));
            } finally {
                eventLog.stop();
            }
        }
    }

    private void epochs(Target target, Source src, int run, long[] copiedThrough, String label) throws Exception {
            for (int epoch = 1; epoch <= 6; epoch++) {
            long eStart = System.nanoTime();
            var  m      = new long[TYPES];
            for (int i = 0; i < TYPES; i++) m[i] = SpikeDb.queryLong(target.copier, "SELECT coalesce(max(global_order), 0) FROM " + src.table(src.types.get(i)));
            var    t0     = SpikeDb.queryString(target.copier, "SELECT clock_timestamp()::text");
            long   wStart = System.nanoTime();
            int    polls  = 0;
            while (SpikeDb.queryLong(target.copier, "SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend' AND pid <> pg_backend_pid() "
                    + "AND xact_start IS NOT NULL AND xact_start <= '" + t0 + "'::timestamptz") > 0) {
                polls++;
                Thread.sleep(5);
            }
            double waitMs = (System.nanoTime() - wStart) / 1e6;
            long   cStart = System.nanoTime();
            long   rows   = 0;
            for (int i = 0; i < TYPES; i++) {
                rows += target.copyRange(src.types.get(i), copiedThrough[i], m[i], 10_000, false);
                copiedThrough[i] = m[i];
            }
            double copyS = (System.nanoTime() - cStart) / 1e9;
            SpikeDb.record(ONLINE, String.format("EPOCH run %d | %s | epoch %d | delta %d rows | epoch wait %.1f ms (%d polls) | copy %.2f s = %.0f events/s | epoch total %.2f s",
                                                 run, label, epoch, rows, waitMs, polls, copyS, rows / Math.max(copyS, 1e-9), (System.nanoTime() - eStart) / 1e9));
            if (rows < 5_000) break;
        }
    }

    private static double ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e6;
    }

    // ------------------------------------------------------------------ the old engine's tables

    /** The old engine as the Spring starter wires it, plus the bulk generator */
    private static final class Source implements AutoCloseable {
        final SpikeDb                   db;
        final HikariDataSource          pool;
        final SpringTransactionAwareEventStoreUnitOfWorkFactory factory;
        final PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
        final List<AggregateType>       types = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Source(SpikeDb db) {
            this.db = db;
            pool = db.pool("writer", 12);
            var jdbi = Jdbi.create(new TransactionAwareDataSourceProxy(pool));
            jdbi.installPlugin(new PostgresPlugin());
            jdbi.setSqlLogger(new SqlExecutionTimeLogger());
            factory = new SpringTransactionAwareEventStoreUnitOfWorkFactory(jdbi, new DataSourceTransactionManager(pool));
            factory.setTransactionIsolation(UnitOfWorkTransactionIsolation.READ_COMMITTED);
            var serializer = EssentialsJSONEventSerializers.create();
            var strategy = SeparateTablePerAggregateTypePersistenceStrategy.builder()
                                                                           .setJdbi(jdbi)
                                                                           .setUnitOfWorkFactory(factory)
                                                                           .setEventMapper(new TestPersistableEventMapper())
                                                                           .setAggregateEventStreamConfigurationFactory(SeparateTablePerAggregateTypeEventStreamConfigurationFactory
                                                                                                                                .standardSingleTenantConfiguration(serializer, IdentifierColumnType.TEXT, JSONColumnType.JSONB))
                                                                           .build();
            eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                             .setUnitOfWorkFactory(factory)
                                             .setPersistenceStrategy(strategy)
                                             .setEventStreamGapHandlerFactory(es -> new NoEventStreamGapHandler<>())
                                             .setEventStoreSubscriptionObserver(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver())
                                             .build();
            for (var name : List.of("Accounts", "BankAccountLinks", "Deposits", "Instruments", "Orders", "ReconciliationCases", "TradeSettlements", "UnknownExchangeOrders",
                                    "Users", "Withdrawals")) {
                var type = AggregateType.of(name);
                eventStore.addAggregateEventStreamConfiguration(type, OrderId.class);
                types.add(type);
            }
        }

        String table(AggregateType type) {
            return eventStore.getAggregateEventStreamConfiguration(type).eventStreamTableName;
        }

        /** Real appends, then the bulk generator, then the shape check */
        void build() throws SQLException {
            var cols = eventStore.getAggregateEventStreamConfiguration(types.get(0)).eventStreamTableColumnNames;
            var expected = List.of("global_order", "aggregate_id", "event_order", "event_id", "caused_by_event_id", "correlation_id", "event_type", "event_revision", "timestamp",
                                   "event_payload", "event_metadata", "tenant");
            var actual = List.of(cols.globalOrderColumn, cols.aggregateIdColumn, cols.eventOrderColumn, cols.eventIdColumn, cols.causedByEventIdColumn, cols.correlationIdColumn,
                                 cols.eventTypeColumn, cols.eventRevisionColumn, cols.timestampColumn, cols.eventPayloadColumn, cols.eventMetaDataColumn, cols.tenantColumn);
            if (!expected.equals(actual)) throw new IllegalStateException("unexpected old column names " + actual);
            // real appends: 3 streams of 10 events per type
            for (var type : types) {
                for (int s = 0; s < 3; s++) appendStream(type, 10, s);
            }
            long perType = EVENTS / TYPES;
            long t0 = System.nanoTime();
            try (var c = db.connect("writer"); var st = c.createStatement()) {
                for (var type : types) {
                    var table = table(type);
                    var tmpl  = "(SELECT event_type FROM " + table + " WHERE event_type LIKE '%$Entry%s' LIMIT 1)";
                    long base = SpikeDb.queryLong(c, "SELECT max(global_order) FROM " + table) + 1;
                    var prefix = type.toString().toLowerCase(Locale.ROOT) + "-";
                    st.execute("INSERT INTO " + table + " (global_order, aggregate_id, event_order, event_id, caused_by_event_id, correlation_id, event_type, event_revision, timestamp, "
                                       + "event_payload, event_metadata, tenant) "
                                       + "SELECT " + base + " + k + k / 4, '" + prefix + "' || (k / 10), k % 10, gen_random_uuid()::text, "
                                       + "CASE WHEN k % 3 = 0 THEN gen_random_uuid()::text END, md5('" + prefix + "' || (k / 10))::uuid::text, "
                                       + "CASE k % 10 WHEN 0 THEN " + tmpl.replace("%s", "Opened") + " WHEN 9 THEN " + tmpl.replace("%s", "Closed") + " ELSE " + tmpl.replace("%s", "Amended") + " END, "
                                       + "'1', now() - interval '30 days' + k * interval '1 millisecond', "
                                       + "jsonb_build_object('entryId', md5(k::text)::uuid::text, 'account', 'ACC-' || (k % 5000), 'amountMinor', (k * 7919) % 1000000, 'currency', 'DKK', "
                                       + "'description', substr(repeat(md5(k::text || '" + prefix + "'), 26), 1, 800), 'tags', jsonb_build_array('ledger', 'k' || (k % 7))), "
                                       + "(SELECT event_metadata FROM " + table + " LIMIT 1), NULL "
                                       + "FROM generate_series(0, " + (perType - 31) + ") k");
                    // 20 % holes inside the bulk; burn a few values at the end too, as rollbacks do
                    st.execute("SELECT setval(pg_get_serial_sequence('" + table + "', 'global_order'), (SELECT max(global_order) + 7 FROM " + table + "), true)");
                }
                for (var type : types) st.execute("VACUUM ANALYZE " + table(type));
            }
            double buildS = (System.nanoTime() - t0) / 1e9;
            // shape check: a real row against a generated row of the same type
            var t = table(types.get(0));
            try (var c = db.connect("writer")) {
                var shape = "SELECT event_type || ' | rev ' || event_revision || ' | id ' || event_id || ' | cause ' || coalesce(caused_by_event_id, 'null') || ' | corr ' || coalesce(correlation_id, 'null') "
                        + "|| ' | eo ' || event_order || ' | keys ' || (SELECT string_agg(k, ',' ORDER BY k) FROM jsonb_object_keys(event_payload) k) || ' | payload ' || pg_column_size(event_payload) "
                        + "|| ' B (text ' || length(event_payload::text) || ') | meta ' || event_metadata::text || ' | tenant ' || coalesce(tenant, 'null') FROM " + t;
                var real = SpikeDb.queryString(c, shape + " WHERE global_order = 2");
                var gen  = SpikeDb.queryString(c, shape + " WHERE global_order = (SELECT max(global_order) - 3 FROM " + t + ")");
                var columns = "SELECT string_agg(column_name || ' ' || data_type, ', ' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_name = '" + t + "'";
                SpikeDb.record(BULK, String.format("  source %s, fsync=" + SpikeDb.queryString(c, "SHOW fsync") + ": %d rows/type x %d types in %.1f s (%.0f rows/s) | %s", db.describe(), SpikeDb.queryLong(c, "SELECT count(*) FROM " + t), TYPES,
                                                   buildS, EVENTS / buildS, SpikeDb.queryString(c, columns)));
                SpikeDb.record(BULK, "  shape REAL      " + real);
                SpikeDb.record(BULK, "  shape GENERATED " + gen);
                SpikeDb.record(BULK, String.format("  holes: %d rows over %d global_order values in %s; sequence cache %s", SpikeDb.queryLong(c, "SELECT count(*) FROM " + t),
                                                   SpikeDb.queryLong(c, "SELECT max(global_order) FROM " + t), t,
                                                   SpikeDb.queryString(c, "SELECT cache_size::text FROM pg_sequences WHERE sequencename = '" + t + "_global_order_seq'")));
            }
        }

        void appendStream(AggregateType type, int events, int n) {
            var id = OrderId.random();
            var list = new ArrayList<Object>();
            for (int i = 0; i < events; i++) {
                var desc = description(n * 100 + i);
                var tags = List.of("ledger", "k" + (i % 7));
                list.add(i == 0 ? new EntryOpened(UUID.randomUUID().toString(), "ACC-" + n, 100L * i, "DKK", desc, tags)
                                : i == events - 1 ? new EntryClosed(UUID.randomUUID().toString(), "ACC-" + n, 100L * i, "DKK", desc, tags)
                                                  : new EntryAmended(UUID.randomUUID().toString(), "ACC-" + n, 100L * i, "DKK", desc, tags));
            }
            factory.usingUnitOfWork(uow -> eventStore.appendToStream(type, id, Optional.empty(), list));
        }

        void appendDelta(int perType) {
            for (var type : types) {
                for (int s = 0; s < perType / 10; s++) appendStream(type, 10, 7_000 + s);
            }
        }

        /** {@code perType} rows per type in a {@code durable_subscriptions}-shaped table, each with transient gaps, some filled */
        void createSubscriptions(int perType, long[] copiedThrough) throws SQLException {
            try (var c = db.connect("writer"); var s = c.createStatement()) {
                s.execute("CREATE TABLE durable_subscriptions (subscriber_id text NOT NULL, aggregate_type text NOT NULL, resume_from_and_including_global_eventorder bigint, "
                                  + "last_updated timestamptz, reposition_epoch bigint NOT NULL DEFAULT 0, PRIMARY KEY (subscriber_id, aggregate_type))");
                s.execute("CREATE TABLE transient_subscriber_gaps (subscriber_id text NOT NULL, aggregate_type text NOT NULL, gap_global_event_order bigint NOT NULL, "
                                  + "first_discovered timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (subscriber_id, aggregate_type, gap_global_event_order))");
                for (int i = 0; i < TYPES; i++) {
                    var type = types.get(i).toString();
                    for (int k = 0; k < perType; k++) {
                        long r = copiedThrough[i] - 1_000L * k;
                        s.execute("INSERT INTO durable_subscriptions VALUES ('sub-" + k + "', '" + type + "', " + r + ", now(), 0)");
                        // a hole (never filled) and a value with a row (a gap the old engine saw and that was filled since)
                        s.execute("INSERT INTO transient_subscriber_gaps (subscriber_id, aggregate_type, gap_global_event_order) "
                                          + "SELECT 'sub-" + k + "', '" + type + "', g FROM (SELECT max(global_order) AS g FROM " + table(types.get(i)) + " WHERE global_order < " + (r - 500)
                                          + " UNION ALL SELECT " + (r - 3) + ") x WHERE g IS NOT NULL ON CONFLICT DO NOTHING");
                    }
                }
            }
        }

        long size() throws SQLException {
            long total = 0;
            try (var c = db.connect("writer")) {
                for (var type : types) total += SpikeDb.queryLong(c, "SELECT pg_total_relation_size('" + table(type) + "')");
            }
            return total;
        }

        @Override
        public void close() {
            pool.close();
        }
    }

    // ------------------------------------------------------------------ the event log's tables and the copy

    private static final class Target implements AutoCloseable {
        final Source                          src;
        final HikariDataSource                pool;
        final EventLog                        eventLog;
        final Connection                      copier;
        final Connection                      copyOut;
        final Map<AggregateType, AggregateTypeTables> tables = new HashMap<>();
        final Set<String>                     interned = new HashSet<>();

        Target(SpikeDb db, Source src) throws SQLException {
            this.src = src;
            pool = db.pool("reader", 4);
            eventLog = EventLog.builder().setDataSource(pool).setSettings(EventLogSettings.defaults()).build();
            eventLog.start();
            for (var type : src.types) tables.put(type, eventLog.registerAggregateType(type.toString()));
            copier = db.connect("reader");
            copyOut = db.connect("reader");
            try (var s = copier.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS spike_migration (aggregate_type text PRIMARY KEY, state text NOT NULL, copied_through bigint NOT NULL)");
                for (var type : src.types) s.execute("INSERT INTO spike_migration VALUES ('" + type + "', 'COPYING', 0)");
                s.execute("CREATE TEMP TABLE spike_staging (global_order bigint, event_order bigint, timestamp timestamptz, event_id text, caused_by_event_id text, correlation_id text, "
                                  + "event_type text, event_revision text, aggregate_id text, event_payload text, event_metadata text, tenant text)");
            }
        }

        /** Copies {@code (from, to]} in batches of {@code batch} values, each in its own transaction with its checkpoint and heads */
        long copyRange(AggregateType type, long from, long to, int batch, boolean viaCopy) throws Exception {
            long rows = 0;
            copier.setAutoCommit(false);
            try {
                for (long lo = from; lo < to; lo += batch) {
                    long hi = Math.min(lo + batch, to);
                    intern(type, lo, hi);
                    try (var s = copier.createStatement()) {
                        s.execute("SET LOCAL synchronous_commit = off");
                    }
                    rows += viaCopy ? copyBatchViaCopy(type, lo, hi) : copyBatch(type, lo, hi, true);
                    copier.commit();
                }
            } catch (Exception e) {
                copier.rollback();
                throw e;
            } finally {
                copier.setAutoCommit(true);
            }
            return rows;
        }

        /** Names in the range not interned yet: {@code idOf} in its own transaction */
        void intern(AggregateType type, long lo, long hi) throws SQLException {
            try (var s = copyOut.createStatement(); var rs = s.executeQuery("SELECT DISTINCT event_type FROM " + src.table(type) + " WHERE global_order > " + lo + " AND global_order <= " + hi)) {
                while (rs.next()) {
                    var name = rs.getString(1);
                    if (interned.add(name)) eventLog.eventTypes().idOf(name);
                }
            }
        }

        /** One batch inside the caller's transaction: count, insert-select, row-count check, heads, checkpoint */
        long copyBatch(AggregateType type, long lo, long hi, boolean checkInterned) throws SQLException {
            if (!checkInterned) intern(type, lo, hi);
            var  old   = src.table(type);
            var  t     = tables.get(type);
            var  range = " WHERE o.global_order > " + lo + " AND o.global_order <= " + hi;
            long expected = SpikeDb.queryLong(copier, "SELECT count(*) FROM " + old + " o" + range);
            int  inserted;
            try (var s = copier.createStatement()) {
                inserted = s.executeUpdate("INSERT INTO " + t.eventTable() + " (global_order, event_order, timestamp, event_id, caused_by_event_id, correlation_id, event_type, event_revision, "
                                                   + "aggregate_id, event_payload, event_metadata, tenant) "
                                                   + "SELECT o.global_order, o.event_order, o.timestamp, o.event_id::uuid, (" + CAUSE_OLD + ")::uuid, o.correlation_id::uuid, t.id, "
                                                   + "o.event_revision::smallint, o.aggregate_id::text, o.event_payload::text::json, o.event_metadata::text::json, o.tenant "
                                                   + "FROM " + old + " o JOIN el_event_types t ON t.name = o.event_type" + range + " ORDER BY o.global_order");
                if (inserted != expected) throw new IllegalStateException("batch (" + lo + ", " + hi + "] of " + old + ": inserted " + inserted + ", expected " + expected);
                heads(s, t, lo, hi);
                s.executeUpdate("UPDATE spike_migration SET copied_through = " + hi + " WHERE aggregate_type = '" + type + "'");
            }
            return inserted;
        }

        private static void heads(Statement s, AggregateTypeTables t, long lo, long hi) throws SQLException {
            s.executeUpdate("INSERT INTO " + t.headTable() + " (aggregate_id, version) SELECT aggregate_id, max(event_order) FROM " + t.eventTable()
                                    + " WHERE global_order > " + lo + " AND global_order <= " + hi + " GROUP BY aggregate_id "
                                    + "ON CONFLICT (aggregate_id) DO UPDATE SET version = greatest(" + t.headTable() + ".version, EXCLUDED.version)");
        }

        /** The COPY arm: COPY out to the client, COPY into a temp staging table, insert-select with the type join */
        long copyBatchViaCopy(AggregateType type, long lo, long hi) throws Exception {
            var old    = src.table(type);
            var t      = tables.get(type);
            var buffer = new ByteArrayOutputStream(16 << 20);
            long out = copyOut.unwrap(PGConnection.class).getCopyAPI().copyOut(
                    "COPY (SELECT global_order, event_order, timestamp, event_id, caused_by_event_id, correlation_id, event_type, event_revision, aggregate_id, event_payload::text, "
                            + "event_metadata::text, tenant FROM " + old + " WHERE global_order > " + lo + " AND global_order <= " + hi + " ORDER BY global_order) TO STDOUT", buffer);
            long in = copier.unwrap(PGConnection.class).getCopyAPI().copyIn("COPY spike_staging FROM STDIN", new ByteArrayInputStream(buffer.toByteArray()));
            if (in != out) throw new IllegalStateException("copy in " + in + " != out " + out);
            try (var s = copier.createStatement()) {
                int inserted = s.executeUpdate("INSERT INTO " + t.eventTable() + " (global_order, event_order, timestamp, event_id, caused_by_event_id, correlation_id, event_type, event_revision, "
                                                       + "aggregate_id, event_payload, event_metadata, tenant) "
                                                       + "SELECT o.global_order, o.event_order, o.timestamp, o.event_id::uuid, (" + CAUSE_OLD + ")::uuid, o.correlation_id::uuid, t.id, "
                                                       + "o.event_revision::smallint, o.aggregate_id, o.event_payload::json, o.event_metadata::json, o.tenant "
                                                       + "FROM spike_staging o JOIN el_event_types t ON t.name = o.event_type ORDER BY o.global_order");
                if (inserted != in) throw new IllegalStateException("staging " + in + " != inserted " + inserted);
                s.execute("TRUNCATE spike_staging");
                heads(s, t, lo, hi);
                s.executeUpdate("UPDATE spike_migration SET copied_through = " + hi + " WHERE aggregate_type = '" + type + "'");
                return inserted;
            }
        }

        void setval(AggregateType type) throws SQLException {
            var old = src.table(type);
            var t   = tables.get(type);
            SpikeDb.queryLong(copier, "SELECT setval(pg_get_serial_sequence('" + t.eventTable() + "', 'global_order'), greatest((SELECT last_value FROM "
                    + "pg_sequences WHERE sequencename = '" + old + "_global_order_seq'), (SELECT coalesce(max(global_order), 1) FROM " + t.eventTable() + ")), true)");
        }

        record Verified(long windows, long rows, long mismatches, double seconds) {
        }

        /** The online verify over every window of every type */
        Verified verify(Void unused) throws SQLException {
            long t0 = System.nanoTime(), windows = 0, rows = 0, mismatches = 0;
            for (var type : src.types) {
                long max = SpikeDb.queryLong(copier, "SELECT coalesce(max(global_order), 0) FROM " + src.table(type));
                for (long w = 0; w < max; w += WINDOW) {
                    var r = compareWindow(type, w, w + WINDOW);
                    windows++;
                    rows += r[0];
                    mismatches += r[1];
                }
            }
            return new Verified(windows, rows, mismatches, (System.nanoTime() - t0) / 1e9);
        }

        /** {count, 0 or 1 mismatch} for {@code (lo, hi]} */
        long[] compareWindow(AggregateType type, long lo, long hi) throws SQLException {
            var t = tables.get(type);
            var oldSql = "SELECT count(*)::text || ':' || coalesce(md5(string_agg(md5(concat_ws('|', o.global_order, o.aggregate_id::text, o.event_order, o.event_id::text, " + CAUSE_OLD
                    + ", o.correlation_id::text, o.event_type, o.event_revision::text, (extract(epoch FROM o.timestamp) * 1e6)::bigint, o.event_payload::text, o.event_metadata::text, "
                    + "coalesce(o.tenant, '∅'))), '' ORDER BY o.global_order)), '') FROM " + src.table(type) + " o WHERE o.global_order > " + lo + " AND o.global_order <= " + hi;
            var newSql = "SELECT count(*)::text || ':' || coalesce(md5(string_agg(md5(concat_ws('|', e.global_order, e.aggregate_id, e.event_order, e.event_id::text, e.caused_by_event_id::text, "
                    + "e.correlation_id::text, y.name, e.event_revision::text, (extract(epoch FROM e.timestamp) * 1e6)::bigint, e.event_payload::text, e.event_metadata::text, "
                    + "coalesce(e.tenant, '∅'))), '' ORDER BY e.global_order)), '') FROM " + t.eventTable() + " e JOIN el_event_types y ON y.id = e.event_type "
                    + "WHERE e.global_order > " + lo + " AND e.global_order <= " + hi;
            var a = SpikeDb.queryString(copier, oldSql);
            var b = SpikeDb.queryString(copier, newSql);
            return new long[]{Long.parseLong(a.substring(0, a.indexOf(':'))), a.equals(b) ? 0 : 1};
        }

        boolean headsOk() throws SQLException {
            return headsOkFor(new long[TYPES]);
        }

        /** Every head of an aggregate with an event above {@code from[i]} equals its stream's max(event_order); head count = distinct ids */
        boolean headsOkFor(long[] from) throws SQLException {
            for (int i = 0; i < TYPES; i++) {
                var t = tables.get(src.types.get(i));
                long bad = SpikeDb.queryLong(copier, "SELECT count(*) FROM (SELECT aggregate_id, max(event_order) v FROM " + t.eventTable() + " WHERE aggregate_id IN (SELECT aggregate_id FROM "
                        + t.eventTable() + " WHERE global_order > " + from[i] + ") GROUP BY aggregate_id) x LEFT JOIN " + t.headTable() + " h USING (aggregate_id) WHERE h.version IS DISTINCT FROM x.v");
                if (bad != 0) return false;
                if (from[i] == 0 && SpikeDb.queryLong(copier, "SELECT count(*) FROM " + t.headTable()) != SpikeDb.queryLong(copier, "SELECT count(DISTINCT aggregate_id) FROM " + t.eventTable())) {
                    return false;
                }
            }
            return true;
        }

        boolean sequencesOk() throws SQLException {
            for (var type : src.types) {
                var t = tables.get(type);
                if (SpikeDb.queryLong(copier, "SELECT count(*) FROM pg_sequences WHERE sequencename = '" + t.eventTable() + "_global_order_seq' AND last_value >= (SELECT last_value FROM pg_sequences "
                        + "WHERE sequencename = '" + src.table(type) + "_global_order_seq')") != 1) return false;
            }
            return true;
        }

        /** The imported resume point per subscription: least(R, lowest open gap that has a row now), written as a progress row */
        long importSubscriptions() throws SQLException {
            long n = 0;
            try (var s = copier.createStatement()) {
                for (var type : src.types) {
                    var t = tables.get(type);
                    n += s.executeUpdate("INSERT INTO el_progress (subscriber, aggregate_type, instance_name, exclusive, consistency_mode, progress_model, resume_from) "
                                                 + "SELECT d.subscriber_id, d.aggregate_type, '', true, 'FRESH', 'PERIODIC', least(d.resume_from_and_including_global_eventorder, "
                                                 + "(SELECT min(g.gap_global_event_order) FROM transient_subscriber_gaps g WHERE g.subscriber_id = d.subscriber_id AND g.aggregate_type = d.aggregate_type "
                                                 + "AND g.gap_global_event_order < d.resume_from_and_including_global_eventorder AND EXISTS (SELECT 1 FROM " + src.table(type)
                                                 + " o WHERE o.global_order = g.gap_global_event_order))) FROM durable_subscriptions d WHERE d.aggregate_type = '" + type + "'");
                }
            }
            return n;
        }

        long size() throws SQLException {
            long total = 0;
            for (var t : tables.values()) total += SpikeDb.queryLong(copier, "SELECT pg_total_relation_size('" + t.eventTable() + "') + pg_total_relation_size('" + t.headTable() + "')");
            return total;
        }

        @Override
        public void close() throws Exception {
            copier.close();
            copyOut.close();
            eventLog.stop();
            pool.close();
        }
    }

    // ------------------------------------------------------------------ the old engine's application load

    private static final class Appender implements AutoCloseable {
        record Sample(long startNanos, long nanos) {
        }

        record Stats(long n, double p50, double p99, double seconds) {
        }

        final Source                         src;
        final ExecutorService                threads = Executors.newFixedThreadPool(4);
        final ConcurrentLinkedQueue<Sample>  samples = new ConcurrentLinkedQueue<>();
        final List<Map.Entry<String, Long>>  marks   = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong                     errors  = new AtomicLong();
        volatile boolean                     running = true;

        Appender(Source src) {
            this.src = src;
        }

        void start() {
            long start = System.nanoTime();
            for (int t = 0; t < 4; t++) {
                int thread = t;
                threads.submit(() -> {
                    long next = start + thread * 1_000_000L;
                    long i    = 0;
                    while (running) {
                        long now = System.nanoTime();
                        if (now < next) LockSupport_park(next - now);
                        long n    = i++;
                        var  type = src.types.get((int) ((thread + 4 * n) % TYPES));
                        var  desc = description((int) n);
                        long t0 = System.nanoTime();
                        try {
                            src.factory.usingUnitOfWork(uow -> src.eventStore.appendToStream(type, OrderId.random(), Optional.empty(),
                                                                                             List.of(new EntryOpened(UUID.randomUUID().toString(), "ACC-1", n, "DKK", desc, List.of("ledger")))));
                            samples.add(new Sample(t0, System.nanoTime() - t0));
                        } catch (Throwable e) {
                            errors.incrementAndGet();
                        }
                        next += 4_000_000L; // 250/s per thread
                    }
                });
            }
        }

        private static void LockSupport_park(long nanos) {
            java.util.concurrent.locks.LockSupport.parkNanos(nanos);
        }

        void mark(String phase) {
            marks.add(Map.entry(phase, System.nanoTime()));
        }

        void stop() throws InterruptedException {
            running = false;
            threads.shutdown();
            threads.awaitTermination(60, TimeUnit.SECONDS);
        }

        Map<String, Stats> byPhase() {
            var result = new HashMap<String, Stats>();
            for (int m = 0; m < marks.size() - 1; m++) {
                long from = marks.get(m).getValue(), to = marks.get(m + 1).getValue();
                var sorted = samples.stream().filter(s -> s.startNanos >= from && s.startNanos < to).mapToLong(Sample::nanos).sorted().toArray();
                result.put(marks.get(m).getKey(), sorted.length == 0 ? new Stats(0, Double.NaN, Double.NaN, (to - from) / 1e9)
                                                                     : new Stats(sorted.length, sorted[(int) Math.floor(0.50 * (sorted.length - 1))] / 1e6,
                                                                                 sorted[(int) Math.floor(0.99 * (sorted.length - 1))] / 1e6, (to - from) / 1e9));
            }
            return result;
        }

        @Override
        public void close() throws InterruptedException {
            if (running) stop();
        }
    }

    private static void header(String file, String shape) {
        SpikeDb.record(file, "");
        SpikeDb.record(file, "=== " + shape + " | " + Instant.now() + " | container " + SpikeDb.IMAGE + " (fsync=on) | CPUs " + Runtime.getRuntime().availableProcessors()
                + " | pgjdbc " + org.postgresql.Driver.getVersion() + " | events " + EVENTS + " over " + TYPES + " types, ~1 KB jsonb payload, 20 % holes | arms " + ARMS + " | runs " + RUNS);
    }
}
