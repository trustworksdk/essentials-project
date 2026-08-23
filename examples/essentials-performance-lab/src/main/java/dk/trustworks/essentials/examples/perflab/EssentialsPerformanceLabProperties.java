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

package dk.trustworks.essentials.examples.perflab;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "essentials.lab")
public class EssentialsPerformanceLabProperties {

    private Mode mode = Mode.SHOWCASE;
    private String scenario = "catalog";
    private Duration warmup = Duration.ofSeconds(10);
    private Duration duration = Duration.ofSeconds(30);
    private int producerThreads = 4;
    private int subscriberCount = 5;
    private int queueCount = 10;
    private int aggregateCardinality = 1_000;
    private long randomSeed = 42L;
    private int appendMaxAttempts = 3;
    private Duration appendRetryBackoff = Duration.ofMillis(2);
    private String metricsOutputFile;
    /**
     * Artificial delay applied inside each subscriber handler, in milliseconds.
     * Used by the {@code backpressure} scenario to simulate a slow downstream consumer and
     * validate that the CDC pipeline's bounded buffers hold under sustained producer pressure.
     * Default {@code 0} means no delay — the baseline scenarios run at full subscriber speed.
     */
    private long subscriberHandlerDelayMs = 0;
    /**
     * Target aggregate production rate across all producer threads, in events per second.
     * {@code 0.0} (default) means unthrottled — each producer appends as fast as the event store
     * allows.
     * <p>
     * Primarily used by the {@code backpressure} scenario: with a slow subscriber, an unthrottled
     * producer accumulates a backlog that takes orders of magnitude longer to drain than the
     * measurement window. Setting a rate proportional to the subscriber's drain capacity
     * (e.g. {@code 2 × 1000 / handlerDelayMs}) keeps the pressure real but bounded.
     * <p>
     * Fractional values are supported so truly-idle workloads can be expressed precisely:
     * {@code 0.1} = 1 event every 10 seconds, {@code 0.0167} ≈ 1 event/minute. This matters
     * for the S1 NOTIFY-driven wake-up measurement, where the design point is workloads with
     * inter-arrival ≫ maxDelay — impossible to express with integer Hz.
     */
    private double producerRateHz = 0.0d;

    /**
     * Cadence at which {@code SlotLagBoundedScenario} samples {@code pg_replication_slots}
     * and the framework's {@code essentials.cdc.slot.*} gauges. Default {@code PT5S} —
     * frequent enough to spot mid-run lag spikes, rare enough that the sampling itself
     * doesn't load the database.
     */
    private Duration slotLagSampleInterval = Duration.ofSeconds(5);

    /**
     * Pass-criterion threshold for {@code SlotLagBoundedScenario}: the maximum
     * {@code pg_wal_lsn_diff(current, confirmed_flush)} observed across the run must stay
     * under this value. Default {@code 100 MiB} — comfortable headroom for a few seconds
     * of buffered WAL during dispatcher tick gaps; tighten when validating lower-volume
     * profiles, raise for stress tests.
     */
    private long slotLagMaxBytes = 100L * 1024L * 1024L;

    /**
     * Number of malformed inbox rows {@code PoisonFloodEnduranceScenario} injects via
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcInboxRepository#insertRaw insertRaw}
     * at scenario start. Default {@code 100} — large enough to detect counting bugs, small
     * enough that the dispatcher can quarantine them all within a typical scenario duration.
     * Raise to validate the gauge at scale; {@code 0} disables injection entirely (useful as
     * a control run when comparing two passes).
     */
    private int poisonFloodCount = 100;

    /**
     * Comma-separated {@code parallelConsumers} values swept by {@code VirtualThreadsQueueScenario}.
     * Default {@code 8,32,128} — 8 is a realistic production consumer width, 32 is where a platform-thread
     * pool starts to look expensive, and 128 is past the point where an OS thread per in-flight message
     * stops being reasonable.
     */
    private String virtualThreadsParallelConsumers = "8,32,128";

