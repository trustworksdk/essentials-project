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
 * What the delivery-statistics {@code AFTER DELETE} trigger costs, and whether the designed replacement is
 * worth building.
 *
 * <h2>The claim under test</h2>
 * {@code docs/durable-queues-statistics-improvements.md} argues the trigger is the wrong mechanism on seven
 * counts, of which two are measurable here. Every acknowledged message pays a plpgsql invocation, an
 * {@code INSERT} and maintenance on two indexes — and, the sharpest claim, the
 * {@code EXCEPTION WHEN OTHERS} guard around that insert is implemented in plpgsql as an <b>implicit savepoint,
 * so it costs a subtransaction per row</b>. The document's contention is that at sustained throughput this burns
 * subtransaction ids and pushes the subtransaction SLRU toward overflow, degrading unrelated queries on the same
 * database — making the safety net more dangerous than the thing it guards.
 * <p>
 * That is a specific, falsifiable claim, and PostgreSQL exposes the evidence directly: {@code pg_stat_slru} has a
 * row for the subtransaction cache, so the reads, writes and hits it attributes can be differenced across the
 * drain.
 *
 * <h2>Arms</h2>
 * <ul>
 *     <li>{@code NO_STATISTICS} — the queue table alone. Statistics are off by default in the framework, so this
 *     is what most deployments run.</li>
 *     <li>{@code TRIGGER_AS_SHIPPED} — the trigger exactly as {@code PostgresqlDurableQueuesStatistics} installs
 *     it, {@code EXCEPTION WHEN OTHERS} and all, against a stats table with its two indexes.</li>
 *     <li>{@code TRIGGER_WITHOUT_EXCEPTION} — the same trigger with the exception block removed. The difference
 *     between this and the arm above <em>is</em> the subtransaction cost, isolated.</li>
 *     <li>{@code JAVA_OBSERVER_SIMULATED} — no trigger; the client inserts the statistics rows itself, batched,
 *     once per acknowledged batch. This is the shape the improvements document proposes, so this arm says
 *     whether implementing it pays.</li>
 * </ul>
 * Acknowledgement is batched, because that is now the framework's own recommended path and because a per-message
 * acknowledgement would let §7's transaction tax swamp the effect being measured.
 */
