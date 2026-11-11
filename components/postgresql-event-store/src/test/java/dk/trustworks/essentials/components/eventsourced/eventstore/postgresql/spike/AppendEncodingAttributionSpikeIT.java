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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.CurrentEngineBaselineSpikeIT.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.CurrentEngineBaselineSpikeIT.Stack;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.SingleStatementAppendSpikeIT.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Encoding follow-up to spike P0.4 ({@code docs/plans/event-store-phase0-append.md} §6): where does the single-statement
 * append's server time go, and what do the parameter encodings of {@link Encoding} change?
 * <ol>
 *     <li>Correctness: every encoding writes rows identical to the engine's.</li>
 *     <li>Server execution per append, single writer, no conflicts: per variant a fresh table pre-filled with 10
 *     aggregates x 2 000 events, then 3 000 units of work (300 warm-up) of "read last event order, append 1 (or 10)
 *     events after it", round-robin over the aggregates. Reported: {@code pg_stat_statements} execution of the append
 *     statement(s) per unit of work, plans, buffers, client wall p50 and the container's CPU per unit of work. Two
 *     rounds, variant order rotated.</li>
 *     <li>Plans: {@code auto_explain} (analyze, buffers, verbose, generic plan forced) for a separate role, 50 units of
 *     work per variant and size; the last plan of each append statement is recorded.</li>
 * </ol>
 * Results go to {@code target/spike-results/p0-4-encoding.txt}. Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class AppendEncodingAttributionSpikeIT {
    private static final String RESULTS    = "p0-4-encoding.txt";
    private static final int    ITERATIONS = Integer.getInteger("spike.encoding.iterations", 3000);

    /** null encoding = arm A (the engine). */
    record Variant(String label, Encoding encoding, JSONColumnType json) {
        Variant(String label, Encoding encoding) {
            this(label, encoding, JSONColumnType.JSONB);
        }

        boolean takes(int events) {
            return encoding != Encoding.SCALAR_1 || events == 1;
        }
    }

    private static final List<Variant> VARIANTS = List.of(new Variant("A engine", null),
                                                          new Variant("B text arrays", Encoding.TEXT_ARRAYS),
                                                          new Variant("B binary arrays", Encoding.BINARY_ARRAYS),
                                                          new Variant("B one jsonb", Encoding.JSONB_ONE),
                                                          new Variant("B scalar (1 event)", Encoding.SCALAR_1),
                                                          new Variant("B no head (diagnostic)", Encoding.NO_HEAD),
                                                          new Variant("A engine [json]", null, JSONColumnType.JSON),
                                                          new Variant("B text arrays [json]", Encoding.TEXT_ARRAYS, JSONColumnType.JSON),
                                                          new Variant("B binary arrays [json]", Encoding.BINARY_ARRAYS, JSONColumnType.JSON),
                                                          new Variant("B scalar (1 event) [json]", Encoding.SCALAR_1, JSONColumnType.JSON));

    @Test
    void attribution() throws Exception {
        record("");
        record("=== ENCODING | " + Instant.now() + " | CPUs " + Runtime.getRuntime().availableProcessors() + " | " + ITERATIONS + " units of work per (variant, size, round), 300 warm-up, "
                       + "single writer, tables pre-filled with 10 aggregates x 2 000 events, fsync=off");
        try (var pg = Pg.start()) {
            try (var s = pg.observer.createStatement()) {
                s.execute("ALTER SYSTEM SET pg_stat_statements.track_planning = on");
                s.execute("SELECT pg_reload_conf()");
                s.execute("CREATE ROLE explainer LOGIN SUPERUSER PASSWORD 'explainer'");
                for (var setting : List.of("session_preload_libraries = 'auto_explain'", "auto_explain.log_min_duration = 0", "auto_explain.log_analyze = on",
                                           "auto_explain.log_buffers = on", "auto_explain.log_verbose = on", "auto_explain.log_nested_statements = on",
                                           "plan_cache_mode = force_generic_plan")) {
                    s.execute("ALTER ROLE explainer SET " + setting);
                }
            }
            var w     = new Writer(Stack.writer(pg, "writer"));
            var wJson = new Writer(Stack.writer(pg, "writer", JSONColumnType.JSON));
            correctness(pg, w);
            conversion(pg, w);

            for (int round = 1; round <= 2; round++) {
                var order = new ArrayList<>(VARIANTS);
                Collections.rotate(order, round - 1);
                for (int events : List.of(1, 10)) {
                    for (var v : order) {
                        if (!v.takes(events)) continue;
                        measure(pg, v.json() == JSONColumnType.JSON ? wJson : w, v, events, round);
                    }
                }
            }

            var exJsonb = new Writer(Stack.writer(pg, "explainer"));
            var exJson  = new Writer(Stack.writer(pg, "explainer", JSONColumnType.JSON));
            for (int events : List.of(1, 10)) {
                for (var v : VARIANTS) {
                    if (!v.takes(events)) continue;
                    var ex   = v.json() == JSONColumnType.JSON ? exJson : exJsonb;
                    var type = prefilled(ex, "ex");
                    var cfg  = ex.config(type);
                    var logsBefore = pg.container.getLogs().length();
                    var ids = aggregates(ex, type);
                    for (int i = 0; i < 50; i++) unitOfWork(ex, type, cfg, v, ids.get(i % ids.size()), events);
                    var logs = pg.container.getLogs().substring(logsBefore);
                    record("PLAN | " + v.label() + " | " + events + " event(s) | last auto_explain plan of the append statement:");
                    lastPlanOf(logs, "INSERT INTO " + cfg.eventStreamTableName).forEach(l -> record("    " + l));
                }
            }
            exJsonb.stack.close();
            exJson.stack.close();
            wJson.stack.close();
            w.stack.close();
        }
    }

    private void correctness(Pg pg, Writer w) throws Exception {
        var type    = w.freshType("encsanity");
        var cfg     = w.config(type);
        var engine1 = OrderId.random();
        var engine2 = OrderId.random();
        SingleStatementAppendSpikeIT.payloadIdForSanity(engine1.toString());
        var added   = new OrderEvent.OrderAdded(engine1, CustomerId.random(), 42);
        var product = new OrderEvent.ProductAddedToOrder(engine1, ProductId.random(), 3);
        w.stack.unitOfWorkFactory.usingUnitOfWork(uow -> w.stack.eventStore.appendToStream(type, engine1, Optional.empty(), List.of(added)));
        w.stack.unitOfWorkFactory.usingUnitOfWork(uow -> w.stack.eventStore.appendToStream(type, engine2, Optional.empty(), List.of(added, product)));
        var one = SingleStatementAppendSpikeIT.describeRows(pg, cfg, engine1);
        var two = SingleStatementAppendSpikeIT.describeRows(pg, cfg, engine2).stream().map(r -> r.replace(engine2.toString(), "<id>")).toList();
        for (var e : Encoding.values()) {
            var a = OrderId.random();
            var r = w.stack.unitOfWorkFactory.withUnitOfWork(uow -> w.encoded(uow, cfg, List.of(new Append(a, Optional.of(-1L), List.of(added))), e));
            assertThat(r.counts()).as(e.name()).containsExactly(1);
            assertThat(SingleStatementAppendSpikeIT.describeRows(pg, cfg, a)).as(e.name()).isEqualTo(one);
            if (e == Encoding.SCALAR_1) continue;
            var b = OrderId.random();
            var r2 = w.stack.unitOfWorkFactory.withUnitOfWork(uow -> w.encoded(uow, cfg, List.of(new Append(b, Optional.of(-1L), List.of(added, product))), e));
            assertThat(r2.counts()).as(e.name()).containsExactly(2);
            assertThat(r2.persisted()).extracting(p -> p.eventOrder().longValue()).containsExactly(0L, 1L);
            assertThat(SingleStatementAppendSpikeIT.describeRows(pg, cfg, b)).as(e.name()).isEqualTo(two);
            if (e != Encoding.NO_HEAD) {
                var stale = w.stack.unitOfWorkFactory.withUnitOfWork(uow -> w.encoded(uow, cfg, List.of(new Append(b, Optional.of(0L), List.of(product))), e));
                assertThat(stale.counts()).as(e.name() + " stale").containsExactly(0);
            }
        }
        record("CORRECTNESS ok | every encoding writes the engine's rows (1 and 2 events); a stale expected order gives zero rows except NO_HEAD (no check by design)");
    }

    /**
     * The server cost of turning the event's payload and metadata text into the column type, alone: one statement
     * casting the same text 200 000 times to text (baseline), json and jsonb; best of three per cast. The
     * {@code left('', g % 1)} keeps the planner from folding the cast into one constant.
     */
    private void conversion(Pg pg, Writer w) throws Exception {
        var type = w.freshType("conv");
        var cfg  = w.config(type);
        var id   = OrderId.random();
        var s    = Serialized.of(cfg, PersistableEvent.from(EventId.random(), cfg.aggregateType, id, EventTypeOrName.with(OrderEvent.ProductAddedToOrder.class),
                                                             new OrderEvent.ProductAddedToOrder(id, ProductId.random(), 1), EventOrder.of(0), EventRevision.of(1),
                                                             EventMetaData.of("Key1", "Value1", "Key2", "Value2"), java.time.OffsetDateTime.now(), EventId.random(), CorrelationId.random(), null));
        for (var doc : List.of(Map.entry("payload", s.json().getJson()), Map.entry("metadata", s.metaData().getJson()))) {
            var line = new StringJoiner(", ", "CONVERSION | " + doc.getKey() + " (" + doc.getValue().length() + " chars) | executor µs per value, best of 3, 200 000 values: ", "");
            for (var target : List.of("text", "json", "jsonb")) {
                double best = Double.MAX_VALUE;
                for (int i = 0; i < 3; i++) {
                    var sql = "EXPLAIN (ANALYZE, TIMING OFF, SUMMARY ON, FORMAT JSON) SELECT count(x) FROM (SELECT (p || left('', g % 1))::" + target + " AS x FROM (SELECT ?::text AS p) d, generate_series(1, 200000) g OFFSET 0) q";
                    try (var st = pg.observer.prepareStatement(sql)) {
                        st.setString(1, doc.getValue());
                        try (var rs = st.executeQuery()) {
                            rs.next();
                            var json = rs.getString(1);
                            var m = java.util.regex.Pattern.compile("\"Execution Time\": ([0-9.]+)").matcher(json);
                            if (m.find()) best = Math.min(best, Double.parseDouble(m.group(1)));
                        }
                    }
                }
                line.add(String.format("%s %.3f", target, best * 1000 / 200_000));
            }
            record(line.toString());
        }
    }

    private static AggregateType prefilled(Writer w, String prefix) {
        var type = w.freshType(prefix);
        for (int a = 0; a < 10; a++) {
            var id = OrderId.random();
            for (int batch = 0; batch < 20; batch++) {
                var events = new ArrayList<Object>(100);
                for (int i = 0; i < 100; i++) events.add(i == 0 && batch == 0 ? new OrderEvent.OrderAdded(id, CustomerId.random(), 1) : new OrderEvent.ProductAddedToOrder(id, ProductId.random(), 1));
                w.stack.unitOfWorkFactory.usingUnitOfWork(uow -> w.stack.eventStore.appendToStream(type, id, Optional.empty(), events));
            }
        }
        return type;
    }

    private static List<OrderId> aggregates(Writer w, AggregateType type) throws SQLException {
        var cfg = w.config(type);
        var ids = new ArrayList<OrderId>();
        try (var c = w.stack.dataSource.getConnection(); var s = c.createStatement();
             var rs = s.executeQuery("SELECT DISTINCT " + cfg.eventStreamTableColumnNames.aggregateIdColumn + " FROM " + cfg.eventStreamTableName)) {
            while (rs.next()) ids.add(OrderId.of(rs.getString(1)));
        }
        return ids;
    }

    private static void unitOfWork(Writer w, AggregateType type, SeparateTablePerAggregateEventStreamConfiguration cfg, Variant v, OrderId id, int count) {
        w.stack.unitOfWorkFactory.usingUnitOfWork(uow -> {
            long last   = w.stack.eventStore.loadLastPersistedEventRelatedTo(type, id).orElseThrow().eventOrder().longValue();
            var  events = new ArrayList<Object>(count);
            for (int i = 0; i < count; i++) events.add(new OrderEvent.ProductAddedToOrder(id, ProductId.random(), 1));
            if (v.encoding() == null) {
                w.stack.eventStore.appendToStream(type, id, Optional.of(last), events);
            } else {
                var r = w.encoded(uow, cfg, List.of(new Append(id, Optional.of(last), events)), v.encoding());
                if (r.counts()[0] != count) throw new IllegalStateException("conflict in a single-writer run");
            }
        });
    }

    private void measure(Pg pg, Writer w, Variant v, int events, int round) throws Exception {
        var type = prefilled(w, "enc" + round);
        var cfg  = w.config(type);
        var ids  = aggregates(w, type);
        for (int i = 0; i < 300; i++) unitOfWork(w, type, cfg, v, ids.get(i % ids.size()), events);
        pg.resetStatistics();
        long cpu0 = ContendedAppendThroughputSpikeIT.containerCpuMicros(pg);
        var wall = new ArrayList<Long>(ITERATIONS);
        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.nanoTime();
            unitOfWork(w, type, cfg, v, ids.get(i % ids.size()), events);
            wall.add(System.nanoTime() - t0);
        }
        long cpu = ContendedAppendThroughputSpikeIT.containerCpuMicros(pg) - cpu0;
        // the append statement(s): every writer statement that inserts into this table
        double execMs = 0, planMs = 0, blocks = 0, totalMs = 0;
        long   calls = 0, plans = 0;
        try (var s = pg.observer.createStatement();
             var rs = s.executeQuery("SELECT s.query, s.calls, s.plans, s.total_plan_time, s.total_exec_time, s.shared_blks_hit + s.shared_blks_read FROM pg_stat_statements s "
                                             + "JOIN pg_roles r ON r.oid = s.userid WHERE r.rolname = 'writer'")) {
            while (rs.next()) {
                totalMs += rs.getDouble(5);
                if (!rs.getString(1).contains("INSERT INTO " + cfg.eventStreamTableName)) continue;
                calls += rs.getLong(2);
                plans += rs.getLong(3);
                planMs += rs.getDouble(4);
                execMs += rs.getDouble(5);
                blocks += rs.getLong(6);
            }
        }
        var l = Latencies.of(wall);
        record(String.format("EXEC round %d | %-24s | %2d event(s) | append exec %6.1f µs/UoW (%d calls, %d plans, plan %.1f µs/UoW) | buffers %.1f/UoW | all writer statements exec %6.1f µs/UoW "
                                     + "| container CPU %6.1f µs/UoW | client wall p50 %.3f ms p99 %.3f ms",
                             round, v.label(), events, execMs * 1000 / ITERATIONS, calls, plans, planMs * 1000 / ITERATIONS, blocks / ITERATIONS, totalMs * 1000 / ITERATIONS,
                             cpu / (double) ITERATIONS, l.p50(), l.p99()));
    }

    /** The last auto_explain block in the logs whose query text contains the marker. */
    private static List<String> lastPlanOf(String logs, String marker) {
        List<String> last = List.of("(no plan found)");
        var lines = logs.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].contains("LOG:  duration:") || !lines[i].contains("plan:")) continue;
            var block = new ArrayList<String>();
            block.add(lines[i].substring(lines[i].indexOf("duration:")));
            int j = i + 1;
            while (j < lines.length && (lines[j].startsWith("\t") || lines[j].startsWith(" "))) block.add(lines[j++].replace("\t", "  "));
            if (String.join("\n", block).contains(marker)) last = block;
            i = j - 1;
        }
        return last;
    }

    private static void record(String line) {
        SpikePostgres.record(RESULTS, line);
    }
}