    /**
     * Messages queued up-front per case in {@code VirtualThreadsQueueScenario}. Needs to be large enough
     * that the fixed cost of starting the consumer and ramping the fetcher's worker slots is small against
     * the drain window, and small enough that the slowest case (DB-bound, low parallelism) still finishes.
     */
    private int virtualThreadsMessagesPerCase = 600;

    /**
     * Simulated per-message handler work in {@code VirtualThreadsQueueScenario} — a sleep in
     * {@code SLEEP} mode, a {@code pg_sleep} inside a unit of work in {@code DB} mode. This is the blocking
     * duration the thread-type comparison is about, so it must dominate the queue's own per-message
     * overhead.
     */
    private Duration virtualThreadsHandlerDelay = Duration.ofMillis(50);

    /**
     * Which handler shapes {@code VirtualThreadsQueueScenario} runs: {@code SLEEP} (blocks without holding
     * a pooled JDBC connection), {@code DB} (blocks while holding one), or {@code BOTH} (default). Running
     * only one of the two produces a misleading headline — see the scenario javadoc.
     */
    private String virtualThreadsHandlerMode = "BOTH";

    /**
     * How many times {@code VirtualThreadsQueueScenario} repeats each (handler mode, parallelism, executor
     * kind) case. Default {@code 1} keeps the smoke path short; measurement runs need considerably more.
     * <p>
     * This is not optional rigour: a single measurement of this workload has a run-to-run spread wide
     * enough to reverse the sign of the platform-versus-virtual difference, so a one-shot comparison
     * reports noise as a result. The scenario reduces repetitions to a median and reports the observed
     * range next to it.
     */
    private int virtualThreadsRepetitions = 1;

    /**
     * Comma-separated ordered-message fractions swept by {@code QueueDesignAbScenario}. {@code 0.0} is a
     * pure unordered backlog, {@code 1.0} pure ordered; the default {@code 0.0,0.5,1.0} brackets the range
     * so the cost of the ordered per-key barrier can be read off the difference between the ends.
     */
    private String queueDesignOrderedFractions = "0.0,0.5,1.0";

    /**
     * Parallel consumers used by {@code QueueDesignAbScenario}. Fixed rather than swept — the scenario is
     * about the per-message database cost, and the parallelism sweep already lives in
     * {@code VirtualThreadsQueueScenario}.
     */
    private int queueDesignParallelConsumers = 32;

    /**
     * Messages queued up-front per case in {@code QueueDesignAbScenario}. Larger than the virtual-threads
     * default because there is no artificial handler delay here: with the handler doing nothing, a small
     * burst drains in a few fetcher ticks and measures ramp-up instead of steady state.
     */
    private int queueDesignMessagesPerCase = 4000;

    /**
     * Repetitions per case in {@code QueueDesignAbScenario}. Default {@code 1} for the smoke path;
     * measurement runs need more. See {@link #virtualThreadsRepetitions} for why this is not optional.
     */
    private int queueDesignRepetitions = 1;

    /**
     * Number of distinct ordered-message keys. Raised to at least {@code queueDesignParallelConsumers} by
     * the scenario: the per-key barrier allows one in-flight message per key, so with fewer keys than
     * consumers the ordered arm measures key contention rather than query cost.
     */
    private int queueDesignOrderedKeyCount = 64;

    /**
     * How often the batched-ack arm flushes buffered acknowledgements. Must stay well below the durable
     * queues' {@code messageHandlingTimeout} (30s by default), or buffered-but-unflushed messages start
     * being reset as stuck and redelivered.
     */
    private Duration queueDesignAckFlushInterval = Duration.ofMillis(50);

    /**
     * Flush the batched-ack arm early once this many acknowledgements are buffered, so the batch size does
     * not grow without bound at high throughput.
     */
    private int queueDesignAckMaxBatchSize = 200;

