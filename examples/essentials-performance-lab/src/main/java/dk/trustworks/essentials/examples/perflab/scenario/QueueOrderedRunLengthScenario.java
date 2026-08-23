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
 * The gate on the per-key cursor: <strong>is claiming a run of one key's messages worth anything, and
 * when?</strong>
 *
 * <h2>Why this is the deciding measurement</h2>
 * Everything else about the cursor is settled. Its claim is 2.18x faster than the barrier and it holds 14.0 MB
 * of index against 25.8 MB; a rolling deploy works; the stranding hazard is closed. None of that justifies a
 * schema migration on its own. The remaining case is that the cursor can hand one claimer a <em>run</em> of a
 * key's messages, which the barrier structurally cannot — its {@code NOT EXISTS (… key_order < mine)} is a
 * per-row test, so a key yields only its head no matter how high the limit goes. A run amortises one
 * transaction across N messages, and §7 of the measurements established that the transaction is the cost.
 *
 * <h2>The variable is key cardinality, not run length</h2>
 * This scenario exists in this shape because the obvious experiment is useless. Both claim statements cap their
 * total at {@code :limit}, so raising the run length changes <em>which</em> rows come back — deeper into fewer
 * keys — and not how many. Rounds per message would be identical and there would be nothing to measure.
 * <p>
 * Runs can only pay when the ready keys are fewer than the batch can hold. With 1000 ready keys and a 500-row
 * batch there is always breadth to fill the batch and a run adds nothing. With 8 ready keys the barrier can
 * return at most 8 rows per round — one per key — while a run of 64 returns 500. So the sweep is over
 * <b>key count</b>, with run length as the treatment, and the headline metric is <b>database round trips per
 * message</b> rather than wall clock: that is what §7 showed costs.
 *
 * <h2>Arms</h2>
 * <ul>
 *     <li>{@code BARRIER} — v1's ordered claim. Structurally one row per key per round, so it is the
 *     {@code runLength = 1} baseline whether or not that is asked for.</li>
 *     <li>{@code CURSOR_RUN} — the corrected cursor's run claim at each configured run length. Run length 1
 *     isolates the cursor's own claim cost from the run effect.</li>
 * </ul>
 * Ordered traffic only; run length is meaningless without keys.
 */
