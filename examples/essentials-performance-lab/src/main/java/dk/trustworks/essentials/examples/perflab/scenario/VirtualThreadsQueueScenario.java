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
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import dk.trustworks.essentials.examples.perflab.vthreads.VirtualThreadScheduledExecutorAdapter;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * A/B benchmark: the {@code DurableQueues} consumer worker pool backed by <em>platform</em> threads
 * (today's default, {@code Executors.newScheduledThreadPool(parallelConsumers)}) versus <em>virtual</em>
 * threads, across a sweep of {@code parallelConsumers} values.
 *
 * <h2>Why this is the right place to measure</h2>
 * With the default {@code useCentralizedMessageFetcher=true}, a consumer's worker pool does not bound
 * concurrency — {@code CentralizedMessageFetcher.calculateAvailableWorkerSlotsPerQueue()} does, via the
 * {@code maxParallelConsumers - activeWorkers} slot count. The pool only supplies threads for
 * {@code submit(...)}. That makes platform-vs-virtual a clean single-variable swap: both arms admit
 * exactly {@code parallelConsumers} messages in flight, and only the thread implementation differs.
 *
 * <h2>The two handler shapes, and why both are needed</h2>
 * <dl>
 *     <dt>{@link HandlerMode#SLEEP}</dt>
 *     <dd>The handler blocks without holding a pooled JDBC connection — the stand-in for a message
 *     handler that calls an HTTP API, a broker, or any other external service. This is the shape where
 *     virtual threads are expected to pay: the block unmounts the carrier instead of parking an OS
 *     thread.</dd>
 *     <dt>{@link HandlerMode#DB}</dt>
 *     <dd>The handler blocks <em>inside</em> a unit of work via {@code pg_sleep}, holding a Hikari
 *     connection for its whole duration — the shape of a handler that reads or writes the database.
 *     Here the connection pool, not the thread pool, is the scarce resource, so raising thread
 *     concurrency past the pool size cannot raise throughput no matter what kind of thread it is.</dd>
 * </dl>
 * Reporting only one of the two would produce a misleading headline. Both are run by default.
 *
 * <h2>What is measured per case</h2>
 * A fixed burst of {@code messagesPerCase} messages is queued up-front, then the consumer is started and
 * the queue is drained. Recorded: wall-clock drain time, achieved throughput, per-message latency from
 * drain-start to handler entry (p50/p99/max), JVM peak thread count over the case, and process RSS delta.
 * Every case runs against a freshly named queue so no backlog carries over.
 */
@Component
public class VirtualThreadsQueueScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(VirtualThreadsQueueScenario.class);

    /**
     * How long to wait for a case to drain before giving up and reporting it as incomplete. Generous:
     * the DB-bound arm at high parallelism is deliberately slow, and a timed-out case is still a data
     * point as long as it is labelled as one.
     */
    private static final Duration DRAIN_TIMEOUT = Duration.ofMinutes(5);

    private final DurableQueues                                                 durableQueues;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final DataSource                                                    dataSource;
    private final ObjectMapper                                                  objectMapper;

    public VirtualThreadsQueueScenario(DurableQueues durableQueues,
                                       HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                       DataSource dataSource,
                                       ObjectMapper objectMapper) {
        this.durableQueues = durableQueues;
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "virtual-threads-queue";
    }

    @Override
    public String description() {
        return "A/B's the DurableQueues consumer worker pool (platform vs virtual threads) across a parallelConsumers sweep, "
                + "for both a non-DB-blocking handler and a connection-holding DB handler";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        var parallelism      = parseParallelism(properties.getVirtualThreadsParallelConsumers());
        var messagesPerCase  = properties.getVirtualThreadsMessagesPerCase();
        var handlerDelay     = properties.getVirtualThreadsHandlerDelay();
        var handlerModes     = parseHandlerModes(properties.getVirtualThreadsHandlerMode());
        var runId            = Long.toHexString(System.nanoTime());

        log.info("virtual-threads-queue: parallelism={}, messagesPerCase={}, handlerDelay={}, handlerModes={}",
                 parallelism, messagesPerCase, handlerDelay, handlerModes);

        var repetitions = properties.getVirtualThreadsRepetitions();
        var results     = new ArrayList<CaseResult>();
        for (var handlerMode : handlerModes) {
            for (var parallelConsumers : parallelism) {
                // At high parallelism a fixed burst drains in a couple of fetcher ticks, so the measurement
                // would be all ramp-up and no steady state. Scale the burst so every case gets at least
                // ~8 messages per slot; the actual count used is reported per case.
                var messagesForCase = Math.max(messagesPerCase, parallelConsumers * 8);
                for (var repetition = 0; repetition < repetitions; repetition++) {
                    // Executor kinds alternate *inside* a repetition rather than one arm running to
                    // completion before the other. A single measurement of this workload has a spread wide
                    // enough to reverse the sign of the platform-vs-virtual delta, and running the arms in
                    // blocks would let any drift over the run (page cache, container CPU credits) land
                    // entirely on one of them.
                    for (var executorKind : ExecutorKind.values()) {
                        // Repetition 0 of the whole run doubles as JIT and connection-pool warm-up.
                        // Reported as warmup=true so the analysis can drop it.
                        var result = runCase(runId, handlerMode, executorKind, parallelConsumers, messagesForCase, handlerDelay, repetition, results.isEmpty());
                        results.add(result);
                        log.info("virtual-threads-queue case {} rep {} => {} msg/s, drain {} ms, peakThreads {}, rssDeltaKb {}",
                                 result.caseId(), repetition, String.format("%.1f", result.throughputMsgPerSecond()), result.drainMillis(), result.peakThreadCount(), result.rssDeltaKb());
                    }
                }
            }
        }

        var report = buildReport(properties, parallelism, messagesPerCase, handlerDelay, results);
        var json   = toJson(report);
        System.out.println("############# [perf-lab] virtual-threads-queue: " + json);
        writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
    }

    private CaseResult runCase(String runId,
                               HandlerMode handlerMode,
                               ExecutorKind executorKind,
                               int parallelConsumers,
                               int messagesPerCase,
                               Duration handlerDelay,
                               int repetition,
                               boolean warmup) throws Exception {
        var queueName = QueueName.of("lab_vt_" + runId + "_" + handlerMode.name().toLowerCase(Locale.ROOT) + "_" + executorKind.name().toLowerCase(Locale.ROOT) + "_" + parallelConsumers + "_r" + repetition);
        var caseId    = handlerMode + "/" + executorKind + "/p" + parallelConsumers;

        // Queue the whole burst before any consumer exists, so every case starts from an identical backlog
        // and the measured window contains only consumption.
        var messages = new ArrayList<Message>(messagesPerCase);
        for (var i = 0; i < messagesPerCase; i++) {
            messages.add(Message.of(new LabWorkItem(i, handlerDelay.toMillis())));
        }
        unitOfWorkFactory.usingUnitOfWork(uow -> durableQueues.queueMessages(queueName, messages));

        var handled          = new CountDownLatch(messagesPerCase);
        var handlerLatencies = new ConcurrentLinkedQueue<Long>();
        var handlerFailures  = new AtomicInteger();
        var drainStartNanos  = new AtomicLong();

        var threadMxBean = ManagementFactory.getThreadMXBean();
        threadMxBean.resetPeakThreadCount();
        var rssBeforeKb        = readResidentSetSizeKb();
        var threadsBeforeStart = threadMxBean.getThreadCount();

        var executor = switch (executorKind) {
            case PLATFORM -> Executors.newScheduledThreadPool(parallelConsumers, runnable -> {
                var thread = new Thread(runnable, "vt-lab-platform-" + parallelConsumers);
                thread.setDaemon(true);
                return thread;
            });
            case VIRTUAL -> new VirtualThreadScheduledExecutorAdapter("vt-lab-" + parallelConsumers);
        };

        QueuedMessageHandler handler = queuedMessage -> {
            handlerLatencies.add(System.nanoTime() - drainStartNanos.get());
            try {
                switch (handlerMode) {
                    // Blocks the thread without holding any pooled resource: the external-service shape.
                    case SLEEP -> Thread.sleep(handlerDelay.toMillis());
                    // Blocks *inside* a unit of work, so a Hikari connection is held for the whole delay:
                    // the read-or-write-the-database shape.
                    case DB -> unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle()
                                                                           .execute("SELECT pg_sleep(?)", handlerDelay.toMillis() / 1000.0d));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while simulating handler work", e);
            } catch (RuntimeException e) {
                handlerFailures.incrementAndGet();
                throw e;
            } finally {
                handled.countDown();
            }
        };

        var consumeFromQueue = ConsumeFromQueue.builder()
                                               .setQueueName(queueName)
                                               .setConsumerName(caseId)
                                               .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3))
                                               .setParallelConsumers(parallelConsumers)
                                               .setConsumerExecutorService(executor)
                                               .setQueueMessageHandler(handler)
                                               .build();

        drainStartNanos.set(System.nanoTime());
        var consumer  = durableQueues.consumeFromQueue(consumeFromQueue);
        var completed = handled.await(DRAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        var drainNanos = System.nanoTime() - drainStartNanos.get();

        var peakThreadCount = threadMxBean.getPeakThreadCount();
        var rssAfterKb      = readResidentSetSizeKb();

        consumer.cancel();
        executor.shutdownNow();
        unitOfWorkFactory.usingUnitOfWork(uow -> durableQueues.purgeQueue(queueName));

        var latencies = new ArrayList<>(handlerLatencies);
        Collections.sort(latencies);
        var drainMillis = Duration.ofNanos(drainNanos).toMillis();

        return new CaseResult(caseId,
                              handlerMode.name(),
                              executorKind.name(),
                              parallelConsumers,
                              messagesPerCase,
                              handlerDelay.toMillis(),
                              repetition,
                              warmup,
                              completed,
                              messagesPerCase - (int) handled.getCount(),
                              handlerFailures.get(),
                              drainMillis,
                              drainMillis == 0 ? 0.0d : (messagesPerCase - handled.getCount()) * 1000.0d / drainMillis,
                              percentileMillis(latencies, 0.50d),
                              percentileMillis(latencies, 0.99d),
                              percentileMillis(latencies, 1.00d),
                              threadsBeforeStart,
                              peakThreadCount,
                              peakThreadCount - threadsBeforeStart,
                              rssBeforeKb,
                              rssAfterKb,
                              rssBeforeKb < 0 || rssAfterKb < 0 ? -1L : rssAfterKb - rssBeforeKb);
    }

    private Map<String, Object> buildReport(EssentialsPerformanceLabProperties properties,
                                            List<Integer> parallelism,
                                            int messagesPerCase,
                                            Duration handlerDelay,
                                            List<CaseResult> results) {
        var report = new LinkedHashMap<String, Object>();
        report.put("scenario", name());
        report.put("capturedAt", Instant.now().toString());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("javaVmName", System.getProperty("java.vm.name"));
        report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        // The carrier pool is what a virtual thread ultimately runs on; without it the SLEEP-vs-DB
        // difference below is not interpretable.
        report.put("virtualThreadSchedulerParallelism", System.getProperty("jdk.virtualThreadScheduler.parallelism", "default(availableProcessors)"));
        // The DB-mode ceiling is (connectionPoolSize / handlerDelay) messages per second regardless of how
        // many threads are in flight, so the pool size has to travel with the numbers or the DB arm is
        // uninterpretable.
        report.put("connectionPoolMaximumSize", resolveConnectionPoolMaximumSize());
        report.put("parallelConsumersSweep", parallelism);
        report.put("messagesPerCase", messagesPerCase);
        report.put("handlerDelayMs", handlerDelay.toMillis());
        report.put("handlerModes", properties.getVirtualThreadsHandlerMode());
        report.put("cases", results);
        report.put("comparisons", buildComparisons(results));
        return report;
    }

    /**
     * Pairs the PLATFORM and VIRTUAL arms that share a handler mode and parallelism, reducing each arm's
     * repetitions to a <em>median</em> throughput and reporting the observed min/max alongside it.
     * <p>
     * The median rather than the mean, and the spread rather than a bare speed-up number, because a single
     * measurement of this workload has a run-to-run spread wide enough to reverse the sign of the delta —
     * so a speed-up quoted without the spread next to it is not a result. {@code speedupWithinNoise} is
     * {@code true} when the two arms' observed ranges overlap, i.e. when the difference between the medians
     * is not distinguishable from the variance of either arm.
     * <p>
     * Warm-up repetitions are excluded when any non-warm-up repetition exists for that arm.
     */
    private List<Map<String, Object>> buildComparisons(List<CaseResult> results) {
        var byKey = new LinkedHashMap<String, Map<String, List<CaseResult>>>();
        for (var result : results) {
            byKey.computeIfAbsent(result.handlerMode() + "/p" + result.parallelConsumers(), key -> new LinkedHashMap<>())
                 .computeIfAbsent(result.executorKind(), kind -> new ArrayList<>())
                 .add(result);
        }

        var comparisons = new ArrayList<Map<String, Object>>();
        byKey.forEach((key, arms) -> {
            var platform = measured(arms.get(ExecutorKind.PLATFORM.name()));
            var virtual  = measured(arms.get(ExecutorKind.VIRTUAL.name()));
            if (platform.isEmpty() || virtual.isEmpty()) {
                return;
            }

            var platformThroughput = platform.stream().map(CaseResult::throughputMsgPerSecond).sorted().toList();
            var virtualThroughput  = virtual.stream().map(CaseResult::throughputMsgPerSecond).sorted().toList();
            var platformMedian     = median(platformThroughput);
            var virtualMedian      = median(virtualThroughput);

            var comparison = new LinkedHashMap<String, Object>();
            comparison.put("key", key);
            comparison.put("repetitions", Math.min(platformThroughput.size(), virtualThroughput.size()));
            comparison.put("platformThroughputMedianMsgPerSecond", platformMedian);
            comparison.put("platformThroughputMinMsgPerSecond", platformThroughput.getFirst());
            comparison.put("platformThroughputMaxMsgPerSecond", platformThroughput.getLast());
            comparison.put("virtualThroughputMedianMsgPerSecond", virtualMedian);
            comparison.put("virtualThroughputMinMsgPerSecond", virtualThroughput.getFirst());
            comparison.put("virtualThroughputMaxMsgPerSecond", virtualThroughput.getLast());
            comparison.put("virtualThroughputSpeedup", platformMedian == 0.0d ? null : virtualMedian / platformMedian);
            // Null rather than false below two repetitions: with a single sample each arm's "range" is a
            // point, so the ranges never overlap and the flag would read as "the difference is real" for
            // exactly the measurements that cannot support that claim.
            comparison.put("speedupWithinNoise", platformThroughput.size() < 2 || virtualThroughput.size() < 2
                    ? null
                    : platformThroughput.getFirst() <= virtualThroughput.getLast()
                            && virtualThroughput.getFirst() <= platformThroughput.getLast());
            comparison.put("platformPeakThreadCountMax", platform.stream().mapToInt(CaseResult::peakThreadCount).max().orElse(-1));
            comparison.put("virtualPeakThreadCountMax", virtual.stream().mapToInt(CaseResult::peakThreadCount).max().orElse(-1));
            comparisons.add(comparison);
        });
        return comparisons;
    }

    /**
     * The repetitions that count: warm-up ones are dropped unless they are all there is.
     */
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

    /**
     * Reads the connection pool's maximum size reflectively so the scenario keeps compiling if the pool
     * implementation is swapped; {@code -1} when it cannot be determined.
     */
    private int resolveConnectionPoolMaximumSize() {
        try {
            var getMaximumPoolSize = dataSource.getClass().getMethod("getMaximumPoolSize");
            return (int) getMaximumPoolSize.invoke(dataSource);
        } catch (ReflectiveOperationException | ClassCastException e) {
            log.debug("Could not determine the connection pool maximum size from {}", dataSource.getClass().getName(), e);
            return -1;
        }
    }

    private static double percentileMillis(List<Long> sortedNanos, double percentile) {
        if (sortedNanos.isEmpty()) {
            return -1.0d;
        }
        var index = (int) Math.ceil(percentile * sortedNanos.size()) - 1;
        return sortedNanos.get(Math.max(0, Math.min(index, sortedNanos.size() - 1))) / 1_000_000.0d;
    }

    /**
     * Process RSS in KiB from {@code /proc/self/status}, or {@code -1} where that file does not exist
     * (non-Linux). Platform-thread stacks are off-heap, so a JMX heap reading would show nothing — RSS is
     * the only figure that captures the cost this scenario is trying to expose.
     */
    private static long readResidentSetSizeKb() {
        var status = Path.of("/proc/self/status");
        if (!Files.exists(status)) {
            return -1L;
        }
        try {
            for (var line : Files.readAllLines(status)) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (IOException | NumberFormatException e) {
            log.debug("Could not read VmRSS from /proc/self/status", e);
        }
        return -1L;
    }

    private static List<Integer> parseParallelism(String sweep) {
        return Arrays.stream(sweep.split(","))
                     .map(String::trim)
                     .filter(StringUtils::hasText)
                     .map(Integer::parseInt)
                     .toList();
    }

    private static List<HandlerMode> parseHandlerModes(String configured) {
        if ("BOTH".equalsIgnoreCase(configured)) {
            return List.of(HandlerMode.SLEEP, HandlerMode.DB);
        }
        return List.of(HandlerMode.valueOf(configured.toUpperCase(Locale.ROOT)));
    }

    private void writeMetricsIfConfigured(String metricsOutputFile, String json) throws IOException {
        if (!StringUtils.hasText(metricsOutputFile)) return;
        var target = Paths.get(metricsOutputFile).toAbsolutePath().normalize();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, json + System.lineSeparator(),
                          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        log.info("Wrote virtual-threads-queue metrics to {}", target);
        System.out.println("############# [perf-lab] virtual-threads-queue metrics file: " + target);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize virtual-threads-queue metrics to JSON", e);
        }
    }

    public enum ExecutorKind {
        PLATFORM,
        VIRTUAL
    }

    public enum HandlerMode {
        /**
         * Handler blocks without holding a pooled JDBC connection — the external-service shape.
         */
        SLEEP,
        /**
         * Handler blocks inside a unit of work via {@code pg_sleep}, holding a Hikari connection throughout
         * — the read-or-write-the-database shape.
         */
        DB
    }

    public record CaseResult(String caseId,
                             String handlerMode,
                             String executorKind,
                             int parallelConsumers,
                             int messagesQueued,
                             long handlerDelayMs,
                             int repetition,
                             boolean warmup,
                             boolean drainedWithinTimeout,
                             int messagesHandled,
                             int handlerFailures,
                             long drainMillis,
                             double throughputMsgPerSecond,
                             double latencyP50Millis,
                             double latencyP99Millis,
                             double latencyMaxMillis,
                             int threadCountBefore,
                             int peakThreadCount,
                             int threadDelta,
                             long rssBeforeKb,
                             long rssAfterKb,
                             long rssDeltaKb) {
    }

    public record LabWorkItem(int sequence, long handlerDelayMs) {
    }
}