    /**
     * Recorded verbatim into the scenario output to label which fetch-query strategy the run used. It cannot
     * be read back from {@code PostgresqlDurableQueues}, and it is fixed at construction, so the benchmark
     * harness passes the value it set via {@code essentials.durable-queues.use-ordered-unordered-query}.
     */
    private String queueDesignUseOrderedUnorderedQueryLabel = "unknown";

    /**
     * Messages written per case by {@code QueueSchemaWriteCostScenario}. Needs to be large enough that the
     * table outgrows shared buffers' comfortable range and index maintenance actually costs something —
     * every earlier queue measurement ran at 4000 rows, which is precisely the scale at which this effect is
     * invisible.
     */
    private int schemaWriteCostMessages = 200_000;

    /**
     * Rows claimed and acknowledged per statement in {@code QueueSchemaWriteCostScenario}. Batched so
     * per-statement overhead does not swamp the index-maintenance signal being measured.
     */
    private int schemaWriteCostClaimBatchSize = 500;

    /**
     * Repetitions per case in {@code QueueSchemaWriteCostScenario}.
     */
    private int schemaWriteCostRepetitions = 3;

    /**
     * Distinct ordered keys used by the ORDERED arm of {@code QueueSchemaWriteCostScenario}.
     */
    private int schemaWriteCostOrderedKeyCount = 1_000;

    /**
     * Messages drained per case by {@code QueueFrameworkOverheadScenario}. An order of magnitude below
     * {@link #schemaWriteCostMessages} on purpose: the per-message arms pay two transactions per message, so
     * the same volume would make the run an order of magnitude longer without sharpening a ratio that is
     * already stable — this scenario measures a per-message constant, not an effect that only appears at
     * scale.
     */
    private int frameworkOverheadMessages = 20_000;

    /**
     * Rows claimed per statement by the batch-claiming arms of {@code QueueFrameworkOverheadScenario}, and
     * the enqueue chunk size for its component arms. Matches
     * {@link #schemaWriteCostClaimBatchSize} so the {@code RAW_BATCHED} baseline is directly comparable with
     * the write-cost scenario's arms.
     */
    private int frameworkOverheadClaimBatchSize = 500;

    /**
     * Repetitions per case in {@code QueueFrameworkOverheadScenario}. A discarded warmup case runs per arm
     * regardless.
     */
    private int frameworkOverheadRepetitions = 3;

    /**
     * Messages per case in {@code QueueOrderedRunLengthScenario}.
     */
    private int runLengthMessages = 50_000;

    /**
     * Rows claimed per statement. This is the batch capacity the key cardinality is swept against: run length
     * can only pay when the ready keys are fewer than the batch can hold, because the barrier returns at most
     * one row per key per round.
     */
    private int runLengthClaimBatchSize = 500;

    private int runLengthRepetitions = 2;

    /**
     * Ordered key counts to sweep, comma-separated. The default brackets the batch size from far below to far
     * above: at 8 keys the barrier is starved to 8 rows a round, at 2000 there is always breadth to fill the
     * batch and a run should add nothing.
     */
    private String runLengthKeyCounts = "8,64,500,2000";

    /**
     * Cursor run lengths to sweep, comma-separated. {@code 1} isolates the cursor's own claim cost from the run
     * effect, which is what makes the rest of the sweep attributable.
     */
    private String runLengthRunLengths = "1,4,16,64";

    /**
     * Messages per case in {@code QueueStorageLayoutScenario}.
     */
    private int storageLayoutMessages = 40_000;

    /**
     * Queues per case. More than one is required: partitioning by {@code queue_name} with a single queue gives
     * one partition and measures nothing.
     */
    private int storageLayoutQueueCount = 8;

    /**
     * Percentage of messages dead-lettered before the drain. Non-zero on purpose — with no dead letters the
     * side table stays empty and the arm only measures one fewer index column, missing the claim that long-lived
     * dead-letter rows occupy pages in the hot table.
     */
    private int storageLayoutDeadLetterPercent = 5;

    private int storageLayoutRepetitions = 2;