@Component
public class QueueOrderedRunLengthScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(QueueOrderedRunLengthScenario.class);

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final ObjectMapper                                                  objectMapper;

    public QueueOrderedRunLengthScenario(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                         ObjectMapper objectMapper) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "queue-ordered-run-length";
    }

    @Override
    public String description() {
        return "Sweeps ordered key cardinality against cursor run length, measuring round trips per message - the gate on the per-key cursor";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        var messages    = properties.getRunLengthMessages();
        var claimBatch  = properties.getRunLengthClaimBatchSize();
        var repetitions = properties.getRunLengthRepetitions();
        var keyCounts   = parseInts(properties.getRunLengthKeyCounts());
        var runLengths  = parseInts(properties.getRunLengthRunLengths());
        var runId       = Long.toHexString(System.nanoTime());

        log.info("queue-ordered-run-length: messages={}, claimBatch={}, repetitions={}, keyCounts={}, runLengths={}",
                 messages, claimBatch, repetitions, keyCounts, runLengths);

        var results = new ArrayList<CaseResult>();
        for (var repetition = 0; repetition < repetitions; repetition++) {
            for (var keyCount : keyCounts) {
                // The barrier is run once per key count: it has no run-length dimension to sweep.
                results.add(runCase(runId, Arm.BARRIER, 1, keyCount, messages, claimBatch, repetition));
                for (var runLength : runLengths) {
                    results.add(runCase(runId, Arm.CURSOR_RUN, runLength, keyCount, messages, claimBatch, repetition));
                }
            }
        }
        results.forEach(result -> log.info("queue-ordered-run-length {} keys={} runLength={} rep {} => claim {} ms, ack {} ms, "
                                                   + "total {} ms, rounds {}, roundsPerMessage {}",
                                           result.arm(), result.keyCount(), result.runLength(), result.repetition(),
                                           result.claimMillis(), result.ackMillis(), result.totalMillis(),
                                           result.rounds(), String.format("%.4f", result.roundsPerMessage())));

        var report = new LinkedHashMap<String, Object>();
        report.put("scenario", name());
        report.put("capturedAt", Instant.now().toString());
        report.put("messages", messages);
        report.put("claimBatchSize", claimBatch);
        report.put("cases", results);
        report.put("comparisons", buildComparisons(results, keyCounts, runLengths));

        var json = toJson(report);
        System.out.println("############# [perf-lab] queue-ordered-run-length: " + json);
        writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
    }

    private CaseResult runCase(String runId, Arm arm, int runLength, int keyCount, int messages, int claimBatch, int repetition) {
        // PostgreSQL truncates identifiers at 63 bytes and index names derive from this.
        var suffix        = runId + "_" + arm.ordinal() + runLength + "_" + keyCount + "_r" + repetition;
        var table         = "rl_" + suffix;
        var keyStateTable = "rk_" + suffix;
        var queueName     = "q_" + suffix;

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            // Both arms get the same schema, so the comparison is the claim statement and not the indexes. The
            // safe-cursor DDL is the superset, and the barrier simply does not use the key-state table.
            QueueSchemaPrototype.cursorSafeOrderedTableDdl(table, 100).forEach(statement -> unitOfWork.handle().execute(statement));
            QueueSchemaPrototype.cursorKeyStateDdl(keyStateTable).forEach(statement -> unitOfWork.handle().execute(statement));
        });

        var payload = "{\"filler\":\"" + "x".repeat(200) + "\"}";
        var now     = OffsetDateTime.now();
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var batch = unitOfWork.handle().prepareBatch(QueueSchemaPrototype.insertOrderedSql(table, true));
            for (var i = 0; i < messages; i++) {
                batch.bind("id", UUID.randomUUID().toString())
                     .bind("queueName", queueName)
                     .bind("payload", payload)
                     .bind("payloadType", "LabRunLengthItem")
                     .bind("now", now)
                     .bind("key", "key-" + (i % keyCount))
                     .bind("keyOrder", (long) (i / keyCount))
                     .add();
            }
            batch.execute();
        });
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                  .createUpdate(QueueSchemaPrototype.seedKeyStateSql(keyStateTable, table))
                                                                  .bind("queueName", queueName)
                                                                  .execute());

        var claimSql = arm == Arm.BARRIER
                       ? QueueSchemaPrototype.claimOrderedSql(table, false)
                       : QueueSchemaPrototype.claimOrderedRunViaSafeCursorSql(table, keyStateTable);
        var ackSql = arm == Arm.BARRIER
                     ? QueueSchemaPrototype.deleteBatchSql(table)
                     : QueueSchemaPrototype.ackOrderedViaSafeCursorSql(table, keyStateTable);

        var claimed    = 0;
        var claimNanos = 0L;
        var ackNanos   = 0L;
        // Every claim and every acknowledgement is one round trip in its own transaction. Counting them is the
        // point of the scenario: §7 established that the transaction, not the statement, is the cost.
        var rounds     = 0L;
        while (true) {
            var claimStart = System.nanoTime();
            var batchIds = unitOfWorkFactory.withUnitOfWork(unitOfWork -> {
                var query = unitOfWork.handle().createQuery(claimSql)
                                      .bind("queueName", queueName)
                                      .bind("now", OffsetDateTime.now())
                                      .bind("limit", claimBatch);
                if (arm == Arm.CURSOR_RUN) {
                    query.bind("runLength", runLength);
                }
                return query.mapTo(String.class).list();
            });
            claimNanos += System.nanoTime() - claimStart;
            rounds++;
            if (batchIds.isEmpty()) {
                break;
            }
            claimed += batchIds.size();

            var ackStart = System.nanoTime();
            unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                      .createUpdate(ackSql)
                                                                      .bindList("ids", batchIds)
                                                                      .execute());
            ackNanos += System.nanoTime() - ackStart;
            rounds++;
        }

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.handle().execute("DROP TABLE IF EXISTS " + table);
            unitOfWork.handle().execute("DROP TABLE IF EXISTS " + keyStateTable);
        });

        var claimMillis = Duration.ofNanos(claimNanos).toMillis();
        var ackMillis   = Duration.ofNanos(ackNanos).toMillis();
        return new CaseResult(arm.name(), runLength, keyCount, repetition, messages, claimed,
                              claimMillis, ackMillis, claimMillis + ackMillis,
                              rounds, claimed == 0 ? 0.0d : (double) rounds / claimed);
    }

    /**
     * For each key count, the cursor at each run length against the barrier. A ratio above 1 means the cursor
     * did less work; {@code roundTripReduction} is the one that matters, since wall clock at this scale is
     * dominated by whatever else the machine is doing.
     */
    private List<Map<String, Object>> buildComparisons(List<CaseResult> results, List<Integer> keyCounts, List<Integer> runLengths) {
        var comparisons = new ArrayList<Map<String, Object>>();
        for (var keyCount : keyCounts) {
            var barrier = median(results, Arm.BARRIER, 1, keyCount);
            if (barrier == null) {
                continue;
            }
            for (var runLength : runLengths) {
                var cursor = median(results, Arm.CURSOR_RUN, runLength, keyCount);
                if (cursor == null) {
                    continue;
                }
                var comparison = new LinkedHashMap<String, Object>();
                comparison.put("keyCount", keyCount);
                comparison.put("runLength", runLength);
                comparison.put("barrierRounds", barrier.rounds());
                comparison.put("cursorRounds", cursor.rounds());
                comparison.put("roundTripReduction", cursor.rounds() == 0 ? null : (double) barrier.rounds() / cursor.rounds());
                comparison.put("barrierTotalMillis", barrier.total());
                comparison.put("cursorTotalMillis", cursor.total());
                comparison.put("wallClockSpeedup", cursor.total() == 0 ? null : (double) barrier.total() / cursor.total());
                comparisons.add(comparison);
            }
        }
        return comparisons;
    }

    private static Medians median(List<CaseResult> results, Arm arm, int runLength, int keyCount) {
        var matching = results.stream()
                              .filter(r -> r.arm().equals(arm.name()) && r.runLength() == runLength && r.keyCount() == keyCount)
                              .toList();
        if (matching.isEmpty()) {
            return null;
        }
        return new Medians(medianOf(matching.stream().map(CaseResult::totalMillis).sorted().toList()),
                           medianOf(matching.stream().map(CaseResult::rounds).sorted().toList()));
    }

    private static long medianOf(List<Long> sorted) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        var middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2L;
    }

    private static List<Integer> parseInts(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(","))
                     .map(String::trim)
                     .filter(StringUtils::hasText)
                     .map(Integer::parseInt)
                     .toList();
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
        log.info("Wrote queue-ordered-run-length metrics to {}", target);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize queue-ordered-run-length metrics to JSON", e);
        }
    }

    public enum Arm {
        /**
         * v1's ordered claim. Its per-row barrier means one row per key per round regardless of the limit, so it
         * is inherently the run-length-1 baseline.
         */
        BARRIER,
        /**
         * The corrected cursor's run claim, swept over run length.
         */
        CURSOR_RUN
    }

    private record Medians(long total, long rounds) {
    }

    public record CaseResult(String arm,
                             int runLength,
                             int keyCount,
                             int repetition,
                             int messagesInserted,
                             int messagesClaimed,
                             long claimMillis,
                             long ackMillis,
                             long totalMillis,
                             long rounds,
                             double roundsPerMessage) {
    }
}
