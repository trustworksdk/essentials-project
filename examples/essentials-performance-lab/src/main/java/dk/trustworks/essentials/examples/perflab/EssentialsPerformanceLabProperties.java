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
import java.util.List;

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

    public SeqGap getSeqGap() {
        return seqGap;
    }

    public QueueBenchmark getQueueBenchmark() {
        return queueBenchmark;
    }

    public enum Mode {
        SHOWCASE,
        BENCHMARK
    }

    private final SeqGap         seqGap         = new SeqGap();
    private final QueueBenchmark queueBenchmark = new QueueBenchmark();

    /**
     * Settings for the {@code queue-benchmark} scenario, which captures the baseline the
     * next-generation queue design has to beat.
     * <p>
     * One scenario covers several workload profiles rather than one class per profile, because they
     * differ only in configuration: saturating throughput is {@code producerRateHz=0}, the latency
     * profile is a low rate, the ordered profile sets a key cardinality, the failure profile sets a
     * failure percentage, the idle-cost profile creates many queues and produces to few, and the
     * soak profile is simply a long duration.
     */
    public static class QueueBenchmark {
        /**
         * One arm per named configuration of the existing implementation. {@code centralized} is the
         * default single-fetcher topology; {@code traditional} is the per-consumer polling one.
         * Comparing them is worth doing on its own — the plan assumes centralized is the faster
         * baseline, and that assumption has never been measured here.
         */
        private List<String> arms              = List.of("centralized", "traditional");
        private Workload     workload          = Workload.UNORDERED;
        private int          keyCardinality    = 1_000;
        /**
         * How many of {@code essentials.lab.queue-count} queues actually receive messages. Leaving
         * the rest idle is what exposes the cost of polling queues that have nothing in them —
         * the case that dominates real deployments.
         */
        private int      busyQueues        = 1;
        private int      parallelConsumers = 5;
        /**
         * Percentage of handled messages whose handler throws, exercising redelivery and the dead
         * letter path. The design claims failure handling can be moved off the hot path; that claim
         * needs a baseline showing what it costs today.
         */
        private int      failurePercent    = 0;
        private Duration pollingInterval   = Duration.ofMillis(20);
        private Duration redeliveryDelay   = Duration.ofMillis(100);
        private int      maxRedeliveries   = 3;
        private int      repetitions       = 3;
        private int      payloadBytes      = 200;
        private Duration drainTimeout      = Duration.ofSeconds(60);
        /**
         * Maximum number of enqueued-but-unhandled messages before producers pause.
         * <p>
         * Without this the run is not a throughput measurement at all. Unthrottled producers
         * outrun the consumers within seconds, and from then on every latency sample is the time a
         * message spent waiting in a backlog rather than the time the system took to deliver it —
         * the first run of this scenario reported a p50 of 16 seconds for exactly that reason.
         * Bounding in-flight work keeps the system in the steady state the numbers are supposed to
         * describe.
         */
        private int maxInFlight = 5_000;

        public List<String> getArms() {
            return arms;
        }

        public void setArms(List<String> arms) {
            this.arms = arms;
        }

        public Workload getWorkload() {
            return workload;
        }

        public void setWorkload(Workload workload) {
            this.workload = workload;
        }

        public int getKeyCardinality() {
            return keyCardinality;
        }

        public void setKeyCardinality(int keyCardinality) {
            this.keyCardinality = keyCardinality;
        }

        public int getBusyQueues() {
            return busyQueues;
        }

        public void setBusyQueues(int busyQueues) {
            this.busyQueues = busyQueues;
        }

        public int getParallelConsumers() {
            return parallelConsumers;
        }

        public void setParallelConsumers(int parallelConsumers) {
            this.parallelConsumers = parallelConsumers;
        }

        public int getFailurePercent() {
            return failurePercent;
        }

        public void setFailurePercent(int failurePercent) {
            this.failurePercent = failurePercent;
        }

        public Duration getPollingInterval() {
            return pollingInterval;
        }

        public void setPollingInterval(Duration pollingInterval) {
            this.pollingInterval = pollingInterval;
        }

        public Duration getRedeliveryDelay() {
            return redeliveryDelay;
        }

        public void setRedeliveryDelay(Duration redeliveryDelay) {
            this.redeliveryDelay = redeliveryDelay;
        }

        public int getMaxRedeliveries() {
            return maxRedeliveries;
        }

        public void setMaxRedeliveries(int maxRedeliveries) {
            this.maxRedeliveries = maxRedeliveries;
        }

        public int getRepetitions() {
            return repetitions;
        }

        public void setRepetitions(int repetitions) {
            this.repetitions = repetitions;
        }

        public int getPayloadBytes() {
            return payloadBytes;
        }

        public void setPayloadBytes(int payloadBytes) {
            this.payloadBytes = payloadBytes;
        }

        public Duration getDrainTimeout() {
            return drainTimeout;
        }

        public void setDrainTimeout(Duration drainTimeout) {
            this.drainTimeout = drainTimeout;
        }

        public int getMaxInFlight() {
            return maxInFlight;
        }

        public void setMaxInFlight(int maxInFlight) {
            this.maxInFlight = maxInFlight;
        }

        public enum Workload {
            UNORDERED,
            ORDERED
        }
    }

    /**
     * Settings for the {@code seq-gap} scenario, which measures how often a cursor-based reader
     * observes a hole in a per-shard sequence, and how long those holes take to resolve.
     * <p>
     * A hole appears when one producer allocates sequence value <em>n</em> and a second allocates
     * <em>n+1</em> and commits first: a reader following the sequence sees <em>n+1</em> before
     * <em>n</em> exists. The proposed queue design deliberately does not stall its cursor on such a
     * hole — it keeps delivering and chases the missing value separately — so the questions this
     * scenario has to answer are how frequently holes occur, how quickly they resolve, and whether
     * anything is ever lost.
     */
    public static class SeqGap {
        private int shards = 8;
        /**
         * One arm per value. Each is how long a producer holds its transaction open after inserting
         * and before committing, simulating an enqueue that has joined a longer business
         * transaction — the outbox case, and the one that widens the window in which holes form.
         * {@code 0} is the plain autocommit enqueue.
         */
        private List<Long> txHoldMillis = List.of(0L, 10L);
        private int        batchSize    = 100;
        /**
         * How long the reader waits before re-querying for a missing sequence value. Too eager and
         * it spends the run on point lookups that were always going to miss; too slow and it adds
         * latency to exactly the messages that were already unlucky.
         */
        private Duration chaseDelay = Duration.ofMillis(5);
        /**
         * How long a missing sequence value is chased before being declared permanently absent —
         * an aborted transaction, or a value the sequence cache burned. Must exceed the longest
         * expected enqueue transaction or live messages will be abandoned.
         */
        private Duration gapExpiry    = Duration.ofSeconds(30);
        private Duration drainTimeout = Duration.ofSeconds(30);
        private int      repetitions  = 3;
        private int      payloadBytes = 200;

        public int getShards() {
            return shards;
        }

        public void setShards(int shards) {
            this.shards = shards;
        }

        public List<Long> getTxHoldMillis() {
            return txHoldMillis;
        }

        public void setTxHoldMillis(List<Long> txHoldMillis) {
            this.txHoldMillis = txHoldMillis;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getChaseDelay() {
            return chaseDelay;
        }

        public void setChaseDelay(Duration chaseDelay) {
            this.chaseDelay = chaseDelay;
        }

        public Duration getGapExpiry() {
            return gapExpiry;
        }

        public void setGapExpiry(Duration gapExpiry) {
            this.gapExpiry = gapExpiry;
        }

        public Duration getDrainTimeout() {
            return drainTimeout;
        }

        public void setDrainTimeout(Duration drainTimeout) {
            this.drainTimeout = drainTimeout;
        }

        public int getRepetitions() {
            return repetitions;
        }

        public void setRepetitions(int repetitions) {
            this.repetitions = repetitions;
        }

        public int getPayloadBytes() {
            return payloadBytes;
        }

        public void setPayloadBytes(int payloadBytes) {
            this.payloadBytes = payloadBytes;
        }
    }
}
