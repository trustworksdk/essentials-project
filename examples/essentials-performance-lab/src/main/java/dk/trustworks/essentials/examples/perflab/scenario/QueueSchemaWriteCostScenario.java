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
 * Answers the one question gating the DurableQueues v2 decision: <strong>is the index-maintenance win of
 * splitting ordered and unordered messages into separate tables real, and how large?</strong>
 * <p>
 * v1 keeps every message in one table carrying six secondary indexes, three of which exist solely for ordered
 * delivery. An unordered message therefore pays maintenance on all six at insert, at claim and at delete. A
 * split unordered table needs one. This scenario measures that difference directly, on raw SQL rather than
 * through {@code DurableQueues}, because routing it through the real component would bury the effect under
 * the per-message connection acquisition already known to dominate (see the measurements doc §2a).
 *
 * <h2>Consumer mode is the point, not an aside</h2>
 * A two-table design forces the consumer to declare {@code UNORDERED} or {@code ORDERED}, because those are
 * now physically different tables. The scenario mirrors that: each case runs a single-mode workload against
 * the table that mode would use. The v1 arm runs the same mode against the shared table, with the
 * {@code key IS NULL} / {@code key IS NOT NULL} predicate v1 needs to tell the two apart.
 *
 * <h2>Arms</h2>
 * <ul>
 *     <li>{@code V1_SHARED} — one table, six secondary indexes, {@code fillfactor=100} (exactly v1).</li>
 *     <li>{@code V1_SHARED_FILLFACTOR_80} — identical, but with page headroom. Included because if most of
 *     the gap closes here, the cheap fix is one {@code ALTER TABLE} rather than a second implementation.</li>
 *     <li>{@code V2_SPLIT} — the mode's own table: one secondary index for unordered, three for ordered.</li>
 * </ul>
 *
 * <h2>What is recorded</h2>
 * Wall-clock per phase (insert, claim, ack), plus PostgreSQL's own write accounting from
 * {@code pg_stat_user_tables} — including {@code n_tup_hot_upd}, which tests rather than assumes the
 * claim that these updates cannot be HOT — and final heap and index sizes.
 */
