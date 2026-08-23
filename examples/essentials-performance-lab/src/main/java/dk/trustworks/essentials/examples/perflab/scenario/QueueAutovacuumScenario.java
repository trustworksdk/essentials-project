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

package dk.trustworks.essentials.examples.perflab.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import dk.trustworks.essentials.examples.perflab.queuedesign.QueueSchemaPrototype;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Does setting per-table autovacuum parameters on the queue table actually help, and with which values?
 *
 * <h2>Why this needs its own shape</h2>
 * Every other scenario here runs one insert-then-drain cycle, which cannot see autovacuum at all: the dead
 * tuples a single cycle creates are still there when it ends. That is exactly why autovacuum showed up across
 * this investigation as *noise* rather than as a measurable effect — run-to-run spreads of 1.13-2.99x in §7, and
 * an arm that degraded 5.7x across three identical repetitions once a long-running transaction pinned the xmin
 * horizon.
 * <p>
 * So the shape here is <b>repeated cycles against one table</b>, and the signal is <b>degradation across
 * cycles</b>: does cycle 10 cost more than cycle 1? A queue is the worst case for dead tuples — every message is
 * inserted, updated once on claim and deleted on acknowledgement, so a drained queue is pure garbage by volume —
 * and if aggressive settings are worth anything, they show up as a flatter curve.
 *
 * <h2>The cluster-level caveat that may dominate</h2>
 * {@code autovacuum_naptime} defaults to 60 seconds and is a <b>cluster</b> setting, not a table one. A queue
 * that churns tens of thousands of rows in a few seconds can outrun the autovacuum daemon's willingness to even
 * look, and in that regime a per-table {@code scale_factor} changes nothing because the threshold is not what is
 * binding. Essentials can set table-level parameters in its DDL; it cannot set naptime.
 * <p>
 * That makes naptime a dimension rather than a footnote — the scenario is meant to be run against containers
 * configured with different naptimes, and the answer may well be that the table-level settings this plan
 * proposed are necessary but not sufficient, with the rest being operator guidance.
 */