    /**
     * Insert-then-drain cycles against one table in {@code QueueAutovacuumScenario}. The signal is degradation
     * across cycles, so this must be large enough for dead tuples to accumulate if they are going to.
     */
    private int autovacuumCycles = 12;

    private int autovacuumMessagesPerCycle = 20_000;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public Duration getWarmup() {
        return warmup;
    }

    public void setWarmup(Duration warmup) {
        this.warmup = warmup;
    }

    public Duration getDuration() {
        return duration;
    }

    public void setDuration(Duration duration) {
        this.duration = duration;
    }

    public int getProducerThreads() {
        return producerThreads;
    }

    public void setProducerThreads(int producerThreads) {
        this.producerThreads = producerThreads;
    }

    public int getSubscriberCount() {
        return subscriberCount;
    }

    public void setSubscriberCount(int subscriberCount) {
        this.subscriberCount = subscriberCount;
    }

    public int getQueueCount() {
        return queueCount;
    }

    public void setQueueCount(int queueCount) {
        this.queueCount = queueCount;
    }

    public int getAggregateCardinality() {
        return aggregateCardinality;
    }

    public void setAggregateCardinality(int aggregateCardinality) {
        this.aggregateCardinality = aggregateCardinality;
    }

    public long getRandomSeed() {
        return randomSeed;
    }

    public void setRandomSeed(long randomSeed) {
        this.randomSeed = randomSeed;
    }

    public int getAppendMaxAttempts() {
        return appendMaxAttempts;
    }

    public void setAppendMaxAttempts(int appendMaxAttempts) {
        this.appendMaxAttempts = appendMaxAttempts;
    }

    public Duration getAppendRetryBackoff() {
        return appendRetryBackoff;
    }

    public void setAppendRetryBackoff(Duration appendRetryBackoff) {
        this.appendRetryBackoff = appendRetryBackoff;
    }

    public String getMetricsOutputFile() {
        return metricsOutputFile;
    }

    public void setMetricsOutputFile(String metricsOutputFile) {
        this.metricsOutputFile = metricsOutputFile;
    }

    public long getSubscriberHandlerDelayMs() {
        return subscriberHandlerDelayMs;
    }

    public void setSubscriberHandlerDelayMs(long subscriberHandlerDelayMs) {
        this.subscriberHandlerDelayMs = subscriberHandlerDelayMs;
    }

    public double getProducerRateHz() {
        return producerRateHz;
    }

    public void setProducerRateHz(double producerRateHz) {
        this.producerRateHz = producerRateHz;
    }

    public Duration getSlotLagSampleInterval() {
        return slotLagSampleInterval;
    }

    public void setSlotLagSampleInterval(Duration slotLagSampleInterval) {
        this.slotLagSampleInterval = slotLagSampleInterval;
    }

    public long getSlotLagMaxBytes() {
        return slotLagMaxBytes;
    }

    public void setSlotLagMaxBytes(long slotLagMaxBytes) {
        this.slotLagMaxBytes = slotLagMaxBytes;
    }

    public int getPoisonFloodCount() {
        return poisonFloodCount;
    }

    public void setPoisonFloodCount(int poisonFloodCount) {
        this.poisonFloodCount = poisonFloodCount;
    }

    public String getVirtualThreadsParallelConsumers() {
        return virtualThreadsParallelConsumers;
    }

    public void setVirtualThreadsParallelConsumers(String virtualThreadsParallelConsumers) {
        this.virtualThreadsParallelConsumers = virtualThreadsParallelConsumers;
    }

    public int getVirtualThreadsMessagesPerCase() {
        return virtualThreadsMessagesPerCase;
    }

    public void setVirtualThreadsMessagesPerCase(int virtualThreadsMessagesPerCase) {
        this.virtualThreadsMessagesPerCase = virtualThreadsMessagesPerCase;
    }

    public Duration getVirtualThreadsHandlerDelay() {
        return virtualThreadsHandlerDelay;
    }

