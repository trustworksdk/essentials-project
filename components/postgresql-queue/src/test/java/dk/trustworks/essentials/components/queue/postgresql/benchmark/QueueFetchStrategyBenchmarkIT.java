/*
 *  Copyright 2021-2025 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.queue.postgresql.benchmark;

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Manual benchmark for identifying the queue-count sweet spot between:
 * - per-queue fetching ({@link PostgresqlDurableQueues#fetchNextBatchOfMessages(Collection, Map, Map)})
 * - batched fetching ({@link PostgresqlDurableQueues#fetchNextBatchOfMessagesBatched(Collection, Map, Map)})
 * <p>
 * This benchmark only runs when -Dbenchmark.run=true is provided.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
public class QueueFetchStrategyBenchmarkIT {

    @Container
    static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("test", "test", "test");

    private static final QueuedMessageHandler NO_OP_HANDLER = _msg -> {
    };

    @Test
    void benchmark_per_queue_vs_batched_fetching() throws IOException {
        assumeTrue(Boolean.parseBoolean(System.getProperty("benchmark.run", "false")),
                   "Skipping benchmark. Set -Dbenchmark.run=true to execute.");

        var config = BenchmarkConfig.fromSystemProperties();
        var rows = new ArrayList<ResultRow>();

        System.out.println("Benchmark started at " + Instant.now());
        System.out.println("Config: " + config);

        for (var queueCount : config.queueCounts()) {
            for (var messagesPerQueue : config.messagesPerQueue()) {
                for (var workerSlots : config.workerSlotsPerQueue()) {
                    for (var excludedKeys : config.excludedKeysPerQueue()) {
                        var scenario = new Scenario(queueCount, messagesPerQueue, workerSlots, excludedKeys);
                        System.out.println("Running scenario " + scenario.toLabel());

                        rows.add(runScenario(config, scenario));
                    }
                }
            }
        }

        var outputPath = config.outputCsvPath();
        writeCsv(outputPath, rows);
        System.out.println("Benchmark completed at " + Instant.now());
        System.out.println("CSV written to: " + outputPath.toAbsolutePath());
    }

    private ResultRow runScenario(BenchmarkConfig config, Scenario scenario) {
        var perQueueRuns = new ArrayList<RunResult>();
        var batchedRuns = new ArrayList<RunResult>();

        var totalIterations = config.warmupIterations() + config.measureIterations();

        try (var resources = createScenarioResources(scenario)) {
            for (int iteration = 0; iteration < totalIterations; iteration++) {
                seedScenario(resources.durableQueues, resources.queueNames, scenario.messagesPerQueue(), scenario.excludedKeysPerQueue());
                var result = measurePerQueue(resources, scenario.workerSlotsPerQueue(), scenario.excludedKeysPerQueue());
                if (iteration >= config.warmupIterations()) {
                    perQueueRuns.add(result);
                }
            }

            for (int iteration = 0; iteration < totalIterations; iteration++) {
                seedScenario(resources.durableQueues, resources.queueNames, scenario.messagesPerQueue(), scenario.excludedKeysPerQueue());
                var result = measureBatched(resources, scenario.workerSlotsPerQueue(), scenario.excludedKeysPerQueue());
                if (iteration >= config.warmupIterations()) {
                    batchedRuns.add(result);
                }
            }
        }

        return ResultRow.fromRuns(config, scenario, perQueueRuns, batchedRuns);
    }

    private BenchmarkResources createScenarioResources(Scenario scenario) {
        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                      postgreSQLContainer.getUsername(),
                                                                      postgreSQLContainer.getPassword()));

        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(unitOfWorkFactory)
                                                   .setUseCentralizedMessageFetcher(true)
                                                   .setUseOrderedUnorderedQuery(true)
                                                   .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(20))
                                                   .build();

        var queueNames = new ArrayList<QueueName>(scenario.queueCount());
        for (int i = 0; i < scenario.queueCount(); i++) {
            var queueName = QueueName.of("bench-q-" + i);
            queueNames.add(queueName);

            durableQueues.consumeFromQueue(
                    ConsumeFromQueue.builder()
                                    .setQueueName(queueName)
                                    .setConsumerName("bench-consumer-" + i)
                                    .setParallelConsumers(Math.max(1, scenario.workerSlotsPerQueue()))
                                    .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 0))
                                    .setPollingInterval(Duration.ofMillis(20))
                                    .setQueueMessageHandler(NO_OP_HANDLER)
                                    .build());
        }

        return new BenchmarkResources(durableQueues, queueNames);
    }

    private void seedScenario(PostgresqlDurableQueues durableQueues,
                              List<QueueName> queueNames,
                              int messagesPerQueue,
                              int excludedKeysPerQueue) {
        for (var queueName : queueNames) {
            durableQueues.purgeQueue(queueName);

            var messageCount = Math.max(1, messagesPerQueue);
            var distinctKeys = Math.max(1, excludedKeysPerQueue + 2);
            var messages = new ArrayList<Message>(messageCount);

            for (int i = 0; i < messageCount; i++) {
                if (i % 2 == 0) {
                    messages.add(Message.of("unordered-" + queueName + "-" + i));
                } else {
                    var key = "key-" + (i % distinctKeys);
                    messages.add(OrderedMessage.of("ordered-" + queueName + "-" + i, key, i));
                }
            }

            durableQueues.queueMessages(queueName, messages);
        }
    }

    private RunResult measurePerQueue(BenchmarkResources resources,
                                      int workerSlotsPerQueue,
                                      int excludedKeysPerQueue) {
        var queueNames = resources.queueNames;
        var exclude = buildExcludeKeys(queueNames, excludedKeysPerQueue);
        var availableSlots = buildAvailableSlots(queueNames, workerSlotsPerQueue);

        var start = System.nanoTime();
        var messages = resources.durableQueues.fetchNextBatchOfMessages(queueNames, exclude, availableSlots);
        var durationNanos = System.nanoTime() - start;

        var uniqueIds = messages.stream().map(QueuedMessage::getId).collect(Collectors.toSet()).size();
        var dedupCollisions = Math.max(0, messages.size() - uniqueIds);
        var dedupRatio = uniqueIds == 0 ? 1.0 : (double) messages.size() / uniqueIds;

        return new RunResult(durationNanos, messages.size(), uniqueIds, dedupCollisions, dedupRatio);
    }

    private RunResult measureBatched(BenchmarkResources resources,
                                     int workerSlotsPerQueue,
                                     int excludedKeysPerQueue) {
        var queueNames = resources.queueNames;
        var exclude = buildExcludeKeys(queueNames, excludedKeysPerQueue);
        var availableSlots = buildAvailableSlots(queueNames, workerSlotsPerQueue);

        var start = System.nanoTime();
        var messages = resources.durableQueues.fetchNextBatchOfMessagesBatched(queueNames, exclude, availableSlots);
        var durationNanos = System.nanoTime() - start;

        var uniqueIds = messages.stream().map(QueuedMessage::getId).collect(Collectors.toSet()).size();
        var dedupCollisions = Math.max(0, messages.size() - uniqueIds);
        var dedupRatio = uniqueIds == 0 ? 1.0 : (double) messages.size() / uniqueIds;

        return new RunResult(durationNanos, messages.size(), uniqueIds, dedupCollisions, dedupRatio);
    }

    private Map<QueueName, Set<String>> buildExcludeKeys(List<QueueName> queueNames,
                                                         int excludedKeysPerQueue) {
        var excludeKeys = new HashMap<QueueName, Set<String>>();
        if (excludedKeysPerQueue <= 0) {
            return excludeKeys;
        }

        for (var queueName : queueNames) {
            var keys = new HashSet<String>();
            for (int i = 0; i < excludedKeysPerQueue; i++) {
                keys.add("key-" + i);
            }
            excludeKeys.put(queueName, keys);
        }
        return excludeKeys;
    }

    private Map<QueueName, Integer> buildAvailableSlots(List<QueueName> queueNames,
                                                        int workerSlotsPerQueue) {
        var slots = new HashMap<QueueName, Integer>();
        for (var queueName : queueNames) {
            slots.put(queueName, workerSlotsPerQueue);
        }
        return slots;
    }

    private void writeCsv(Path outputPath,
                          List<ResultRow> rows) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        var header = String.join(",",
                                 "timestamp",
                                 "queue_count",
                                 "messages_per_queue",
                                 "worker_slots_per_queue",
                                 "excluded_keys_per_queue",
                                 "warmup_iterations",
                                 "measure_iterations",
                                 "per_queue_avg_ms",
                                 "per_queue_p95_ms",
                                 "per_queue_avg_rows",
                                 "batched_avg_ms",
                                 "batched_p95_ms",
                                 "batched_avg_rows",
                                 "batched_avg_unique_rows",
                                 "batched_avg_dedup_collisions",
                                 "batched_avg_dedup_ratio",
                                 "winner");

        var lines = new ArrayList<String>();
        lines.add(header);
        for (var row : rows) {
            lines.add(row.toCsvLine());
        }

        Files.write(outputPath, lines);
    }

    private record BenchmarkResources(PostgresqlDurableQueues durableQueues,
                                      List<QueueName> queueNames) implements AutoCloseable {

        @Override
        public void close() {
            durableQueues.stop();
        }
    }

    private record RunResult(long durationNanos,
                             int returnedRows,
                             int uniqueRows,
                             int dedupCollisions,
                             double dedupRatio) {
    }

    private record Scenario(int queueCount,
                            int messagesPerQueue,
                            int workerSlotsPerQueue,
                            int excludedKeysPerQueue) {
        String toLabel() {
            return "queues=" + queueCount
                    + ",messagesPerQueue=" + messagesPerQueue
                    + ",slots=" + workerSlotsPerQueue
                    + ",excludedKeys=" + excludedKeysPerQueue;
        }
    }

    private record BenchmarkConfig(List<Integer> queueCounts,
                                   List<Integer> messagesPerQueue,
                                   List<Integer> workerSlotsPerQueue,
                                   List<Integer> excludedKeysPerQueue,
                                   int warmupIterations,
                                   int measureIterations,
                                   Path outputCsvPath) {

        static BenchmarkConfig fromSystemProperties() {
            var queueCounts = parseIntList("benchmark.queueCounts", "1,2,4,8,16,32,64,128");
            var messagesPerQueue = parseIntList("benchmark.messagesPerQueue", "1,5,20");
            var workerSlots = parseIntList("benchmark.workerSlots", "1,4");
            var excludedKeys = parseIntList("benchmark.excludedKeys", "0,10");

            var warmupIterations = Integer.parseInt(System.getProperty("benchmark.warmupIterations", "3"));
            var measureIterations = Integer.parseInt(System.getProperty("benchmark.measureIterations", "8"));
            var outputCsvPath = Path.of(System.getProperty("benchmark.outputCsv",
                                                           "target/queue-fetch-strategy-benchmark.csv"));

            return new BenchmarkConfig(queueCounts,
                                       messagesPerQueue,
                                       workerSlots,
                                       excludedKeys,
                                       warmupIterations,
                                       measureIterations,
                                       outputCsvPath);
        }

        private static List<Integer> parseIntList(String propertyName,
                                                  String defaultValue) {
            var raw = System.getProperty(propertyName, defaultValue).trim();
            if (raw.isEmpty()) {
                throw new IllegalArgumentException(propertyName + " cannot be empty");
            }
            return Arrays.stream(raw.split(","))
                         .map(String::trim)
                         .filter(s -> !s.isEmpty())
                         .map(Integer::parseInt)
                         .toList();
        }
    }

    private record ResultRow(Instant timestamp,
                             Scenario scenario,
                             int warmupIterations,
                             int measureIterations,
                             double perQueueAvgMs,
                             double perQueueP95Ms,
                             double perQueueAvgRows,
                             double batchedAvgMs,
                             double batchedP95Ms,
                             double batchedAvgRows,
                             double batchedAvgUniqueRows,
                             double batchedAvgDedupCollisions,
                             double batchedAvgDedupRatio,
                             String winner) {

        static ResultRow fromRuns(BenchmarkConfig config,
                                  Scenario scenario,
                                  List<RunResult> perQueueRuns,
                                  List<RunResult> batchedRuns) {
            var perQueueAvgMs = nanosToMillis(avgLong(perQueueRuns.stream().mapToLong(RunResult::durationNanos).toArray()));
            var perQueueP95Ms = nanosToMillis(percentileLong(perQueueRuns.stream().mapToLong(RunResult::durationNanos).toArray(), 95));
            var perQueueAvgRows = avgInt(perQueueRuns.stream().mapToInt(RunResult::returnedRows).toArray());

            var batchedAvgMs = nanosToMillis(avgLong(batchedRuns.stream().mapToLong(RunResult::durationNanos).toArray()));
            var batchedP95Ms = nanosToMillis(percentileLong(batchedRuns.stream().mapToLong(RunResult::durationNanos).toArray(), 95));
            var batchedAvgRows = avgInt(batchedRuns.stream().mapToInt(RunResult::returnedRows).toArray());
            var batchedAvgUniqueRows = avgInt(batchedRuns.stream().mapToInt(RunResult::uniqueRows).toArray());
            var batchedAvgDedupCollisions = avgInt(batchedRuns.stream().mapToInt(RunResult::dedupCollisions).toArray());
            var batchedAvgDedupRatio = avgDouble(batchedRuns.stream().mapToDouble(RunResult::dedupRatio).toArray());

            var winner = batchedAvgMs < perQueueAvgMs ? "BATCHED" : "PER_QUEUE";

            return new ResultRow(Instant.now(),
                                 scenario,
                                 config.warmupIterations(),
                                 config.measureIterations(),
                                 perQueueAvgMs,
                                 perQueueP95Ms,
                                 perQueueAvgRows,
                                 batchedAvgMs,
                                 batchedP95Ms,
                                 batchedAvgRows,
                                 batchedAvgUniqueRows,
                                 batchedAvgDedupCollisions,
                                 batchedAvgDedupRatio,
                                 winner);
        }

        String toCsvLine() {
            return List.of(timestamp.toString(),
                           Integer.toString(scenario.queueCount()),
                           Integer.toString(scenario.messagesPerQueue()),
                           Integer.toString(scenario.workerSlotsPerQueue()),
                           Integer.toString(scenario.excludedKeysPerQueue()),
                           Integer.toString(warmupIterations),
                           Integer.toString(measureIterations),
                           fmt(perQueueAvgMs),
                           fmt(perQueueP95Ms),
                           fmt(perQueueAvgRows),
                           fmt(batchedAvgMs),
                           fmt(batchedP95Ms),
                           fmt(batchedAvgRows),
                           fmt(batchedAvgUniqueRows),
                           fmt(batchedAvgDedupCollisions),
                           fmt(batchedAvgDedupRatio),
                           winner)
                       .stream()
                       .collect(joining(","));
        }
    }

    private static long avgLong(long[] values) {
        if (values.length == 0) return 0;
        long sum = 0;
        for (var value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double avgInt(int[] values) {
        if (values.length == 0) return 0;
        double sum = 0;
        for (var value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double avgDouble(double[] values) {
        if (values.length == 0) return 0;
        double sum = 0;
        for (var value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static long percentileLong(long[] values,
                                       int percentile) {
        if (values.length == 0) return 0;
        var sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        int idx = (int) Math.ceil((percentile / 100.0) * sorted.length) - 1;
        idx = Math.max(0, Math.min(idx, sorted.length - 1));
        return sorted[idx];
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