@Component
public class QueueAutovacuumScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(QueueAutovacuumScenario.class);

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final ObjectMapper                                                  objectMapper;

    public QueueAutovacuumScenario(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                   ObjectMapper objectMapper) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "queue-autovacuum";
    }

    @Override
    public String description() {
        return "Runs repeated insert/drain cycles against one queue table to measure whether per-table autovacuum settings flatten the degradation curve";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        var cycles           = properties.getAutovacuumCycles();
        var messagesPerCycle = properties.getAutovacuumMessagesPerCycle();
        var runId            = Long.toHexString(System.nanoTime());

        var naptime = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                                 .createQuery("SHOW autovacuum_naptime")
                                                                 .mapTo(String.class)
                                                                 .one());
        log.info("queue-autovacuum: cycles={}, messagesPerCycle={}, cluster autovacuum_naptime={}",
                 cycles, messagesPerCycle, naptime);

        var results = new ArrayList<CycleResult>();
        for (var arm : Arm.values()) {
            results.addAll(runArm(runId, arm, cycles, messagesPerCycle));
        }

        var report = new LinkedHashMap<String, Object>();
        report.put("scenario", name());
        report.put("capturedAt", Instant.now().toString());
        report.put("cycles", cycles);
        report.put("messagesPerCycle", messagesPerCycle);
        // Recorded because it may be the variable that actually matters, and it is not one Essentials can set.
        report.put("clusterAutovacuumNaptime", naptime);
        report.put("cycleResults", results);
        report.put("summary", buildSummary(results, cycles));

        var json = toJson(report);
        System.out.println("############# [perf-lab] queue-autovacuum: " + json);
        writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
    }

    private List<CycleResult> runArm(String runId, Arm arm, int cycles, int messagesPerCycle) {
        var table     = "av_" + runId + "_" + arm.ordinal();
        var queueName = "q_" + runId + "_" + arm.ordinal();

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            QueueSchemaPrototype.v1SingleTableDdl(table, 100).forEach(s -> unitOfWork.handle().execute(s));
            if (arm.storageParameters() != null) {
                unitOfWork.handle().execute("ALTER TABLE " + table + " SET (" + arm.storageParameters() + ")");
            }
        });

        var payload = "{\"filler\":\"" + "x".repeat(200) + "\"}";
        var results = new ArrayList<CycleResult>();
        for (var cycle = 0; cycle < cycles; cycle++) {
            var now         = OffsetDateTime.now();
            var insertStart = System.nanoTime();
            unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
                var batch = unitOfWork.handle().prepareBatch(QueueSchemaPrototype.insertUnorderedSql(table));
                for (var i = 0; i < messagesPerCycle; i++) {
                    batch.bind("id", UUID.randomUUID().toString())
                         .bind("queueName", queueName)
                         .bind("payload", payload)
                         .bind("payloadType", "LabAutovacuumItem")
                         .bind("now", now)
                         .add();
                }
                batch.execute();
            });
            var insertMillis = millisSince(insertStart);

            // Batched claim and batched acknowledge, so the per-message transaction tax from §7 does not
            // dominate and the dead-tuple effect stays visible.
            var claimNanos = 0L;
            var ackNanos   = 0L;
            var drained    = 0;
            while (true) {
                var claimStart = System.nanoTime();
                var ids = unitOfWorkFactory.withUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                                   .createQuery(QueueSchemaPrototype.claimUnorderedSql(table, true))
                                                                                   .bind("queueName", queueName)
                                                                                   .bind("now", OffsetDateTime.now())
                                                                                   .bind("limit", 500)
                                                                                   .mapTo(String.class)
                                                                                   .list());
                claimNanos += System.nanoTime() - claimStart;
                if (ids.isEmpty()) {
                    break;
                }
                drained += ids.size();
                var ackStart = System.nanoTime();
                unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                          .createUpdate(QueueSchemaPrototype.deleteBatchSql(table))
                                                                          .bindList("ids", ids)
                                                                          .execute());
                ackNanos += System.nanoTime() - ackStart;
            }

            var stats = readStats(table);
            results.add(new CycleResult(arm.name(), cycle, messagesPerCycle, drained,
                                        insertMillis,
                                        Duration.ofNanos(claimNanos).toMillis(),
                                        Duration.ofNanos(ackNanos).toMillis(),
                                        stats.deadTuples(), stats.liveTuples(),
                                        stats.autovacuumCount(), stats.heapBytes()));
            log.info("queue-autovacuum {} cycle {} => insert {} ms, claim {} ms, ack {} ms, deadTuples {}, autovacuums {}, heap {}",
                     arm, cycle, insertMillis, Duration.ofNanos(claimNanos).toMillis(),
                     Duration.ofNanos(ackNanos).toMillis(), stats.deadTuples(), stats.autovacuumCount(), stats.heapBytes());
        }

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle().execute("DROP TABLE IF EXISTS " + table));
        return results;
    }

    private Stats readStats(String table) {
        return unitOfWorkFactory.withUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                        .createQuery("""
                                                                                     SELECT COALESCE(s.n_dead_tup, 0)      AS dead_tuples,
                                                                                            COALESCE(s.n_live_tup, 0)      AS live_tuples,
                                                                                            COALESCE(s.autovacuum_count,0) AS autovacuum_count,
                                                                                            pg_table_size(c.oid)           AS heap_bytes
                                                                                       FROM pg_class c
                                                                                       LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
                                                                                      WHERE c.relname = :table
                                                                                     """)
                                                                        .bind("table", table)
                                                                        .map((rs, ctx) -> new Stats(rs.getLong("dead_tuples"),
                                                                                                    rs.getLong("live_tuples"),
                                                                                                    rs.getLong("autovacuum_count"),
                                                                                                    rs.getLong("heap_bytes")))
                                                                        .one());
    }

    /**
     * The headline is the <b>degradation ratio</b>: the last cycle's drain cost divided by the first's. A value
     * near 1 means the table is holding up; a value well above 1 means dead tuples are accumulating faster than
     * they are reclaimed and every cycle pays for its predecessors.
     */
    private List<Map<String, Object>> buildSummary(List<CycleResult> results, int cycles) {
        var summary = new ArrayList<Map<String, Object>>();
        for (var arm : Arm.values()) {
            var forArm = results.stream().filter(r -> r.arm().equals(arm.name())).toList();
            if (forArm.size() < 2) {
                continue;
            }
            var first = forArm.getFirst();
            var last  = forArm.getLast();
            var entry = new LinkedHashMap<String, Object>();
            entry.put("arm", arm.name());
            entry.put("storageParameters", arm.storageParameters() == null ? "(none - PostgreSQL defaults)" : arm.storageParameters());
            entry.put("firstCycleDrainMillis", first.claimMillis() + first.ackMillis());
            entry.put("lastCycleDrainMillis", last.claimMillis() + last.ackMillis());
            entry.put("degradationRatio", (first.claimMillis() + first.ackMillis()) == 0
                                          ? null
                                          : (double) (last.claimMillis() + last.ackMillis()) / (first.claimMillis() + first.ackMillis()));
            entry.put("finalDeadTuples", last.deadTuples());
            entry.put("peakDeadTuples", forArm.stream().mapToLong(CycleResult::deadTuples).max().orElse(0L));
            entry.put("autovacuumsObserved", last.autovacuumCount());
            entry.put("finalHeapBytes", last.heapBytes());
            summary.add(entry);
        }
        return summary;
    }

    private static long millisSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private void writeMetricsIfConfigured(String metricsOutputFile, String json) throws IOException {
        if (!StringUtils.hasText(metricsOutputFile)) {
            return;
        }
        var target = Paths.get(metricsOutputFile).toAbsolutePath().normalize();
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, json + System.lineSeparator(),
                          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        log.info("Wrote queue-autovacuum metrics to {}", target);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize queue-autovacuum metrics to JSON", e);
        }
    }

    public enum Arm {
        /**
         * PostgreSQL's defaults: {@code scale_factor} 0.2, so a 100 000-row table waits for 20 000 dead tuples
         * before it is even a candidate.
         */
        DEFAULT(null),
        /**
         * What `pgmq` ships, roughly: react at 1% dead tuples and do not throttle the worker once it starts.
         */
        AGGRESSIVE("autovacuum_vacuum_scale_factor = 0.01, autovacuum_vacuum_cost_delay = 0, autovacuum_vacuum_threshold = 100"),
        /**
         * A middle setting, in case the aggressive one costs more in vacuum work than it saves in bloat.
         */
        MODERATE("autovacuum_vacuum_scale_factor = 0.05, autovacuum_vacuum_cost_delay = 2");

        private final String storageParameters;

        Arm(String storageParameters) {
            this.storageParameters = storageParameters;
        }

        public String storageParameters() {
            return storageParameters;
        }
    }

    private record Stats(long deadTuples, long liveTuples, long autovacuumCount, long heapBytes) {
    }

    public record CycleResult(String arm,
                              int cycle,
                              int messagesInserted,
                              int messagesDrained,
                              long insertMillis,
                              long claimMillis,
                              long ackMillis,
                              long deadTuples,
                              long liveTuples,
                              long autovacuumCount,
                              long heapBytes) {
    }
}