    public void setVirtualThreadsHandlerDelay(Duration virtualThreadsHandlerDelay) {
        this.virtualThreadsHandlerDelay = virtualThreadsHandlerDelay;
    }

    public String getVirtualThreadsHandlerMode() {
        return virtualThreadsHandlerMode;
    }

    public void setVirtualThreadsHandlerMode(String virtualThreadsHandlerMode) {
        this.virtualThreadsHandlerMode = virtualThreadsHandlerMode;
    }

    public int getVirtualThreadsRepetitions() {
        return virtualThreadsRepetitions;
    }

    public void setVirtualThreadsRepetitions(int virtualThreadsRepetitions) {
        this.virtualThreadsRepetitions = virtualThreadsRepetitions;
    }

    public String getQueueDesignOrderedFractions() {
        return queueDesignOrderedFractions;
    }

    public void setQueueDesignOrderedFractions(String queueDesignOrderedFractions) {
        this.queueDesignOrderedFractions = queueDesignOrderedFractions;
    }

    public int getQueueDesignParallelConsumers() {
        return queueDesignParallelConsumers;
    }

    public void setQueueDesignParallelConsumers(int queueDesignParallelConsumers) {
        this.queueDesignParallelConsumers = queueDesignParallelConsumers;
    }

    public int getQueueDesignMessagesPerCase() {
        return queueDesignMessagesPerCase;
    }

    public void setQueueDesignMessagesPerCase(int queueDesignMessagesPerCase) {
        this.queueDesignMessagesPerCase = queueDesignMessagesPerCase;
    }

    public int getQueueDesignRepetitions() {
        return queueDesignRepetitions;
    }

    public void setQueueDesignRepetitions(int queueDesignRepetitions) {
        this.queueDesignRepetitions = queueDesignRepetitions;
    }

    public int getQueueDesignOrderedKeyCount() {
        return queueDesignOrderedKeyCount;
    }

    public void setQueueDesignOrderedKeyCount(int queueDesignOrderedKeyCount) {
        this.queueDesignOrderedKeyCount = queueDesignOrderedKeyCount;
    }

    public Duration getQueueDesignAckFlushInterval() {
        return queueDesignAckFlushInterval;
    }

    public void setQueueDesignAckFlushInterval(Duration queueDesignAckFlushInterval) {
        this.queueDesignAckFlushInterval = queueDesignAckFlushInterval;
    }

    public int getQueueDesignAckMaxBatchSize() {
        return queueDesignAckMaxBatchSize;
    }

    public void setQueueDesignAckMaxBatchSize(int queueDesignAckMaxBatchSize) {
        this.queueDesignAckMaxBatchSize = queueDesignAckMaxBatchSize;
    }

    public String getQueueDesignUseOrderedUnorderedQueryLabel() {
        return queueDesignUseOrderedUnorderedQueryLabel;
    }

    public void setQueueDesignUseOrderedUnorderedQueryLabel(String queueDesignUseOrderedUnorderedQueryLabel) {
        this.queueDesignUseOrderedUnorderedQueryLabel = queueDesignUseOrderedUnorderedQueryLabel;
    }

    public int getSchemaWriteCostMessages() {
        return schemaWriteCostMessages;
    }

    public void setSchemaWriteCostMessages(int schemaWriteCostMessages) {
        this.schemaWriteCostMessages = schemaWriteCostMessages;
    }

    public int getSchemaWriteCostClaimBatchSize() {
        return schemaWriteCostClaimBatchSize;
    }

    public void setSchemaWriteCostClaimBatchSize(int schemaWriteCostClaimBatchSize) {
        this.schemaWriteCostClaimBatchSize = schemaWriteCostClaimBatchSize;
    }

    public int getSchemaWriteCostRepetitions() {
        return schemaWriteCostRepetitions;
    }

    public void setSchemaWriteCostRepetitions(int schemaWriteCostRepetitions) {
        this.schemaWriteCostRepetitions = schemaWriteCostRepetitions;
    }

