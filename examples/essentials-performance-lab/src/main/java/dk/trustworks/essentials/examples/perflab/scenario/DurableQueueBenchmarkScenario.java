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

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties.QueueBenchmark;
import dk.trustworks.essentials.examples.perflab.harness.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.slf4j.*;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntFunction;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Captures the baseline that the next-generation queue design has to beat.
 * <p>
 * Every target in the design document is currently an estimate. Until the existing implementation
 * has been measured on the same machine, with the same harness and the same statistical treatment
 * as the candidate will be, no later phase can claim an improvement — it can only claim a number.
 * This scenario produces the other half of every future comparison.
 * <p>
 * The workload profiles the plan calls for are configuration of this one scenario rather than
 * separate classes, because that is all that separates them:
 * <table>
 *     <caption>Profiles</caption>
 *     <tr><th>Profile</th><th>Configuration</th></tr>
 *     <tr><td>Latency at low rate</td><td>{@code producer-rate-hz} small</td></tr>
 *     <tr><td>Saturating throughput</td><td>{@code producer-rate-hz=0}</td></tr>
 *     <tr><td>Ordered by key cardinality</td><td>{@code workload=ORDERED}, {@code key-cardinality}</td></tr>
 *     <tr><td>Injected failures</td><td>{@code failure-percent=10}</td></tr>
 *     <tr><td>Many idle queues</td><td>{@code queue-count} high, {@code busy-queues} low</td></tr>
 *     <tr><td>Soak</td><td>{@code duration=30m}</td></tr>
 * </table>
 * Unlike the sequence-gap scenario, this one acknowledges and deletes, so it is the first to produce
 * meaningful dead-tuple and vacuum numbers — the figures that decide whether a design stays fast an
 * hour into a run rather than only for the length of a benchmark.
 */
