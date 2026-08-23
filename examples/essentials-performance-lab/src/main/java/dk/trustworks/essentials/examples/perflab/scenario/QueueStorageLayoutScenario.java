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
 * The last two unmeasured items on the storage track: <strong>does moving dead-letter messages to their own
 * table pay, and does partitioning by {@code queue_name} pay?</strong>
 *
 * <h2>Why these two together</h2>
 * Two levers have measured as significant across this whole investigation: transaction count per message (§7,
 * 16.5× on acknowledgement alone) and index write amplification (the ordered/unordered split's 1.38×, the
 * cursor's index-bytes win). Both arms here attack the second, and they are the only parts of the storage track
 * still resting on argument rather than evidence.
 *
 * <h2>Arms</h2>
 * <ul>
 *     <li>{@code V1_SHARED} — one table, six secondary indexes, dead letters inline, unpartitioned. Exactly v1.</li>
 *     <li>{@code DLQ_SPLIT} — {@code is_dead_letter_message} removed from the hot table entirely, so it appears
 *     in no index and no predicate, with dead letters moved to a side table.</li>
 *     <li>{@code PARTITIONED} — v1's shape partitioned by {@code queue_name}.</li>
 * </ul>
 *
 * <h2>What is being watched, and why it may sink partitioning</h2>
 * PostgreSQL requires the partition key in every unique constraint, so the primary key becomes
 * {@code (id, queue_name)}. The whole {@code DurableQueues} API is keyed by {@code QueueEntryId} <em>alone</em> —
 * {@code acknowledgeMessageAsHandled}, {@code deleteMessage}, {@code getQueuedMessage},
 * {@code markAsDeadLetterMessage}, {@code retryMessage} — so none of them can name a partition and every one
 * degrades from a primary-key lookup to a probe of every partition. Acknowledgement by id is the hot path §7
 * measured at 16.5×.
 * <p>
 * So <b>{@code ackByIdMillis} is the number that decides partitioning</b>, not the purge time. Partitioning can
 * win decisively on purge — {@code TRUNCATE} of a partition against
 * {@code DELETE FROM … WHERE queue_name = :queueName} over every row of a queue — and still be the wrong choice
 * if it taxes every acknowledgement.
 * <p>
 * Multiple queues throughout, because a single-queue run would give partitioning one partition and measure
 * nothing. Unordered messages only: both questions are independent of delivery mode, and unordered isolates
 * them from the ordered barrier's cost.
 */
@Component
public class QueueStorageLayoutScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(QueueStorageLayoutScenario.class);

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final ObjectMapper                                                  objectMapper;

    public QueueStorageLayoutScenario(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                      ObjectMapper objectMapper) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "queue-storage-layout";
    }

    @Override
    public String description() {
        return "Measures the dead-letter side table and queue_name partitioning against v1's shared table, including the by-id acknowledgement partitioning threatens";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        var messages    = properties.getStorageLayoutMessages();
        var queueCount  = properties.getStorageLayoutQueueCount();
        var dlqPercent  = properties.getStorageLayoutDeadLetterPercent();
        var repetitions = properties.getStorageLayoutRepetitions();
        var runId       = Long.toHexString(System.nanoTime());

        log.info("queue-storage-layout: messages={}, queues={}, deadLetterPercent={}, repetitions={}",
                 messages, queueCount, dlqPercent, repetitions);

        var results = new ArrayList<CaseResult>();
        for (var repetition = 0; repetition < repetitions; repetition++) {
            for (var arm : Arm.values()) {
                var result = runCase(runId, arm, messages, queueCount, dlqPercent, repetition);
                results.add(result);
                log.info("queue-storage-layout {} rep {} => insert {} ms, claim {} ms, ackById {} ms, deadLetter {} ms, "
                                 + "purge {} ms, indexBytes {}, heapBytes {}",
                         result.arm(), repetition, result.insertMillis(), result.claimMillis(), result.ackByIdMillis(),
                         result.deadLetterMillis(), result.purgeMillis(), result.indexBytes(), result.heapBytes());
            }
        }

        var report = new LinkedHashMap<String, Object>();
        report.put("scenario", name());
        report.put("capturedAt", Instant.now().toString());
        report.put("messages", messages);
        report.put("queueCount", queueCount);
        report.put("deadLetterPercent", dlqPercent);
        report.put("cases", results);
        report.put("comparisons", buildComparisons(results));

        var json = toJson(report);
        System.out.println("############# [perf-lab] queue-storage-layout: " + json);
        writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
    }

    private CaseResult runCase(String runId, Arm arm, int messages, int queueCount, int dlqPercent, int repetition) {
        var suffix    = runId + "_" + arm.ordinal() + "_r" + repetition;
        var table     = "sl_" + suffix;
        var dlqTable  = "sd_" + suffix;
        var queueNames = new ArrayList<String>();
        for (var q = 0; q < queueCount; q++) {
            queueNames.add("q" + q + "_" + suffix);
        }

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            switch (arm) {
                case V1_SHARED -> QueueSchemaPrototype.v1SingleTableDdl(table, 100).forEach(s -> unitOfWork.handle().execute(s));
                case DLQ_SPLIT -> {
                    QueueSchemaPrototype.dlqSplitHotTableDdl(table, 100).forEach(s -> unitOfWork.handle().execute(s));
                    QueueSchemaPrototype.dlqSideTableDdl(dlqTable).forEach(s -> unitOfWork.handle().execute(s));
                }
                case PARTITIONED -> QueueSchemaPrototype.v1PartitionedByQueueDdl(table, queueNames, 100)
                                                        .forEach(s -> unitOfWork.handle().execute(s));
            }
        });

        var payload = "{\"filler\":\"" + "x".repeat(200) + "\"}";
        var now     = OffsetDateTime.now();
        var ids     = new ArrayList<String>(messages);

        var insertStart = System.nanoTime();
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var batch = unitOfWork.handle().prepareBatch(QueueSchemaPrototype.insertUnorderedSql(table));
            for (var i = 0; i < messages; i++) {
                var id = UUID.randomUUID().toString();
                ids.add(id);
                batch.bind("id", id)
                     .bind("queueName", queueNames.get(i % queueCount))
                     .bind("payload", payload)
                     .bind("payloadType", "LabStorageLayoutItem")
                     .bind("now", now)
                     .add();
            }
            batch.execute();
        });
        var insertMillis = millisSince(insertStart);

        // Dead-lettering, which is a flag flip in v1 and a move in the split. Both timed, because turning it
        // into a move is a real cost the split has to justify.
        var deadLetterCount = messages * dlqPercent / 100;
        var deadLetterIds   = ids.subList(0, deadLetterCount);
        var deadLetterStart = System.nanoTime();
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            for (var id : deadLetterIds) {
                if (arm == Arm.DLQ_SPLIT) {
                    unitOfWork.handle().createUpdate(QueueSchemaPrototype.moveToDlqSql(table, dlqTable)).bind("id", id).execute();
                } else {
                    unitOfWork.handle().createUpdate("UPDATE " + table + " SET is_dead_letter_message = TRUE WHERE id = :id")
                              .bind("id", id).execute();
                }
            }
        });
        var deadLetterMillis = millisSince(deadLetterStart);

        // Claim across every queue, in rounds, the way a fetcher would.
        var claimSql   = arm == Arm.DLQ_SPLIT
                         ? "WITH ready AS (SELECT id FROM " + table + " WHERE queue_name = :queueName AND is_being_delivered = FALSE"
                                 + " AND next_delivery_ts <= :now AND key IS NULL ORDER BY next_delivery_ts LIMIT :limit FOR UPDATE SKIP LOCKED)"
                                 + " UPDATE " + table + " q SET total_attempts = q.total_attempts + 1, next_delivery_ts = NULL,"
                                 + " is_being_delivered = TRUE, delivery_ts = :now FROM ready r WHERE q.id = r.id RETURNING q.id"
                         : QueueSchemaPrototype.claimUnorderedSql(table, true);
        var claimNanos = 0L;
        var ackNanos   = 0L;
        var claimed    = 0;
        for (var queueName : queueNames) {
            while (true) {
                var claimStart = System.nanoTime();
                var batchIds = unitOfWorkFactory.withUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                                        .createQuery(claimSql)
                                                                                        .bind("queueName", queueName)
                                                                                        .bind("now", OffsetDateTime.now())
                                                                                        .bind("limit", 500)
                                                                                        .mapTo(String.class)
                                                                                        .list());
                claimNanos += System.nanoTime() - claimStart;
                if (batchIds.isEmpty()) {
                    break;
                }
                claimed += batchIds.size();

                // Acknowledged one at a time by id ON PURPOSE. This is the operation partitioning threatens,
                // because the API has no queue name to give it, and it is the hot path §7 measured at 16.5x.
                // Batching it here would hide exactly the effect the arm exists to expose.
                var ackStart = System.nanoTime();
                unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
                    for (var id : batchIds) {
                        unitOfWork.handle().createUpdate(QueueSchemaPrototype.deleteByIdSql(table)).bind("id", id).execute();
                    }
                });
                ackNanos += System.nanoTime() - ackStart;
            }
        }

        var stats = readSizes(table);

        // Purge one queue: TRUNCATE of a partition against DELETE over every row of the queue.
        var purgeQueue = queueNames.getFirst();
        var purgeStart = System.nanoTime();
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            if (arm == Arm.PARTITIONED) {
                unitOfWork.handle().execute("TRUNCATE TABLE " + table + "_p0");
            } else {
                unitOfWork.handle().createUpdate("DELETE FROM " + table + " WHERE queue_name = :queueName")
                          .bind("queueName", purgeQueue).execute();
            }
        });
        var purgeMillis = millisSince(purgeStart);

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.handle().execute("DROP TABLE IF EXISTS " + table + " CASCADE");
            unitOfWork.handle().execute("DROP TABLE IF EXISTS " + dlqTable);
        });

        return new CaseResult(arm.name(), repetition, messages, claimed, deadLetterCount,
                              insertMillis, Duration.ofNanos(claimNanos).toMillis(), Duration.ofNanos(ackNanos).toMillis(),
                              deadLetterMillis, purgeMillis, stats.indexBytes(), stats.heapBytes(), stats.indexCount());
    }

    /**
     * Sizes are read before the purge, so they describe the loaded table rather than a partly emptied one.
     * {@code pg_indexes_size} on a partitioned table aggregates its partitions.
     */
    private Sizes readSizes(String table) {
        return unitOfWorkFactory.withUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                        .createQuery("""
                                                                                     SELECT
                                                                                       (SELECT count(*) FROM pg_index WHERE indrelid = c.oid AND NOT indisprimary) AS index_count,
                                                                                       pg_table_size(c.oid)    AS heap_bytes,
                                                                                       pg_indexes_size(c.oid)  AS index_bytes
                                                                                     FROM pg_class c WHERE c.relname = :table
                                                                                     """)
                                                                        .bind("table", table)
                                                                        .map((rs, ctx) -> new Sizes(rs.getInt("index_count"),
                                                                                                    rs.getLong("heap_bytes"),
                                                                                                    rs.getLong("index_bytes")))
                                                                        .one());
    }

    private List<Map<String, Object>> buildComparisons(List<CaseResult> results) {
        var comparisons = new ArrayList<Map<String, Object>>();
        var baseline    = median(results, Arm.V1_SHARED);
        if (baseline == null) {
            return comparisons;
        }
        for (var arm : List.of(Arm.DLQ_SPLIT, Arm.PARTITIONED)) {
            var candidate = median(results, arm);
            if (candidate == null) {
                continue;
            }
            var comparison = new LinkedHashMap<String, Object>();
            comparison.put("arm", arm.name());
            comparison.put("insertSpeedup", ratio(baseline.insert(), candidate.insert()));
            comparison.put("claimSpeedup", ratio(baseline.claim(), candidate.claim()));
            // The decisive one for partitioning.
            comparison.put("ackByIdSpeedup", ratio(baseline.ackById(), candidate.ackById()));
            comparison.put("deadLetterSpeedup", ratio(baseline.deadLetter(), candidate.deadLetter()));
            comparison.put("purgeSpeedup", ratio(baseline.purge(), candidate.purge()));
            comparison.put("baselineIndexBytes", baseline.indexBytes());
            comparison.put("candidateIndexBytes", candidate.indexBytes());
            comparison.put("baselineAckByIdMillis", baseline.ackById());
            comparison.put("candidateAckByIdMillis", candidate.ackById());
            comparisons.add(comparison);
        }
        return comparisons;
    }

    private static Double ratio(long baseline, long candidate) {
        return candidate == 0 ? null : (double) baseline / candidate;
    }

    private static Medians median(List<CaseResult> results, Arm arm) {
        var matching = results.stream().filter(r -> r.arm().equals(arm.name())).toList();
        if (matching.isEmpty()) {
            return null;
        }
        return new Medians(medianOf(matching.stream().map(CaseResult::insertMillis).sorted().toList()),
                           medianOf(matching.stream().map(CaseResult::claimMillis).sorted().toList()),
                           medianOf(matching.stream().map(CaseResult::ackByIdMillis).sorted().toList()),
                           medianOf(matching.stream().map(CaseResult::deadLetterMillis).sorted().toList()),
                           medianOf(matching.stream().map(CaseResult::purgeMillis).sorted().toList()),
                           medianOf(matching.stream().map(CaseResult::indexBytes).sorted().toList()));
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
        log.info("Wrote queue-storage-layout metrics to {}", target);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize queue-storage-layout metrics to JSON", e);
        }
    }

    public enum Arm {
        V1_SHARED,
        DLQ_SPLIT,
        PARTITIONED
    }

    private record Sizes(int indexCount, long heapBytes, long indexBytes) {
    }

    private record Medians(long insert, long claim, long ackById, long deadLetter, long purge, long indexBytes) {
    }

    public record CaseResult(String arm,
                             int repetition,
                             int messagesInserted,
                             int messagesClaimed,
                             int messagesDeadLettered,
                             long insertMillis,
                             long claimMillis,
                             long ackByIdMillis,
                             long deadLetterMillis,
                             long purgeMillis,
                             long indexBytes,
                             long heapBytes,
                             int secondaryIndexCount) {
    }
}