    public int getSchemaWriteCostOrderedKeyCount() {
        return schemaWriteCostOrderedKeyCount;
    }

    public void setSchemaWriteCostOrderedKeyCount(int schemaWriteCostOrderedKeyCount) {
        this.schemaWriteCostOrderedKeyCount = schemaWriteCostOrderedKeyCount;
    }

    public int getFrameworkOverheadMessages() {
        return frameworkOverheadMessages;
    }

    public void setFrameworkOverheadMessages(int frameworkOverheadMessages) {
        this.frameworkOverheadMessages = frameworkOverheadMessages;
    }

    public int getFrameworkOverheadClaimBatchSize() {
        return frameworkOverheadClaimBatchSize;
    }

    public void setFrameworkOverheadClaimBatchSize(int frameworkOverheadClaimBatchSize) {
        this.frameworkOverheadClaimBatchSize = frameworkOverheadClaimBatchSize;
    }

    public int getRunLengthMessages() {
        return runLengthMessages;
    }

    public void setRunLengthMessages(int runLengthMessages) {
        this.runLengthMessages = runLengthMessages;
    }

    public int getRunLengthClaimBatchSize() {
        return runLengthClaimBatchSize;
    }

    public void setRunLengthClaimBatchSize(int runLengthClaimBatchSize) {
        this.runLengthClaimBatchSize = runLengthClaimBatchSize;
    }

    public int getRunLengthRepetitions() {
        return runLengthRepetitions;
    }

    public void setRunLengthRepetitions(int runLengthRepetitions) {
        this.runLengthRepetitions = runLengthRepetitions;
    }

    public String getRunLengthKeyCounts() {
        return runLengthKeyCounts;
    }

    public void setRunLengthKeyCounts(String runLengthKeyCounts) {
        this.runLengthKeyCounts = runLengthKeyCounts;
    }

    public String getRunLengthRunLengths() {
        return runLengthRunLengths;
    }

    public void setRunLengthRunLengths(String runLengthRunLengths) {
        this.runLengthRunLengths = runLengthRunLengths;
    }

    public int getStorageLayoutMessages() {
        return storageLayoutMessages;
    }

    public void setStorageLayoutMessages(int storageLayoutMessages) {
        this.storageLayoutMessages = storageLayoutMessages;
    }

    public int getStorageLayoutQueueCount() {
        return storageLayoutQueueCount;
    }

    public void setStorageLayoutQueueCount(int storageLayoutQueueCount) {
        this.storageLayoutQueueCount = storageLayoutQueueCount;
    }

    public int getStorageLayoutDeadLetterPercent() {
        return storageLayoutDeadLetterPercent;
    }

    public void setStorageLayoutDeadLetterPercent(int storageLayoutDeadLetterPercent) {
        this.storageLayoutDeadLetterPercent = storageLayoutDeadLetterPercent;
    }

    public int getStorageLayoutRepetitions() {
        return storageLayoutRepetitions;
    }

    public void setStorageLayoutRepetitions(int storageLayoutRepetitions) {
        this.storageLayoutRepetitions = storageLayoutRepetitions;
    }

    public int getAutovacuumCycles() {
        return autovacuumCycles;
    }

    public void setAutovacuumCycles(int autovacuumCycles) {
        this.autovacuumCycles = autovacuumCycles;
    }

    public int getAutovacuumMessagesPerCycle() {
        return autovacuumMessagesPerCycle;
    }

    public void setAutovacuumMessagesPerCycle(int autovacuumMessagesPerCycle) {
        this.autovacuumMessagesPerCycle = autovacuumMessagesPerCycle;
    }

    public int getFrameworkOverheadRepetitions() {
        return frameworkOverheadRepetitions;
    }

    public void setFrameworkOverheadRepetitions(int frameworkOverheadRepetitions) {
        this.frameworkOverheadRepetitions = frameworkOverheadRepetitions;
    }

    public enum Mode {
        SHOWCASE,
        BENCHMARK
    }
}