@Component
public class QueueSchemaWriteCostScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(QueueSchemaWriteCostScenario.class);

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final ObjectMapper                                                  objectMapper;

    public QueueSchemaWriteCostScenario(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                        ObjectMapper objectMapper) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "queue-schema-write-cost";
    }

    @Override
    public String description() {
        return "Measures insert/claim/ack cost and index maintenance for v1's shared six-index table versus a per-mode split table";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        var messages    = properties.getSchemaWriteCostMessages();
        var claimBatch  = properties.getSchemaWriteCostClaimBatchSize();
        var repetitions = properties.getSchemaWriteCostRepetitions();
        var keyCount    = properties.getSchemaWriteCostOrderedKeyCount();
        var runId       = Long.toHexString(System.nanoTime());

        log.info("queue-schema-write-cost: messages={}, claimBatch={}, repetitions={}, orderedKeys={}",
                 messages, claimBatch, repetitions, keyCount);

        var results = new ArrayList<CaseResult>();
        for (var mode : ConsumerMode.values()) {
            for (var repetition = 0; repetition < repetitions; repetition++) {
                for (var arm : Arm.values()) {
                    if (arm == Arm.V2_CURSOR && mode == ConsumerMode.UNORDERED) {
                        // A per-key cursor has nothing to track without keys.
                        continue;
                    }
                    var result = runCase(runId, arm, mode, messages, claimBatch, keyCount, repetition);
                    results.add(result);
                    log.info("queue-schema-write-cost {} rep {} => insert {} ms, claim {} ms, ack {} ms, indexBytes {}",
                             result.caseId(), repetition, result.insertMillis(), result.claimMillis(), result.ackMillis(), result.indexBytes());
                }
            }
        }

        var report = new LinkedHashMap<String, Object>();
        report.put("scenario", name());
        report.put("capturedAt", Instant.now().toString());
        report.put("messages", messages);
        report.put("claimBatchSize", claimBatch);
        report.put("orderedKeyCount", keyCount);
        report.put("cases", results);
        report.put("comparisons", buildComparisons(results));

        var json = toJson(report);
        System.out.println("############# [perf-lab] queue-schema-write-cost: " + json);
        writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
    }

    private CaseResult runCase(String runId,
                               Arm arm,
                               ConsumerMode mode,
                               int messages,
                               int claimBatch,
                               int keyCount,
                               int repetition) {
        // Short by necessity, not by preference: PostgreSQL truncates identifiers at 63 bytes, and the index
        // names derived from this collided once the table name carried full arm and mode words.
        var suffix = runId + "_" + arm.ordinal() + mode.ordinal() + "_r" + repetition;
        var table  = "qs_" + suffix;
        var caseId = arm + "/" + mode;
        var queueName = "q_" + suffix;

        var keyStateTable = "ks_" + suffix;
        var ddl = switch (arm) {
            case V1_SHARED -> QueueSchemaPrototype.v1SingleTableDdl(table, 100);
            case V1_SHARED_FILLFACTOR_80 -> QueueSchemaPrototype.v1SingleTableDdl(table, 80);
            case V2_SPLIT -> mode == ConsumerMode.UNORDERED
                    ? QueueSchemaPrototype.v2UnorderedTableDdl(table, 100)
                    : QueueSchemaPrototype.v2OrderedTableDdl(table, 100);
            case V2_CURSOR -> {
                var statements = new ArrayList<String>(QueueSchemaPrototype.cursorOrderedTableDdl(table, 100));
                statements.addAll(QueueSchemaPrototype.cursorKeyStateDdl(keyStateTable));
                yield statements;
            }
        };
        // Only the shared table needs to tell the two delivery modes apart.
        var sharedTable = arm == Arm.V1_SHARED || arm == Arm.V1_SHARED_FILLFACTOR_80;

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> ddl.forEach(statement -> unitOfWork.handle().execute(statement)));

        var payload     = "{\"sequence\":0,\"filler\":\"" + "x".repeat(200) + "\"}";
        var payloadType = "dk.trustworks.essentials.examples.perflab.LabWriteCostItem";
        var now         = OffsetDateTime.now();

        // ---- insert ----
        var insertSql = mode == ConsumerMode.UNORDERED
                ? QueueSchemaPrototype.insertUnorderedSql(table)
                : QueueSchemaPrototype.insertOrderedSql(table, true);
        var insertStart = System.nanoTime();
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var batch = unitOfWork.handle().prepareBatch(insertSql);
            for (var i = 0; i < messages; i++) {
                batch.bind("id", UUID.randomUUID().toString())
                     .bind("queueName", queueName)
                     .bind("payload", payload)
                     .bind("payloadType", payloadType)
                     .bind("now", now);
                if (mode == ConsumerMode.ORDERED) {
                    batch.bind("key", "key-" + (i % keyCount))
                         .bind("keyOrder", (long) (i / keyCount));
                }
                batch.add();
            }
            batch.execute();
        });
        var insertMillis = millisSince(insertStart);

        if (arm == Arm.V2_CURSOR) {
            // One cursor row per key. A real implementation would create it on first enqueue for the key;
            // seeding it here keeps that cost out of the insert timing, which is not what this arm is about.
            unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                       .createUpdate(QueueSchemaPrototype.seedKeyStateSql(keyStateTable, table))
                                                                       .bind("queueName", queueName)
                                                                       .execute());
        }

        // ---- claim and acknowledge, interleaved ----
        // Interleaved rather than claim-everything-then-ack-everything, because ordered delivery makes only
        // the head message per key claimable: a claim-all pass yields one row per key and then returns empty
        // until those are acknowledged. Draining in claim/ack rounds is both the only way the ordered arm can
        // finish and a truer model of how a consumer actually behaves. The two phases keep separate
        // accumulating timers so their costs stay attributable.
        var claimSql = switch (arm) {
            case V2_CURSOR -> QueueSchemaPrototype.claimOrderedViaCursorSql(table, keyStateTable);
            default -> mode == ConsumerMode.UNORDERED
                    ? QueueSchemaPrototype.claimUnorderedSql(table, sharedTable)
                    : QueueSchemaPrototype.claimOrderedSql(table, sharedTable);
        };
        // The cursor arm's acknowledgement deletes the rows and advances the affected cursors in one
        // statement - the operation the barrier design cannot express at all.
        var deleteSql = arm == Arm.V2_CURSOR
                ? QueueSchemaPrototype.ackOrderedViaCursorSql(table, keyStateTable)
                : QueueSchemaPrototype.deleteBatchSql(table);
        var claimedRows = 0;
        var claimNanos  = 0L;
        var ackNanos    = 0L;
        while (true) {
            var claimRoundStart = System.nanoTime();
            var batchIds = unitOfWorkFactory.withUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                                     .createQuery(claimSql)
                                                                                     .bind("queueName", queueName)
                                                                                     .bind("now", OffsetDateTime.now())
                                                                                     .bind("limit", claimBatch)
                                                                                     .mapTo(String.class)
                                                                                     .list());
            claimNanos += System.nanoTime() - claimRoundStart;
            if (batchIds.isEmpty()) {
                break;
            }
            claimedRows += batchIds.size();

            var ackRoundStart = System.nanoTime();
            unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                       .createUpdate(deleteSql)
                                                                       .bindList("ids", batchIds)
                                                                       .execute());
            ackNanos += System.nanoTime() - ackRoundStart;
        }
        var claimMillis = Duration.ofNanos(claimNanos).toMillis();
        var ackMillis   = Duration.ofNanos(ackNanos).toMillis();
        var claimed     = claimedRows;

        var stats = readTableStats(table);

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.handle().execute("DROP TABLE IF EXISTS " + table);
            unitOfWork.handle().execute("DROP TABLE IF EXISTS " + keyStateTable);
        });

        return new CaseResult(caseId,
                              arm.name(),
                              mode.name(),
                              repetition,
                              messages,
                              claimed,
                              insertMillis,
                              claimMillis,
                              ackMillis,
                              insertMillis + claimMillis + ackMillis,
                              stats.indexCount(),
                              stats.heapBytes(),
                              stats.indexBytes(),
                              stats.updates(),
                              stats.hotUpdates());
    }

    /**
     * Reads PostgreSQL's own write accounting before the table is dropped. {@code n_tup_hot_upd} is the
     * interesting one: it tests the claim that a claim-statement update cannot be HOT, rather than assuming
     * it. Index bytes exclude the primary key so the comparison is of the secondary indexes actually under
     * discussion.
     */
    private TableStats readTableStats(String table) {
        return unitOfWorkFactory.withUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                         .createQuery("""
                                                                                      SELECT
                                                                                        (SELECT count(*) FROM pg_index WHERE indrelid = c.oid AND NOT indisprimary) AS index_count,
                                                                                        pg_table_size(c.oid)                                                        AS heap_bytes,
                                                                                        pg_indexes_size(c.oid)                                                       AS index_bytes,
                                                                                        COALESCE(s.n_tup_upd, 0)                                                     AS updates,
                                                                                        COALESCE(s.n_tup_hot_upd, 0)                                                 AS hot_updates
                                                                                      FROM pg_class c
                                                                                      LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
                                                                                      WHERE c.relname = :table
                                                                                      """)
                                                                         .bind("table", table)
                                                                         .map((rs, ctx) -> new TableStats(rs.getInt("index_count"),
                                                                                                          rs.getLong("heap_bytes"),
                                                                                                          rs.getLong("index_bytes"),
                                                                                                          rs.getLong("updates"),
                                                                                                          rs.getLong("hot_updates")))
                                                                         .one());
    }

    /**
     * Pairs each v1 arm against the V2_SPLIT arm for the same consumer mode, reducing repetitions to medians.
     * A ratio above 1 means v1 spent more time, i.e. the split is faster.
     */
    private List<Map<String, Object>> buildComparisons(List<CaseResult> results) {
        var comparisons = new ArrayList<Map<String, Object>>();
        for (var mode : ConsumerMode.values()) {
            var cursor = medianOf(results, Arm.V2_CURSOR, mode);
            if (cursor != null) {
                for (var arm : List.of(Arm.V1_SHARED, Arm.V2_SPLIT)) {
                    var baseline = medianOf(results, arm, mode);
                    if (baseline == null) continue;
                    var comparison = new LinkedHashMap<String, Object>();
                    comparison.put("mode", mode.name());
                    comparison.put("baselineArm", arm.name());
                    comparison.put("candidateArm", Arm.V2_CURSOR.name());
                    comparison.put("baselineClaimMillis", baseline.claim());
                    comparison.put("cursorClaimMillis", cursor.claim());
                    comparison.put("cursorClaimSpeedup", cursor.claim() == 0 ? null : (double) baseline.claim() / cursor.claim());
                    comparison.put("baselineAckMillis", baseline.ack());
                    comparison.put("cursorAckMillis", cursor.ack());
                    comparison.put("baselineTotalMillis", baseline.total());
                    comparison.put("cursorTotalMillis", cursor.total());
                    comparison.put("cursorTotalSpeedup", cursor.total() == 0 ? null : (double) baseline.total() / cursor.total());
                    comparisons.add(comparison);
                }
            }
            var split = medianOf(results, Arm.V2_SPLIT, mode);
            if (split == null) continue;
            for (var arm : List.of(Arm.V1_SHARED, Arm.V1_SHARED_FILLFACTOR_80)) {
                var baseline = medianOf(results, arm, mode);
                if (baseline == null) continue;
                var comparison = new LinkedHashMap<String, Object>();
                comparison.put("mode", mode.name());
                comparison.put("baselineArm", arm.name());
                comparison.put("baselineTotalMillis", baseline.total());
                comparison.put("splitTotalMillis", split.total());
                comparison.put("splitSpeedup", split.total() == 0 ? null : (double) baseline.total() / split.total());
                comparison.put("baselineInsertMillis", baseline.insert());
                comparison.put("splitInsertMillis", split.insert());
                comparison.put("baselineClaimMillis", baseline.claim());
                comparison.put("splitClaimMillis", split.claim());
                comparison.put("baselineAckMillis", baseline.ack());
                comparison.put("splitAckMillis", split.ack());
                comparison.put("baselineIndexBytes", baseline.indexBytes());
                comparison.put("splitIndexBytes", split.indexBytes());
                comparisons.add(comparison);
            }
        }
        return comparisons;
    }

    private static Medians medianOf(List<CaseResult> results, Arm arm, ConsumerMode mode) {
        var matching = results.stream()
                              .filter(result -> result.arm().equals(arm.name()) && result.mode().equals(mode.name()))
                              .toList();
        if (matching.isEmpty()) {
            return null;
        }
        return new Medians(median(matching.stream().map(CaseResult::insertMillis).sorted().toList()),
                           median(matching.stream().map(CaseResult::claimMillis).sorted().toList()),
                           median(matching.stream().map(CaseResult::ackMillis).sorted().toList()),
                           median(matching.stream().map(CaseResult::totalMillis).sorted().toList()),
                           median(matching.stream().map(CaseResult::indexBytes).sorted().toList()));
    }

    private static long median(List<Long> sorted) {
        if (sorted.isEmpty()) return 0L;
        var middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2L;
    }

    private static long millisSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private void writeMetricsIfConfigured(String metricsOutputFile, String json) throws IOException {
        if (!StringUtils.hasText(metricsOutputFile)) return;
        var target = Paths.get(metricsOutputFile).toAbsolutePath().normalize();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, json + System.lineSeparator(),
                          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        log.info("Wrote queue-schema-write-cost metrics to {}", target);
        System.out.println("############# [perf-lab] queue-schema-write-cost metrics file: " + target);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize queue-schema-write-cost metrics to JSON", e);
        }
    }

    /**
     * The choice a two-table design forces on the consumer, and therefore the dimension this scenario sweeps.
     */
    public enum ConsumerMode {
        UNORDERED,
        ORDERED
    }

    public enum Arm {
        V1_SHARED,
        V1_SHARED_FILLFACTOR_80,
        V2_SPLIT,
        /**
         * Ordered messages only: the split ordered table with its per-key barrier replaced by an explicit
         * progress cursor. Skipped for UNORDERED, where a per-key cursor has nothing to track.
         */
        V2_CURSOR
    }

    private record TableStats(int indexCount, long heapBytes, long indexBytes, long updates, long hotUpdates) {
    }

    private record Medians(long insert, long claim, long ack, long total, long indexBytes) {
    }

    public record CaseResult(String caseId,
                             String arm,
                             String mode,
                             int repetition,
                             int messagesInserted,
                             int messagesClaimed,
                             long insertMillis,
                             long claimMillis,
                             long ackMillis,
                             long totalMillis,
                             int secondaryIndexCount,
                             long heapBytes,
                             long indexBytes,
                             long updates,
                             long hotUpdates) {
    }
}