@Component
public class QueueStatisticsTriggerScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(QueueStatisticsTriggerScenario.class);

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final ObjectMapper                                                  objectMapper;

    public QueueStatisticsTriggerScenario(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                          ObjectMapper objectMapper) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "queue-statistics-trigger";
    }

    @Override
    public String description() {
        return "Measures the delivery-statistics AFTER DELETE trigger, isolates its EXCEPTION-block subtransaction cost, and compares the proposed Java-side observer";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        var messages    = properties.getStatisticsTriggerMessages();
        var repetitions = properties.getStatisticsTriggerRepetitions();
        var runId       = Long.toHexString(System.nanoTime());

        log.info("queue-statistics-trigger: messages={}, repetitions={}", messages, repetitions);

        var results = new ArrayList<CaseResult>();
        for (var repetition = 0; repetition < repetitions; repetition++) {
            for (var arm : Arm.values()) {
                var result = runCase(runId, arm, messages, repetition);
                results.add(result);
                log.info("queue-statistics-trigger {} rep {} => insert {} ms, claim {} ms, ack {} ms, statsRows {}, "
                                 + "subtransSlruWrites {}, subtransSlruReads {}",
                         result.arm(), repetition, result.insertMillis(), result.claimMillis(), result.ackMillis(),
                         result.statisticsRows(), result.subtransactionSlruWrites(), result.subtransactionSlruReads());
            }
        }

        var report = new LinkedHashMap<String, Object>();
        report.put("scenario", name());
        report.put("capturedAt", Instant.now().toString());
        report.put("messages", messages);
        report.put("cases", results);
        report.put("comparisons", buildComparisons(results));

        var json = toJson(report);
        System.out.println("############# [perf-lab] queue-statistics-trigger: " + json);
        writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
    }

    private CaseResult runCase(String runId, Arm arm, int messages, int repetition) {
        var suffix     = runId + "_" + arm.ordinal() + "_r" + repetition;
        var table      = "st_" + suffix;
        var statsTable = "sx_" + suffix;
        var queueName  = "q_" + suffix;

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            QueueSchemaPrototype.v1SingleTableDdl(table, 100).forEach(s -> unitOfWork.handle().execute(s));
            if (arm != Arm.NO_STATISTICS) {
                statisticsTableDdl(statsTable).forEach(s -> unitOfWork.handle().execute(s));
            }
            if (arm == Arm.TRIGGER_AS_SHIPPED || arm == Arm.TRIGGER_WITHOUT_EXCEPTION) {
                unitOfWork.handle().execute(triggerDdl(statsTable, table, suffix, arm == Arm.TRIGGER_AS_SHIPPED));
            }
        });

        var payload     = "{\"filler\":\"" + "x".repeat(200) + "\"}";
        var now         = OffsetDateTime.now();
        var insertStart = System.nanoTime();
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var batch = unitOfWork.handle().prepareBatch(QueueSchemaPrototype.insertUnorderedSql(table));
            for (var i = 0; i < messages; i++) {
                batch.bind("id", UUID.randomUUID().toString())
                     .bind("queueName", queueName)
                     .bind("payload", payload)
                     .bind("payloadType", "LabStatisticsItem")
                     .bind("now", now)
                     .add();
            }
            batch.execute();
        });
        var insertMillis = millisSince(insertStart);

        var slruBefore = readSubtransactionSlru();
        var claimNanos = 0L;
        var ackNanos   = 0L;
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
            var ackStart = System.nanoTime();
            unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
                if (arm == Arm.JAVA_OBSERVER_SIMULATED) {
                    // The proposed shape: capture the statistics rows from the delete itself, in the same
                    // statement, rather than firing a trigger per row.
                    unitOfWork.handle().createUpdate(observerAckSql(table, statsTable)).bindList("ids", ids).execute();
                } else {
                    unitOfWork.handle().createUpdate(QueueSchemaPrototype.deleteBatchSql(table)).bindList("ids", ids).execute();
                }
            });
            ackNanos += System.nanoTime() - ackStart;
        }
        var slruAfter = readSubtransactionSlru();

        var statsRows = arm == Arm.NO_STATISTICS
                        ? 0L
                        : unitOfWorkFactory.withUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                                   .createQuery("SELECT count(*) FROM " + statsTable)
                                                                                   .mapTo(Long.class)
                                                                                   .one());

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.handle().execute("DROP TABLE IF EXISTS " + table);
            unitOfWork.handle().execute("DROP TABLE IF EXISTS " + statsTable);
            unitOfWork.handle().execute("DROP FUNCTION IF EXISTS log_message_delivery_stats_" + suffix + "() CASCADE");
        });

        return new CaseResult(arm.name(), repetition, messages, insertMillis,
                              Duration.ofNanos(claimNanos).toMillis(), Duration.ofNanos(ackNanos).toMillis(),
                              statsRows,
                              slruAfter.writes() - slruBefore.writes(),
                              slruAfter.reads() - slruBefore.reads(),
                              slruAfter.hits() - slruBefore.hits());
    }

    /**
     * The statistics table exactly as {@code PostgresqlDurableQueuesStatistics} creates it, including both
     * indexes — their maintenance is part of what the trigger costs.
     */
    private static List<String> statisticsTableDdl(String statsTable) {
        return List.of("""
                       CREATE TABLE %1$s (
                           id                     TEXT PRIMARY KEY,
                           queue_name             TEXT NOT NULL,
                           added_ts               TIMESTAMPTZ NOT NULL,
                           delivery_ts            TIMESTAMPTZ NOT NULL,
                           deletion_ts            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                           total_attempts         INTEGER NOT NULL,
                           redelivery_attempts    INTEGER NOT NULL,
                           delivery_mode          TEXT NOT NULL,
                           delivery_latency       INTERVAL NOT NULL,
                           delivery_error         BOOLEAN NOT NULL,
                           meta_data              JSONB DEFAULT NULL
                       )
                       """.formatted(statsTable),
                       "CREATE INDEX idx_" + statsTable + "_queue_name ON " + statsTable + " (queue_name)",
                       "CREATE INDEX idx_" + statsTable + "_stats ON " + statsTable + " (queue_name, added_ts)");
    }

    /**
     * The trigger as shipped, or the same without its exception block. The function name is suffixed per case
     * because the shipped one is unqualified and argument-less, which is itself a defect the improvements
     * document records — two instances in one schema overwrite each other.
     */
    private static String triggerDdl(String statsTable, String queueTable, String suffix, boolean withExceptionBlock) {
        var insert = """
                     INSERT INTO %1$s (id, queue_name, added_ts, delivery_ts, deletion_ts, total_attempts,
                                       redelivery_attempts, delivery_mode, delivery_latency, delivery_error, meta_data)
                     VALUES (OLD.id, OLD.queue_name, OLD.added_ts, OLD.delivery_ts, NOW(), OLD.total_attempts,
                             OLD.redelivery_attempts, OLD.delivery_mode, NOW() - OLD.added_ts,
                             OLD.last_delivery_error IS NOT NULL, OLD.meta_data);
                     """.formatted(statsTable);
        var body = withExceptionBlock
                   // The implicit savepoint. This is the line the measurement is about.
                   ? "BEGIN\n" + insert + "EXCEPTION WHEN OTHERS THEN\n  RAISE NOTICE 'failed: %', SQLERRM;\nEND;\n"
                   : insert;
        return """
               CREATE OR REPLACE FUNCTION log_message_delivery_stats_%3$s() RETURNS TRIGGER AS $$
                 BEGIN
                   %2$s
                   RETURN OLD;
                 END;
               $$ LANGUAGE plpgsql;
               CREATE TRIGGER trg_stats_%3$s AFTER DELETE ON %1$s FOR EACH ROW
                 EXECUTE FUNCTION log_message_delivery_stats_%3$s();
               """.formatted(queueTable, body, suffix);
    }

    /**
     * The proposed replacement, expressed in SQL: delete the batch and capture what was deleted into the
     * statistics table in one statement. No plpgsql, no per-row invocation, no subtransaction — and the
     * statistics rows still land.
     */
    private static String observerAckSql(String table, String statsTable) {
        return """
               WITH deleted AS (
                 DELETE FROM %1$s WHERE id IN (<ids>) RETURNING *
               )
               INSERT INTO %2$s (id, queue_name, added_ts, delivery_ts, deletion_ts, total_attempts,
                                 redelivery_attempts, delivery_mode, delivery_latency, delivery_error, meta_data)
               SELECT d.id, d.queue_name, d.added_ts, COALESCE(d.delivery_ts, d.added_ts), NOW(), d.total_attempts,
                      d.redelivery_attempts, d.delivery_mode, NOW() - d.added_ts,
                      d.last_delivery_error IS NOT NULL, d.meta_data
                 FROM deleted d
               """.formatted(table, statsTable);
    }

    /**
     * The subtransaction SLRU counters. This is the direct evidence for the improvements document's sharpest
     * claim — that the exception block costs a subtransaction per row — rather than an inference from wall clock.
     */
    private Slru readSubtransactionSlru() {
        return unitOfWorkFactory.withUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                        .createQuery("""
                                                                                     SELECT COALESCE(sum(blks_written), 0) AS writes,
                                                                                            COALESCE(sum(blks_read), 0)    AS reads,
                                                                                            COALESCE(sum(blks_hit), 0)     AS hits
                                                                                       FROM pg_stat_slru
                                                                                      WHERE name ILIKE '%subtrans%'
                                                                                     """)
                                                                        .map((rs, ctx) -> new Slru(rs.getLong("writes"),
                                                                                                   rs.getLong("reads"),
                                                                                                   rs.getLong("hits")))
                                                                        .one());
    }

    private List<Map<String, Object>> buildComparisons(List<CaseResult> results) {
        var comparisons = new ArrayList<Map<String, Object>>();
        var baseline    = median(results, Arm.NO_STATISTICS);
        if (baseline == null) {
            return comparisons;
        }
        for (var arm : List.of(Arm.TRIGGER_AS_SHIPPED, Arm.TRIGGER_WITHOUT_EXCEPTION, Arm.JAVA_OBSERVER_SIMULATED)) {
            var candidate = median(results, arm);
            if (candidate == null) {
                continue;
            }
            var comparison = new LinkedHashMap<String, Object>();
            comparison.put("arm", arm.name());
            comparison.put("baselineAckMillis", baseline.ack());
            comparison.put("candidateAckMillis", candidate.ack());
            // What enabling statistics costs on the acknowledgement path, per mechanism.
            comparison.put("ackCostMultiple", baseline.ack() == 0 ? null : (double) candidate.ack() / baseline.ack());
            comparison.put("candidateSubtransactionSlruWrites", candidate.subtransWrites());
            comparisons.add(comparison);
        }
        var shipped = median(results, Arm.TRIGGER_AS_SHIPPED);
        var noExcept = median(results, Arm.TRIGGER_WITHOUT_EXCEPTION);
        if (shipped != null && noExcept != null) {
            var isolated = new LinkedHashMap<String, Object>();
            isolated.put("arm", "EXCEPTION_BLOCK_ISOLATED");
            isolated.put("withExceptionAckMillis", shipped.ack());
            isolated.put("withoutExceptionAckMillis", noExcept.ack());
            isolated.put("exceptionBlockCostMultiple", noExcept.ack() == 0 ? null : (double) shipped.ack() / noExcept.ack());
            isolated.put("withExceptionSubtransactionSlruWrites", shipped.subtransWrites());
            isolated.put("withoutExceptionSubtransactionSlruWrites", noExcept.subtransWrites());
            comparisons.add(isolated);
        }
        return comparisons;
    }

    private static Medians median(List<CaseResult> results, Arm arm) {
        var matching = results.stream().filter(r -> r.arm().equals(arm.name())).toList();
        if (matching.isEmpty()) {
            return null;
        }
        return new Medians(medianOf(matching.stream().map(CaseResult::ackMillis).sorted().toList()),
                           medianOf(matching.stream().map(CaseResult::subtransactionSlruWrites).sorted().toList()));
    }

    private static long medianOf(List<Long> sorted) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        var middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2L;
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
        log.info("Wrote queue-statistics-trigger metrics to {}", target);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize queue-statistics-trigger metrics to JSON", e);
        }
    }

    public enum Arm {
        NO_STATISTICS,
        TRIGGER_AS_SHIPPED,
        TRIGGER_WITHOUT_EXCEPTION,
        JAVA_OBSERVER_SIMULATED
    }

    private record Slru(long writes, long reads, long hits) {
    }

    private record Medians(long ack, long subtransWrites) {
    }

    public record CaseResult(String arm,
                             int repetition,
                             int messagesInserted,
                             long insertMillis,
                             long claimMillis,
                             long ackMillis,
                             long statisticsRows,
                             long subtransactionSlruWrites,
                             long subtransactionSlruReads,
                             long subtransactionSlruHits) {
    }
}