@Component
public class DurableQueueBenchmarkScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(DurableQueueBenchmarkScenario.class);

    private static final String QUEUE_TABLE_NAME = "perflab_durable_queues";

    private final DataSource dataSource;

    public DurableQueueBenchmarkScenario(DataSource dataSource) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
    }

    @Override
    public String name() {
        return "durable-queues";
    }

    @Override
    public String description() {
        return "Baseline capture of the existing PostgreSQL DurableQueues: throughput, coordinated-omission-free latency, WAL and dead-tuple cost across workload profiles";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        requireNonNull(properties, "No properties provided");
        var settings = properties.getQueueBenchmark();
        var environment = PgSnapshot.captureEnvironment(dataSource);

        var arms = new LinkedHashMap<String, IntFunction<RunResult>>();
        for (var arm : settings.getArms()) {
            arms.put(arm, repetition -> {
                try {
                    return measureOnce(arm, repetition, properties, environment);
                } catch (Exception e) {
                    throw new IllegalStateException("Arm '" + arm + "' repetition " + repetition + " failed", e);
                }
            });
        }

        var results = new AbRunner(settings.getRepetitions()).run(arms);
        var summaries = AbRunner.summarize(results);

        summaries.forEach(summary -> log.info("Arm '{}': throughput median={}/s IQR={}, responseTime p50 median={}us p99 median={}us, WAL bytes/msg median={}",
                                              summary.arm(),
                                              String.format("%.1f", summary.throughputPerSecond().median()),
                                              String.format("%.1f", summary.throughputPerSecond().interQuartileRange()),
                                              String.format("%.0f", summary.responseTimeP50Micros().median()),
                                              String.format("%.0f", summary.responseTimeP99Micros().median()),
                                              String.format("%.0f", summary.walBytesPerOperation().median())));

        // Two arms whose interquartile ranges overlap have not been distinguished by this run,
        // whatever their medians say. Stating that here stops the summary being read as a verdict.
        if (summaries.size() == 2) {
            var first = summaries.get(0);
            var second = summaries.get(1);
            log.info("Throughput distributions {} — {} vs {}",
                     first.throughputPerSecond().overlaps(second.throughputPerSecond())
                     ? "OVERLAP: this run does not separate the arms"
                     : "are separated",
                     first.arm(),
                     second.arm());
        }

        RunResult.writeAll(properties.getMetricsOutputFile(),
                           Map.of("scenario", name(),
                                  "workload", settings.getWorkload().name(),
                                  "environment", environment,
                                  "summaries", summaries,
                                  "runs", results));
    }

    private RunResult measureOnce(String arm,
                                  int repetition,
                                  EssentialsPerformanceLabProperties properties,
                                  Map<String, String> environment) throws Exception {
        var settings = properties.getQueueBenchmark();
        var queueNames = queueNames(properties.getQueueCount());

        var jdbi = Jdbi.create(dataSource).installPlugin(new PostgresPlugin());
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(jdbi))
                                                   // Flavor-neutral: building the serializer by hand would silently
                                                   // pin the benchmark to one Jackson major.
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .setSharedQueueTableName(QUEUE_TABLE_NAME)
                                                   .setUseCentralizedMessageFetcher(!"traditional".equals(arm))
                                                   .setCentralizedMessageFetcherPollingInterval(settings.getPollingInterval())
                                                   .build();
        durableQueues.start();

        var consumers = new ArrayList<DurableQueueConsumer>(queueNames.size());
        try {
            resetQueueTable();

            if (!properties.getWarmup().isZero()) {
                var warmupHarvest = new Harvest();
                // NEUTRAL warmup: plain unordered messages, one queue, no injected failures —
                // whatever profile is about to be measured.
                //
                // The warmup exists to give the post-warmup ANALYZE a representative table, so the
                // table it leaves must not depend on the profile. When each profile warmed up with
                // its own workload, three of them left states the measured run would never see —
                // retry rows carrying future delivery timestamps, or a queue blocked on ten keys —
                // and the statistics ANALYZE recorded described those instead. Those three profiles
                // then ran in the planner's fast-plan mode while the others ran in its slow one,
                // which is how a 10% failure rate came to report five times the throughput of an
                // unimpaired queue.
                consumers.addAll(startConsumers(durableQueues, queueNames.subList(0, 1), settings, warmupHarvest, true));
                runPhase(durableQueues, queueNames.subList(0, 1), properties, warmupHarvest, properties.getWarmup().toMillis(), true);
                stopConsumers(consumers);
                consumers.clear();
                // Analyse HERE, on the table as the warmup left it — churned, drained, and the size
                // the measured run will actually see. Truncating at this point (which this method
                // used to do) throws away precisely the state that makes the statistics
                // representative, and hands the planner an empty-table estimate for a run that is
                // about to insert tens of thousands of rows.
                analyzeQueueTable();
            }

            var harvest = new Harvest();
            consumers.addAll(startConsumers(durableQueues, queueNames, settings, harvest, false));

            var pgBefore = PgSnapshot.capture(dataSource, List.of(QUEUE_TABLE_NAME));
            var jvmBefore = JvmSnapshot.capture();
            var startedAt = Instant.now();
            var startNanos = System.nanoTime();

            var phase = runPhase(durableQueues, queueNames, properties, harvest, properties.getDuration().toMillis(), false);

            var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            var pgAfter = PgSnapshot.capture(dataSource, List.of(QUEUE_TABLE_NAME));
            var jvmAfter = JvmSnapshot.capture();

            var handled = harvest.messagesHandled.sum();
            var deadLettered = queueNames.stream().mapToLong(durableQueues::getTotalDeadLetterMessagesQueuedFor).sum();
            var stillQueued = queueNames.stream().mapToLong(durableQueues::getTotalMessagesQueuedFor).sum();

            var dbDelta = pgAfter.deltaFrom(pgBefore);
            var queued = harvest.messagesQueued.sum();

            var extra = new LinkedHashMap<String, Object>();
            extra.put("messagesQueued", queued);
            extra.put("messagesHandled", handled);
            extra.put("handlerFailuresInjected", harvest.handlerFailures.sum());
            extra.put("deadLetterMessages", deadLettered);
            extra.put("messagesStillQueuedAtEnd", stillQueued);
            extra.put("producerBackpressureWaits", harvest.producerBackpressureWaits.sum());
            extra.put("queueCount", queueNames.size());
            extra.put("busyQueues", Math.min(settings.getBusyQueues(), queueNames.size()));
            extra.put("workload", settings.getWorkload().name());

            // n_dead_tup is a GAUGE, not a counter — autovacuum drives it back down, so differencing
            // two readings produces negative "dead tuples per message". The counters that actually
            // measure the churn a design creates are n_tup_upd and n_tup_del.
            var tupleUpdates = dbDelta.getOrDefault("table." + QUEUE_TABLE_NAME + ".n_tup_upd", 0L);
            var tupleDeletes = dbDelta.getOrDefault("table." + QUEUE_TABLE_NAME + ".n_tup_del", 0L);
            extra.put("tupleUpdates", tupleUpdates);
            extra.put("tupleDeletes", tupleDeletes);
            extra.put("deadTuplesCreatedPerMessage", handled == 0 ? 0.0d : (double) (tupleUpdates + tupleDeletes) / handled);
            extra.put("deadTuplesOutstandingAtEnd", pgAfter.gauge("table." + QUEUE_TABLE_NAME + ".n_dead_tup"));

            // A run whose producers outran its consumers is not a steady-state measurement: its
            // latency figures describe queue depth, and its per-message costs divide database work
            // by a denominator that never caught up. Say so in the result rather than letting the
            // numbers be quoted as if they meant what they usually mean.
            var saturated = queued > 0 && stillQueued > queued / 20L;
            extra.put("saturated", saturated);
            if (saturated) {
                log.warn("Arm '{}' repetition {} ended saturated: {} queued, {} handled, {} still queued. "
                         + "Latency figures for this run measure backlog, not delivery.",
                         arm, repetition, queued, handled, stillQueued);
            }

            // Latency is only a property of the implementation when the offered rate is below
            // capacity. Run a queue AT capacity and Little's Law fixes the answer in advance:
            // wait = standingDepth / throughput, whatever the implementation does. Bounding
            // in-flight work stops a backlog growing without bound, but it does not make the
            // resulting latency meaningful — it just pins the standing depth to the bound.
            // So the latency profile must offer a rate the consumers can keep up with, and a run
            // that engaged backpressure has not done that.
            var backpressureWaits = harvest.producerBackpressureWaits.sum();
            var latencyMeaningful = properties.getProducerRateHz() > 0.0d && backpressureWaits == 0L && !saturated;
            extra.put("latencyMeaningful", latencyMeaningful);
            if (!latencyMeaningful) {
                log.warn("Arm '{}' repetition {}: latency figures are capacity-bound (offered rate {}, backpressure waits {}) "
                         + "and describe queue depth rather than delivery latency. Use a throttled rate below capacity to measure latency.",
                         arm, repetition, properties.getProducerRateHz(), backpressureWaits);
            }
            extra.put("walBytesPerMessageQueued", queued == 0 ? 0.0d : (double) dbDelta.getOrDefault("wal.bytesWritten", 0L) / queued);
            extra.put("steadyStateWindowMillis", phase.windowMillis());
            extra.put("messagesHandledInWindow", phase.handledInWindow());
            extra.put("drainMillis", elapsedMillis - phase.windowMillis());

            var config = new LinkedHashMap<String, Object>();
            config.put("fetcher", "traditional".equals(arm) ? "per-consumer" : "centralized");
            config.put("producerThreads", properties.getProducerThreads());
            config.put("producerRateHz", properties.getProducerRateHz());
            config.put("parallelConsumers", settings.getParallelConsumers());
            config.put("pollingIntervalMillis", settings.getPollingInterval().toMillis());
            config.put("failurePercent", settings.getFailurePercent());
            config.put("keyCardinality", settings.getKeyCardinality());
            config.put("durationMillis", properties.getDuration().toMillis());

            return new RunResult(name(),
                                 arm,
                                 repetition,
                                 startedAt,
                                 elapsedMillis,
                                 handled,
                                 phase.windowMillis() == 0 ? 0.0d : phase.handledInWindow() * 1000.0d / phase.windowMillis(),
                                 config,
                                 List.of(harvest.endToEnd.responseTimeSummary(), harvest.endToEnd.serviceTimeSummary()),
                                 dbDelta,
                                 jvmAfter.deltaFrom(jvmBefore),
                                 environment,
                                 extra);
        } finally {
            stopConsumers(consumers);
            durableQueues.stop();
        }
    }

    /**
     * @return what happened during the steady-state window, separately from the drain that follows it
     */
    private PhaseResult runPhase(DurableQueues durableQueues,
                                 List<QueueName> queueNames,
                                 EssentialsPerformanceLabProperties properties,
                                 Harvest harvest,
                                 long durationMillis,
                                 boolean neutral) throws Exception {
        var settings = properties.getQueueBenchmark();
        var producing = new AtomicBoolean(true);
        var executor = Executors.newFixedThreadPool(properties.getProducerThreads());
        var producers = new ArrayList<Future<?>>(properties.getProducerThreads());
        try {
            var windowStartNanos = System.nanoTime();
            for (var producer = 0; producer < properties.getProducerThreads(); producer++) {
                producers.add(executor.submit(() -> produce(durableQueues, queueNames, properties, harvest, producing, neutral)));
            }
            Thread.sleep(durationMillis);
            producing.set(false);
            for (var producer : producers) {
                producer.get(60, TimeUnit.SECONDS);
            }
            // Throughput is measured over THIS window only. Producers are backpressure-bound to the
            // consumers here, so handled/window is the true steady-state rate. Including the drain
            // that follows divides real work by mostly-idle time: the 10%-failure profile spent 6
            // seconds producing and 45 seconds draining, and reported 145/s for a system that was
            // actually running at roughly ten times that.
            var windowMillis = (System.nanoTime() - windowStartNanos) / 1_000_000L;
            var handledInWindow = harvest.messagesHandled.sum();

            // Drain separately, so nothing is left unhandled and the queue-depth and dead-letter
            // figures describe a settled system.
            var deadline = System.nanoTime() + settings.getDrainTimeout().toNanos();
            while (System.nanoTime() < deadline && harvest.messagesHandled.sum() < harvest.messagesQueued.sum()) {
                Thread.sleep(25L);
            }
            return new PhaseResult(windowMillis, handledInWindow);
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Producer executor did not terminate cleanly");
            }
        }
    }

    private record PhaseResult(long windowMillis, long handledInWindow) {
    }

    private void produce(DurableQueues durableQueues,
                         List<QueueName> queueNames,
                         EssentialsPerformanceLabProperties properties,
                         Harvest harvest,
                         AtomicBoolean producing,
                         boolean neutral) {
        var settings = properties.getQueueBenchmark();
        var payload = "x".repeat(settings.getPayloadBytes());
        var busyQueues = Math.max(1, Math.min(settings.getBusyQueues(), queueNames.size()));
        var random = new Random(properties.getRandomSeed() + Thread.currentThread().threadId());

        var rateHz = properties.getProducerRateHz();
        var intervalNanos = rateHz > 0.0d
                            ? (long) (1_000_000_000.0d * properties.getProducerThreads() / rateHz)
                            : 0L;
        var scheduleStartNanos = System.nanoTime();
        var operation = 0L;

        try {
            while (producing.get()) {
                var intendedNanos = intervalNanos == 0L
                                    ? System.nanoTime()
                                    : scheduleStartNanos + operation * intervalNanos;
                if (intervalNanos != 0L) {
                    var waitNanos = intendedNanos - System.nanoTime();
                    if (waitNanos > 0) {
                        TimeUnit.NANOSECONDS.sleep(waitNanos);
                    }
                }
                // Bounded in-flight work. Both figures are in-memory counters, so this costs nothing
                // and keeps the system in a steady state rather than letting it build a backlog that
                // turns every latency sample into a measure of queue depth.
                while (producing.get()
                       && harvest.messagesQueued.sum() - harvest.messagesHandled.sum() >= settings.getMaxInFlight()) {
                    harvest.producerBackpressureWaits.increment();
                    TimeUnit.MILLISECONDS.sleep(1L);
                }
                if (!producing.get()) {
                    break;
                }
                var queueName = queueNames.get(random.nextInt(busyQueues));
                var body = new BenchmarkMessage(intendedNanos, payload);

                if (!neutral && settings.getWorkload() == QueueBenchmark.Workload.ORDERED) {
                    var key = "key-" + random.nextInt(settings.getKeyCardinality());
                    var order = harvest.keyOrder.computeIfAbsent(key, ignored -> new AtomicLong()).getAndIncrement();
                    durableQueues.queueMessage(queueName, OrderedMessage.of(body, key, order));
                } else {
                    durableQueues.queueMessage(queueName, Message.of(body));
                }
                harvest.messagesQueued.increment();
                operation++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<DurableQueueConsumer> startConsumers(DurableQueues durableQueues,
                                                      List<QueueName> queueNames,
                                                      QueueBenchmark settings,
                                                      Harvest harvest,
                                                      boolean neutral) {
        var redeliveryPolicy = RedeliveryPolicy.fixedBackoff(settings.getRedeliveryDelay(), settings.getMaxRedeliveries());
        var consumers = new ArrayList<DurableQueueConsumer>(queueNames.size());
        for (var queueName : queueNames) {
            consumers.add(durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                                         .setQueueName(queueName)
                                                                         .setRedeliveryPolicy(redeliveryPolicy)
                                                                         .setParallelConsumers(settings.getParallelConsumers())
                                                                         .setQueueMessageHandler(handler(settings, harvest, neutral))
                                                                         .build()));
        }
        return consumers;
    }

    private QueuedMessageHandler handler(QueueBenchmark settings, Harvest harvest, boolean neutral) {
        var failureSelector = new AtomicLong();
        var failurePercent = neutral ? 0 : settings.getFailurePercent();
        return queuedMessage -> {
            if (queuedMessage.getPayload() instanceof BenchmarkMessage body) {
                // Latency is measured against the intended enqueue time carried in the payload, so a
                // producer that fell behind is charged for the delay rather than hiding it.
                harvest.endToEnd.record(body.intendedAtNanos(), body.intendedAtNanos(), System.nanoTime());
            }
            if (failurePercent > 0
                && Math.floorMod(failureSelector.getAndIncrement(), 100L) < failurePercent) {
                harvest.handlerFailures.increment();
                throw new IllegalStateException("Injected benchmark failure");
            }
            harvest.messagesHandled.increment();
        };
    }

    private void stopConsumers(List<DurableQueueConsumer> consumers) {
        consumers.forEach(consumer -> {
            try {
                consumer.stop();
            } catch (RuntimeException e) {
                log.warn("Failed to stop consumer", e);
            }
        });
    }

    /**
     * Truncate rather than purge between runs.
     * <p>
     * {@code purgeQueue} deletes rows but leaves the dead tuples and index bloat behind, so a run
     * inherits the table condition its predecessors left. That is not a hypothetical: running the
     * profile suite back to back, a control profile measured 1 439 messages a second against 7 090
     * for a run with byte-for-byte identical configuration, purely because of where it fell in the
     * sequence. {@code TRUNCATE} reclaims the space immediately, and the {@code ANALYZE} that follows
     * stops the planner working from statistics describing the previous run's table.
     * <p>
     * Bloat sensitivity is itself worth measuring — but by the soak profile, deliberately, not as an
     * uncontrolled variable contaminating every other comparison.
     * <p>
     * <b>Deliberately no {@code ANALYZE}.</b> The first version of this method ran one, on the
     * reasoning that stale statistics were another uncontrolled variable. It made things far worse:
     * {@code ANALYZE} on a table that has just been truncated records that the table is empty, and
     * the planner then chooses plans for an empty table while the run proceeds to fill it with tens
     * of thousands of rows. Throughput collapsed from 8 000 messages a second in the first
     * repetition to 1 500 in the third, and the interquartile range went to 200%. Letting autoanalyze
     * behave as it does in production is both more realistic and more stable.
     */
    private void resetQueueTable() {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE " + QUEUE_TABLE_NAME);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to reset the queue table between runs", e);
        }
    }

    /**
     * Give the planner statistics that describe the table the measured run will see.
     * <p>
     * Without this the results are bimodal — roughly 1 600 or 9 500 messages a second and little in
     * between, which is the signature of the fetch query flipping between a sequential scan and an
     * index scan rather than of any gradual contention. Whether autoanalyze happens to fire early
     * or late in a six-second run then decides which plan most of that run uses.
     * <p>
     * Worth noting as more than a harness concern: it means the existing implementation's fetch
     * performance depends on autoanalyze having caught up, so a freshly deployed or freshly purged
     * queue table can sit in a materially slower plan until it does.
     */
    private void analyzeQueueTable() {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("ANALYZE " + QUEUE_TABLE_NAME);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to analyze the queue table", e);
        }
    }

    private static List<QueueName> queueNames(int queueCount) {
        var names = new ArrayList<QueueName>(Math.max(1, queueCount));
        for (var index = 0; index < Math.max(1, queueCount); index++) {
            names.add(QueueName.of("perflab-queue-" + index));
        }
        return names;
    }

    /**
     * Carries the intended enqueue time through the queue so the handler can compute a
     * coordinated-omission-free latency. Three components rather than one, deliberately: a
     * single-property payload is read by Jackson 3 as a delegating creator and comes back null
     * without an error.
     */
    public record BenchmarkMessage(long intendedAtNanos, String payload) {
    }

    private static final class Harvest {
        private final LatencyRecorder                 endToEnd        = new LatencyRecorder("endToEnd");
        private final LongAdder                       messagesQueued  = new LongAdder();
        private final LongAdder                       messagesHandled = new LongAdder();
        private final LongAdder               handlerFailures           = new LongAdder();
        private final LongAdder               producerBackpressureWaits = new LongAdder();
        private final Map<String, AtomicLong> keyOrder                  = new ConcurrentHashMap<>();
    }
}
