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
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import dk.trustworks.essentials.examples.perflab.queuedesign.BatchingAcknowledgeInterceptor;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Measures the two queue-design levers that were identified as candidates before committing to a new
 * {@code DurableQueues} implementation:
 *
 * <ol>
 *     <li><strong>Ack batching</strong> — every handled message currently issues its own
 *     {@code DELETE ... WHERE id = :id} in its own transaction. Because the batch fetch amortises across a
 *     whole fetcher tick, that delete is the per-message commit. The {@code BATCHED} arm defers and groups
 *     it via {@link BatchingAcknowledgeInterceptor}.</li>
 *     <li><strong>Ordered-message mix</strong> — the ordered fetch path carries a per-key barrier that the
 *     unordered path does not. Sweeping the ordered fraction shows what that barrier costs, which is the
 *     thing a two-table split would be buying.</li>
 * </ol>
 *
 * The {@code useOrderedUnorderedQuery} flag is a third dimension, but it is fixed at
 * {@code PostgresqlDurableQueues} construction, so it is an outer-loop parameter: set it per JVM run and it
 * is recorded in the output as {@code useOrderedUnorderedQuery}.
 *
 * <h2>Method</h2>
 * Identical to {@code VirtualThreadsQueueScenario}: a fixed burst is queued up front, the consumer starts,
 * the queue drains, and drain time, throughput and latency percentiles are recorded. Arms alternate inside
 * each repetition rather than running in blocks, and results are reduced to medians with the observed range
 * reported, because a single sample of this workload has a spread wide enough to invert a comparison.
 *
 * <h2>Ordered messages and parallelism</h2>
 * Ordered messages are spread over {@code orderedKeyCount} keys. The per-key barrier means at most one
 * message per key is in flight at a time, so the effective concurrency of the ordered portion is capped by
 * the key count, not by {@code parallelConsumers} — the key count therefore has to be at least as large as
 * the parallelism or the ordered arm is measuring key contention rather than query cost.
 */
@Component
public class QueueDesignAbScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(QueueDesignAbScenario.class);

    private static final Duration DRAIN_TIMEOUT       = Duration.ofMinutes(5);
    private static final Duration DRAIN_POLL_INTERVAL = Duration.ofMillis(25);

    private final DurableQueues                                                 durableQueues;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final BatchingAcknowledgeInterceptor                                batchingAcknowledgeInterceptor;
    private final DataSource                                                    dataSource;
    private final ObjectMapper                                                  objectMapper;

    public QueueDesignAbScenario(DurableQueues durableQueues,
                                 HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                 BatchingAcknowledgeInterceptor batchingAcknowledgeInterceptor,
                                 DataSource dataSource,
                                 ObjectMapper objectMapper) {
        this.durableQueues = durableQueues;
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.batchingAcknowledgeInterceptor = batchingAcknowledgeInterceptor;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "queue-design-ab";
    }

    @Override
    public String description() {
        return "A/Bs batched vs per-message acknowledgement across a sweep of ordered-message fractions, to size the "
                + "two levers a DurableQueues redesign would pull";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        var orderedFractions = parseFractions(properties.getQueueDesignOrderedFractions());
        var parallelConsumers = properties.getQueueDesignParallelConsumers();
        var messagesPerCase  = properties.getQueueDesignMessagesPerCase();
        var repetitions      = properties.getQueueDesignRepetitions();
        var flushInterval    = properties.getQueueDesignAckFlushInterval();
        var maxBatchSize     = properties.getQueueDesignAckMaxBatchSize();
        var orderedKeyCount  = Math.max(parallelConsumers, properties.getQueueDesignOrderedKeyCount());
        var runId            = Long.toHexString(System.nanoTime());

        log.info("queue-design-ab: orderedFractions={}, parallelConsumers={}, messagesPerCase={}, repetitions={}, orderedKeyCount={}",
                 orderedFractions, parallelConsumers, messagesPerCase, repetitions, orderedKeyCount);

        var results = new ArrayList<CaseResult>();
        for (var orderedFraction : orderedFractions) {
            for (var repetition = 0; repetition < repetitions; repetition++) {
                for (var ackMode : AckMode.values()) {
                    var result = runCase(runId, orderedFraction, orderedKeyCount, ackMode, parallelConsumers,
                                         messagesPerCase, flushInterval, maxBatchSize, repetition, results.isEmpty());
                    results.add(result);
                    log.info("queue-design-ab case {} rep {} => {} msg/s, drain {} ms, ackFlushes {}",
                             result.caseId(), repetition, String.format("%.1f", result.throughputMsgPerSecond()),
                             result.drainMillis(), result.ackFlushCount());
                }
            }
        }

        var report = buildReport(properties, orderedFractions, parallelConsumers, messagesPerCase, orderedKeyCount, results);
        var json   = toJson(report);
        System.out.println("############# [perf-lab] queue-design-ab: " + json);
        writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
    }

    private CaseResult runCase(String runId,
                               double orderedFraction,
                               int orderedKeyCount,
                               AckMode ackMode,
                               int parallelConsumers,
                               int messagesPerCase,
                               Duration flushInterval,
                               int maxBatchSize,
                               int repetition,
                               boolean warmup) throws Exception {
        var queueName = QueueName.of("lab_qd_" + runId + "_" + (int) (orderedFraction * 100) + "_" + ackMode.name().toLowerCase(Locale.ROOT) + "_r" + repetition);
        var caseId    = String.format(Locale.ROOT, "ordered%.0f%%/%s", orderedFraction * 100, ackMode);

        // Deterministic message mix: the same seed produces the same ordered/unordered interleaving for both
        // arms, so the two are draining structurally identical backlogs.
        var random   = new Random(20260817L + repetition);
        var messages = new ArrayList<Message>(messagesPerCase);
        var orderPerKey = new HashMap<String, Long>();
        var orderedCount = 0;
        for (var i = 0; i < messagesPerCase; i++) {
            if (random.nextDouble() < orderedFraction) {
                var key   = "key-" + random.nextInt(orderedKeyCount);
                var order = orderPerKey.merge(key, 0L, (existing, ignored) -> existing + 1);
                messages.add(OrderedMessage.of(new LabQueueWorkItem(i, true), key, order));
                orderedCount++;
            } else {
                messages.add(Message.of(new LabQueueWorkItem(i, false)));
            }
        }
        enqueueInHomogeneousRuns(queueName, messages);

        if (ackMode == AckMode.BATCHED) {
            batchingAcknowledgeInterceptor.enable(flushInterval, maxBatchSize);
        }

        var handled          = new CountDownLatch(messagesPerCase);
        var handlerLatencies = new ConcurrentLinkedQueue<Long>();
        var handlerFailures  = new AtomicInteger();
        var drainStartNanos  = new AtomicLong();

        // No artificial handler delay. The whole point is to measure the queue's own per-message database
        // cost, and a sleep would just dilute it with a constant.
        QueuedMessageHandler handler = queuedMessage -> {
            handlerLatencies.add(System.nanoTime() - drainStartNanos.get());
            handled.countDown();
        };

        var executor = Executors.newScheduledThreadPool(parallelConsumers, runnable -> {
            var thread = new Thread(runnable, "qd-lab-worker");
            thread.setDaemon(true);
            return thread;
        });

        var consumeFromQueue = ConsumeFromQueue.builder()
                                               .setQueueName(queueName)
                                               .setConsumerName(caseId)
                                               .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3))
                                               .setParallelConsumers(parallelConsumers)
                                               .setConsumerExecutorService(executor)
                                               .setQueueMessageHandler(handler)
                                               .build();

        drainStartNanos.set(System.nanoTime());
        var consumer = durableQueues.consumeFromQueue(consumeFromQueue);

        // Drain is complete when the rows are *gone*, not when the last handler returned. The handler runs
        // before the framework acknowledges, so a latch-only wait stops the clock before the very cost this
        // scenario exists to measure — the per-message DELETE, or the batch flush that replaces it. An
        // earlier revision did exactly that and left rows behind in the IMMEDIATE arm.
        var completed = awaitQueueDrained(queueName, handled);
        var drainNanos = System.nanoTime() - drainStartNanos.get();

        consumer.cancel();
        executor.shutdownNow();

        var ackFlushCount   = batchingAcknowledgeInterceptor.getFlushCount();
        var ackFlushedCount = batchingAcknowledgeInterceptor.getFlushedMessages();
        if (ackMode == AckMode.BATCHED) {
            // Safety net only - a completed drain means the flusher already emptied the buffer.
            batchingAcknowledgeInterceptor.disableAndDrain();
        }

        var rowsLeftInQueue = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.getTotalMessagesQueuedFor(queueName));
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.purgeQueue(queueName));

        var latencies = new ArrayList<>(handlerLatencies);
        Collections.sort(latencies);
        var drainMillis = Duration.ofNanos(drainNanos).toMillis();
        var messagesHandled = messagesPerCase - (int) handled.getCount();

        return new CaseResult(caseId,
                              orderedFraction,
                              orderedCount,
                              orderedKeyCount,
                              ackMode.name(),
                              parallelConsumers,
                              messagesPerCase,
                              repetition,
                              warmup,
                              completed,
                              messagesHandled,
                              handlerFailures.get(),
                              rowsLeftInQueue,
                              drainMillis,
                              drainMillis == 0 ? 0.0d : messagesHandled * 1000.0d / drainMillis,
                              percentileMillis(latencies, 0.50d),
                              percentileMillis(latencies, 0.99d),
                              percentileMillis(latencies, 1.00d),
                              ackMode == AckMode.BATCHED ? ackFlushCount : messagesHandled,
                              ackMode == AckMode.BATCHED ? ackFlushedCount : messagesHandled);
    }

    /**
     * Enqueues the burst in consecutive runs of the same delivery mode, preserving overall interleaving.
     *
     * <h2>Why not one {@code queueMessages} call</h2>
     * {@code PostgresqlDurableQueues.queueMessages} cannot currently take a list containing both ordered and
     * unordered messages. It binds the {@code key} parameter per row — a {@code String} for an
     * {@link OrderedMessage}, {@code bindNull(key, Types.VARCHAR)} otherwise — but JDBI's
     * {@code PreparedBatch} prepares one binder from the first row's argument types and reuses it for the
     * rest, so a {@code NullArgument} arriving where the prepared binder expects a {@code String} throws
     * {@code ClassCastException: NullArgument cannot be cast to String}. The intent in the framework is
     * right; the batch binding defeats it.
     * <p>
     * Splitting into homogeneous runs sidesteps it. That makes enqueueing more expensive for mixed
     * fractions, which does not affect the measurement: the drain clock starts after all enqueueing is
     * finished, and both ack arms are handed an identically-built backlog.
     */
    private void enqueueInHomogeneousRuns(QueueName queueName, List<Message> messages) {
        var run = new ArrayList<Message>();
        for (var message : messages) {
            var sameKindAsRun = run.isEmpty() || (run.getFirst() instanceof OrderedMessage) == (message instanceof OrderedMessage);
            if (!sameKindAsRun) {
                var toQueue = List.copyOf(run);
                unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.queueMessages(queueName, toQueue));
                run.clear();
            }
            run.add(message);
        }
        if (!run.isEmpty()) {
            var toQueue = List.copyOf(run);
            unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.queueMessages(queueName, toQueue));
        }
    }

    /**
     * Polls until no rows remain for the queue, which is the true end of the drain — see the call site.
     * {@code getTotalMessagesQueuedFor} counts every non-dead-letter row regardless of
     * {@code is_being_delivered}, so a message that has been handled but not yet acknowledged still counts.
     * <p>
     * The poll costs one {@code COUNT(*)} per interval on a connection, identical in both arms, so it biases
     * neither. The interval is a compromise: short enough not to inflate the measured drain, long enough not
     * to become load in its own right.
     *
     * @return {@code true} if the queue drained within {@link #DRAIN_TIMEOUT}
     */
    private boolean awaitQueueDrained(QueueName queueName, CountDownLatch allHandlersEntered) throws InterruptedException {
        var deadlineNanos = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            var remaining = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.getTotalMessagesQueuedFor(queueName));
            if (remaining == 0L) {
                return true;
            }
            Thread.sleep(DRAIN_POLL_INTERVAL.toMillis());
        }
        log.warn("Queue '{}' did not drain within {} - {} handler invocations still outstanding",
                 queueName, DRAIN_TIMEOUT, allHandlersEntered.getCount());
        return false;
    }

    private Map<String, Object> buildReport(EssentialsPerformanceLabProperties properties,
                                            List<Double> orderedFractions,
                                            int parallelConsumers,
                                            int messagesPerCase,
                                            int orderedKeyCount,
                                            List<CaseResult> results) {
        var report = new LinkedHashMap<String, Object>();
        report.put("scenario", name());
        report.put("capturedAt", Instant.now().toString());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        report.put("connectionPoolMaximumSize", resolveConnectionPoolMaximumSize());
        // Fixed at PostgresqlDurableQueues construction, so it is a per-JVM outer-loop parameter rather than
        // something this scenario can vary. Recorded because it selects between two entirely different
        // fetch queries and two different index sets. Read back from the instance rather than echoing what
        // the harness was told to configure — a self-reported label can disagree with reality, which is the
        // exact failure mode that let the builder and the Spring starter default it differently.
        report.put("useOrderedUnorderedQuery", durableQueues instanceof PostgresqlDurableQueues postgresqlDurableQueues
                ? String.valueOf(postgresqlDurableQueues.isUseOrderedUnorderedQuery())
                : properties.getQueueDesignUseOrderedUnorderedQueryLabel());
        report.put("orderedFractionSweep", orderedFractions);
        report.put("parallelConsumers", parallelConsumers);
        report.put("messagesPerCase", messagesPerCase);
        report.put("orderedKeyCount", orderedKeyCount);
        report.put("ackFlushIntervalMs", properties.getQueueDesignAckFlushInterval().toMillis());
        report.put("ackMaxBatchSize", properties.getQueueDesignAckMaxBatchSize());
        report.put("cases", results);
        report.put("comparisons", buildComparisons(results));
        return report;
    }

    /**
     * Pairs the IMMEDIATE and BATCHED arms at each ordered fraction, reducing each to a median with the
     * observed range beside it. See {@code VirtualThreadsQueueScenario#buildComparisons} for why the spread
     * travels with the median rather than a bare speed-up number.
     */
    private List<Map<String, Object>> buildComparisons(List<CaseResult> results) {
        var byFraction = new LinkedHashMap<String, Map<String, List<CaseResult>>>();
        for (var result : results) {
            byFraction.computeIfAbsent(String.format(Locale.ROOT, "ordered%.0f%%", result.orderedFraction() * 100), key -> new LinkedHashMap<>())
                      .computeIfAbsent(result.ackMode(), mode -> new ArrayList<>())
                      .add(result);
        }

        var comparisons = new ArrayList<Map<String, Object>>();
        byFraction.forEach((key, arms) -> {
            var immediate = measured(arms.get(AckMode.IMMEDIATE.name()));
            var batched   = measured(arms.get(AckMode.BATCHED.name()));
            if (immediate.isEmpty() || batched.isEmpty()) {
                return;
            }
            var immediateThroughput = immediate.stream().map(CaseResult::throughputMsgPerSecond).sorted().toList();
            var batchedThroughput   = batched.stream().map(CaseResult::throughputMsgPerSecond).sorted().toList();
            var immediateMedian     = median(immediateThroughput);
            var batchedMedian       = median(batchedThroughput);

            var comparison = new LinkedHashMap<String, Object>();
            comparison.put("key", key);
            comparison.put("repetitions", Math.min(immediateThroughput.size(), batchedThroughput.size()));
            comparison.put("immediateAckThroughputMedianMsgPerSecond", immediateMedian);
            comparison.put("immediateAckThroughputMinMsgPerSecond", immediateThroughput.getFirst());
            comparison.put("immediateAckThroughputMaxMsgPerSecond", immediateThroughput.getLast());
            comparison.put("batchedAckThroughputMedianMsgPerSecond", batchedMedian);
            comparison.put("batchedAckThroughputMinMsgPerSecond", batchedThroughput.getFirst());
            comparison.put("batchedAckThroughputMaxMsgPerSecond", batchedThroughput.getLast());
            comparison.put("batchedAckSpeedup", immediateMedian == 0.0d ? null : batchedMedian / immediateMedian);
            comparison.put("speedupWithinNoise", immediateThroughput.size() < 2 || batchedThroughput.size() < 2
                    ? null
                    : immediateThroughput.getFirst() <= batchedThroughput.getLast()
                            && batchedThroughput.getFirst() <= immediateThroughput.getLast());
            comparisons.add(comparison);
        });
        return comparisons;
    }

    private static List<CaseResult> measured(List<CaseResult> arm) {
        if (arm == null || arm.isEmpty()) {
            return List.of();
        }
        var nonWarmup = arm.stream().filter(result -> !result.warmup()).toList();
        return nonWarmup.isEmpty() ? arm : nonWarmup;
    }

    private static double median(List<Double> sorted) {
        if (sorted.isEmpty()) {
            return 0.0d;
        }
        var middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0d;
    }

    private static double percentileMillis(List<Long> sortedNanos, double percentile) {
        if (sortedNanos.isEmpty()) {
            return -1.0d;
        }
        var index = (int) Math.ceil(percentile * sortedNanos.size()) - 1;
        return sortedNanos.get(Math.max(0, Math.min(index, sortedNanos.size() - 1))) / 1_000_000.0d;
    }

    private int resolveConnectionPoolMaximumSize() {
        try {
            return (int) dataSource.getClass().getMethod("getMaximumPoolSize").invoke(dataSource);
        } catch (ReflectiveOperationException | ClassCastException e) {
            log.debug("Could not determine the connection pool maximum size from {}", dataSource.getClass().getName(), e);
            return -1;
        }
    }

    private static List<Double> parseFractions(String sweep) {
        return Arrays.stream(sweep.split(","))
                     .map(String::trim)
                     .filter(StringUtils::hasText)
                     .map(Double::parseDouble)
                     .toList();
    }

    private void writeMetricsIfConfigured(String metricsOutputFile, String json) throws IOException {
        if (!StringUtils.hasText(metricsOutputFile)) return;
        var target = Paths.get(metricsOutputFile).toAbsolutePath().normalize();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, json + System.lineSeparator(),
                          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        log.info("Wrote queue-design-ab metrics to {}", target);
        System.out.println("############# [perf-lab] queue-design-ab metrics file: " + target);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize queue-design-ab metrics to JSON", e);
        }
    }

    public enum AckMode {
        /**
         * Today's behaviour: one {@code DELETE ... WHERE id = :id} per handled message, each in its own
         * transaction.
         */
        IMMEDIATE,
        /**
         * Acks buffered and flushed as one {@code DELETE ... WHERE id IN (...)} per interval or batch size.
         */
        BATCHED
    }

    public record CaseResult(String caseId,
                             double orderedFraction,
                             int orderedMessages,
                             int orderedKeyCount,
                             String ackMode,
                             int parallelConsumers,
                             int messagesQueued,
                             int repetition,
                             boolean warmup,
                             boolean drainedWithinTimeout,
                             int messagesHandled,
                             int handlerFailures,
                             long rowsLeftInQueue,
                             long drainMillis,
                             double throughputMsgPerSecond,
                             double latencyP50Millis,
                             double latencyP99Millis,
                             double latencyMaxMillis,
                             long ackFlushCount,
                             long ackFlushedMessages) {
    }

    public record LabQueueWorkItem(int sequence, boolean ordered) {
    }
}
