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

import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties.SeqGap;
import dk.trustworks.essentials.examples.perflab.harness.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Measures the load-bearing assumption of the proposed shard-owned queue design: that a reader can
 * follow a per-shard sequence with a cursor, write nothing when it consumes, and still never lose a
 * message.
 * <p>
 * The hazard is ordinary and unavoidable. Producer A allocates sequence value <em>n</em>, producer B
 * allocates <em>n+1</em>, and B commits first. A reader following {@code seq > cursor} sees
 * <em>n+1</em>, advances past it, and only then does A commit — leaving <em>n</em> behind the
 * cursor forever. The design's answer is to keep the cursor moving and chase the missing value
 * separately, on the argument that holes are rare and resolve in milliseconds. That argument is
 * what this scenario exists to confirm or destroy.
 * <p>
 * If the numbers come back saying holes are common and slow to resolve, the write-free fast path
 * collapses back to a per-message claim write and the design's headline cost of one insert and one
 * delete goes with it. Better to learn that from a day of measurement than from Phase 3.
 * <p>
 * One arm runs per configured transaction-hold duration, because hold time is what governs the
 * width of the window in which holes form. Zero models a plain autocommit enqueue; a non-zero value
 * models an enqueue that has joined a longer business transaction, which is the outbox case and the
 * one the design is most exposed to.
 * <p>
 * The result to read first is {@code invariantNoMessageLost}. Everything else is performance; that
 * one is correctness, and a fast run that reports it false is a failed run.
 */
@Component
public class SequenceGapScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(SequenceGapScenario.class);

    static final String TABLE_NAME    = "perflab_seq_gap";
    static final String SEQUENCE_STEM = "perflab_seq_gap_shard_";

    private final DataSource dataSource;

    public SequenceGapScenario(DataSource dataSource) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
    }

    @Override
    public String name() {
        return "seq-gap";
    }

    @Override
    public String description() {
        return "Measures per-shard sequence hole frequency, hole resolution latency and message loss for a cursor-based reader";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        requireNonNull(properties, "No properties provided");
        var settings = properties.getSeqGap();
        createSchema(settings.getShards());

        var environment = PgSnapshot.captureEnvironment(dataSource);
        log.info("Environment: {}", environment);

        var arms = new LinkedHashMap<String, java.util.function.IntFunction<RunResult>>();
        for (var holdMillis : settings.getTxHoldMillis()) {
            var armName = "txHold=" + holdMillis + "ms";
            arms.put(armName, repetition -> {
                try {
                    return measureOnce(armName, holdMillis, repetition, properties, environment);
                } catch (Exception e) {
                    throw new IllegalStateException("Arm '" + armName + "' repetition " + repetition + " failed", e);
                }
            });
        }

        var runner = new AbRunner(settings.getRepetitions());
        var results = runner.run(arms);
        var summaries = AbRunner.summarize(results);

        summaries.forEach(summary -> log.info("Arm '{}' over {} repetitions: throughput median={} IQR={}, responseTime p99 median={}us IQR={}us",
                                              summary.arm(),
                                              summary.repetitions(),
                                              String.format("%.1f/s", summary.throughputPerSecond().median()),
                                              String.format("%.1f", summary.throughputPerSecond().interQuartileRange()),
                                              String.format("%.0f", summary.responseTimeP99Micros().median()),
                                              String.format("%.0f", summary.responseTimeP99Micros().interQuartileRange())));

        results.forEach(result -> log.info("  {} rep {}: produced={} delivered={} holes={} resolved={} permanent={} lost={} holeRate/1k={} holeResolveP99={}us",
                                           result.arm(),
                                           result.repetition(),
                                           result.extra().get("messagesProduced"),
                                           result.extra().get("messagesDelivered"),
                                           result.extra().get("holesObserved"),
                                           result.extra().get("holesResolved"),
                                           result.extra().get("holesPermanent"),
                                           result.extra().get("messagesLost"),
                                           result.extra().get("holeRatePerThousandMessages"),
                                           result.extra().get("holeResolutionP99Micros")));

        RunResult.writeAll(properties.getMetricsOutputFile(),
                           Map.of("scenario", name(),
                                  "environment", environment,
                                  "summaries", summaries,
                                  "runs", results));
    }

    private RunResult measureOnce(String armName,
                                  long txHoldMillis,
                                  int repetition,
                                  EssentialsPerformanceLabProperties properties,
                                  Map<String, String> environment) throws Exception {
        var settings = properties.getSeqGap();

        // Warmup runs against the same schema, then everything is reset: JIT and the page cache are
        // warm, but no counter, histogram or sequence value survives into the measured window.
        if (!properties.getWarmup().isZero()) {
            runPhase(properties, txHoldMillis, properties.getWarmup().toMillis(), new Harvest(settings));
            resetSchema(settings.getShards());
        }

        var harvest = new Harvest(settings);
        var pgBefore = PgSnapshot.capture(dataSource, List.of(TABLE_NAME));
        var jvmBefore = JvmSnapshot.capture();
        var startedAt = Instant.now();
        var startNanos = System.nanoTime();

        runPhase(properties, txHoldMillis, properties.getDuration().toMillis(), harvest);

        var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        var pgAfter = PgSnapshot.capture(dataSource, List.of(TABLE_NAME));
        var jvmAfter = JvmSnapshot.capture();

        var notDelivered = undeliveredSequenceValues(harvest, settings.getShards());
        var delivered = harvest.messagesDelivered.sum();
        var produced = harvest.messagesProduced.sum();
        var holes = harvest.holesObserved.sum();

        var extra = new LinkedHashMap<String, Object>();
        extra.put("txHoldMillis", txHoldMillis);
        extra.put("shards", settings.getShards());
        extra.put("messagesProduced", produced);
        extra.put("messagesDelivered", delivered);
        extra.put("messagesLost", notDelivered);
        extra.put("invariantNoMessageLost", notDelivered == 0L);
        extra.put("holesObserved", holes);
        extra.put("holesResolved", harvest.holesResolved.sum());
        extra.put("holesPermanent", harvest.holesPermanent.sum());
        extra.put("holeRatePerThousandMessages", delivered == 0 ? 0.0d : holes * 1000.0d / delivered);
        extra.put("maxConcurrentPendingHoles", harvest.maxPendingHoles.get());
        extra.put("holeChaseQueries", harvest.holeChaseQueries.sum());
        extra.put("holeResolutionP99Micros", harvest.holeResolution.responseTimeSummary().p99Micros());

        var config = new LinkedHashMap<String, Object>();
        config.put("producerThreads", properties.getProducerThreads());
        config.put("producerRateHz", properties.getProducerRateHz());
        config.put("durationMillis", properties.getDuration().toMillis());
        config.put("warmupMillis", properties.getWarmup().toMillis());
        config.put("batchSize", settings.getBatchSize());
        config.put("chaseDelayMillis", settings.getChaseDelay().toMillis());
        config.put("payloadBytes", settings.getPayloadBytes());

        var result = new RunResult(name(),
                                   armName,
                                   repetition,
                                   startedAt,
                                   elapsedMillis,
                                   delivered,
                                   elapsedMillis == 0 ? 0.0d : delivered * 1000.0d / elapsedMillis,
                                   config,
                                   List.of(harvest.endToEnd.responseTimeSummary(),
                                           harvest.endToEnd.serviceTimeSummary(),
                                           harvest.holeResolution.responseTimeSummary()),
                                   pgAfter.deltaFrom(pgBefore),
                                   jvmAfter.deltaFrom(jvmBefore),
                                   environment,
                                   extra);
        resetSchema(settings.getShards());
        return result;
    }

    /**
     * Run producers and one reader per shard for {@code durationMillis}, then let the readers drain
     * whatever is still outstanding. Draining matters: without it, every message produced in the
     * last few milliseconds would be counted as lost.
     */
    private void runPhase(EssentialsPerformanceLabProperties properties,
                          long txHoldMillis,
                          long durationMillis,
                          Harvest harvest) throws Exception {
        var settings = properties.getSeqGap();
        var producing = new AtomicBoolean(true);
        var reading = new AtomicBoolean(true);
        var shardCursor = new AtomicInteger();

        var producerThreads = properties.getProducerThreads();
        var executor = Executors.newFixedThreadPool(producerThreads + settings.getShards());
        var producers = new ArrayList<Future<?>>(producerThreads);
        var readers = new ArrayList<Future<?>>(settings.getShards());
        try {
            for (var shard = 0; shard < settings.getShards(); shard++) {
                var shardId = shard;
                readers.add(executor.submit(() -> readShard(shardId, settings, harvest, reading)));
            }
            for (var producer = 0; producer < producerThreads; producer++) {
                producers.add(executor.submit(() -> produce(properties, txHoldMillis, harvest, producing, shardCursor)));
            }

            Thread.sleep(durationMillis);
            producing.set(false);
            for (var producer : producers) {
                producer.get(30, TimeUnit.SECONDS);
            }

            // Drain: readers keep going until nothing is outstanding, or the timeout expires.
            var drainDeadline = System.nanoTime() + settings.getDrainTimeout().toNanos();
            while (System.nanoTime() < drainDeadline
                   && harvest.messagesDelivered.sum() + harvest.holesPermanent.sum() < harvest.messagesProduced.sum()) {
                Thread.sleep(20L);
            }
            reading.set(false);
            for (var reader : readers) {
                reader.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Scenario executor did not terminate cleanly");
            }
        }
    }

    private void produce(EssentialsPerformanceLabProperties properties,
                         long txHoldMillis,
                         Harvest harvest,
                         AtomicBoolean producing,
                         AtomicInteger shardCursor) {
        var settings = properties.getSeqGap();
        var payload = new byte[settings.getPayloadBytes()];
        Arrays.fill(payload, (byte) 'x');

        // Intended start times come from a fixed schedule, never from "when the last one finished" —
        // that is what keeps the latency measurement free of coordinated omission.
        var rateHz = properties.getProducerRateHz();
        var intervalNanos = rateHz > 0.0d
                            ? (long) (1_000_000_000.0d * properties.getProducerThreads() / rateHz)
                            : 0L;
        var scheduleStartNanos = System.nanoTime();

        var sql = "INSERT INTO " + TABLE_NAME + " (shard, seq, intended_at_nanos, payload) VALUES (?, nextval(?::regclass), ?, ?)";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            var operation = 0L;
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
                var shard = Math.floorMod(shardCursor.getAndIncrement(), settings.getShards());
                statement.setShort(1, (short) shard);
                statement.setString(2, SEQUENCE_STEM + shard);
                statement.setLong(3, intendedNanos);
                statement.setBytes(4, payload);

                if (txHoldMillis > 0) {
                    connection.setAutoCommit(false);
                    statement.executeUpdate();
                    // Holding the transaction open is the whole point of this arm: it is what an
                    // enqueue joined to a business transaction actually does, and it is what widens
                    // the window in which another producer can allocate a later value and commit first.
                    Thread.sleep(txHoldMillis);
                    connection.commit();
                    connection.setAutoCommit(true);
                } else {
                    statement.executeUpdate();
                }
                harvest.messagesProduced.increment();
                operation++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (SQLException e) {
            log.error("Producer failed", e);
            throw new IllegalStateException("Producer failed", e);
        }
    }

    /**
     * One reader per shard, exactly as the design specifies — a shard has a single owner, so hole
     * tracking can live in memory and needs no coordination.
     */
    private void readShard(int shard, SeqGap settings, Harvest harvest, AtomicBoolean reading) {
        var cursor = 0L;
        var pendingHoles = new TreeMap<Long, Long>();
        var lastChaseNanos = 0L;
        var chaseDelayNanos = settings.getChaseDelay().toNanos();
        var expiryNanos = settings.getGapExpiry().toNanos();

        var readSql = "SELECT seq, intended_at_nanos FROM " + TABLE_NAME
                      + " WHERE shard = ? AND seq > ? ORDER BY seq LIMIT " + settings.getBatchSize();
        var chaseSql = "SELECT seq, intended_at_nanos FROM " + TABLE_NAME + " WHERE shard = ? AND seq = ANY(?)";

        try (var connection = dataSource.getConnection();
             var readStatement = connection.prepareStatement(readSql);
             var chaseStatement = connection.prepareStatement(chaseSql)) {
            while (reading.get()) {
                readStatement.setShort(1, (short) shard);
                readStatement.setLong(2, cursor);
                var rowsRead = 0;
                try (var resultSet = readStatement.executeQuery()) {
                    while (resultSet.next()) {
                        var seq = resultSet.getLong(1);
                        var intendedNanos = resultSet.getLong(2);
                        // Anything skipped over is a hole: the value was allocated by a transaction
                        // that has not committed yet. The cursor does NOT stop for it.
                        for (var missing = cursor + 1; missing < seq; missing++) {
                            if (pendingHoles.putIfAbsent(missing, System.nanoTime()) == null) {
                                harvest.holesObserved.increment();
                            }
                        }
                        deliver(harvest, shard, seq, intendedNanos);
                        cursor = seq;
                        rowsRead++;
                    }
                }
                harvest.maxPendingHoles.accumulateAndGet(pendingHoles.size(), Math::max);

                var now = System.nanoTime();
                if (!pendingHoles.isEmpty() && now - lastChaseNanos >= chaseDelayNanos) {
                    chaseHoles(shard, chaseStatement, connection, pendingHoles, harvest, expiryNanos);
                    lastChaseNanos = now;
                }
                if (rowsRead == 0) {
                    // Nothing new. A real consumer would be woken by NOTIFY here; the harness is
                    // measuring hole behaviour, not wake-up latency, so a short park is enough.
                    TimeUnit.MICROSECONDS.sleep(200L);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (SQLException e) {
            log.error("Reader for shard {} failed", shard, e);
            throw new IllegalStateException("Reader for shard " + shard + " failed", e);
        }
    }

    private void chaseHoles(int shard,
                            PreparedStatement chaseStatement,
                            Connection connection,
                            TreeMap<Long, Long> pendingHoles,
                            Harvest harvest,
                            long expiryNanos) throws SQLException {
        var candidates = pendingHoles.keySet().stream().limit(1000).toArray(Long[]::new);
        chaseStatement.setShort(1, (short) shard);
        chaseStatement.setArray(2, connection.createArrayOf("bigint", candidates));
        harvest.holeChaseQueries.increment();

        try (var resultSet = chaseStatement.executeQuery()) {
            while (resultSet.next()) {
                var seq = resultSet.getLong(1);
                var intendedNanos = resultSet.getLong(2);
                var firstMissedNanos = pendingHoles.remove(seq);
                if (firstMissedNanos != null) {
                    harvest.holeResolution.recordDuration(System.nanoTime() - firstMissedNanos);
                    harvest.holesResolved.increment();
                }
                deliver(harvest, shard, seq, intendedNanos);
            }
        }

        // A value that never appears was burned by a rollback or by sequence caching. Abandoning it
        // has to be bounded, or a single aborted transaction would keep a shard chasing forever.
        var now = System.nanoTime();
        pendingHoles.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > expiryNanos) {
                harvest.holesPermanent.increment();
                return true;
            }
            return false;
        });
    }

    private void deliver(Harvest harvest, int shard, long seq, long intendedNanos) {
        if (harvest.markDelivered(shard, seq)) {
            harvest.messagesDelivered.increment();
            harvest.endToEnd.record(intendedNanos, intendedNanos, System.nanoTime());
        }
    }

    /**
     * The correctness check. Every row committed to the table must have been handed to the reader
     * exactly once; anything present in the table but never delivered is a lost message, and a lost
     * message invalidates the design regardless of how fast the run was.
     */
    private long undeliveredSequenceValues(Harvest harvest, int shards) throws SQLException {
        var missing = 0L;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT seq FROM " + TABLE_NAME + " WHERE shard = ?")) {
            for (var shard = 0; shard < shards; shard++) {
                statement.setShort(1, (short) shard);
                try (var resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        if (!harvest.wasDelivered(shard, resultSet.getLong(1))) {
                            missing++;
                        }
                    }
                }
            }
        }
        return missing;
    }

    private void createSchema(int shards) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
            statement.execute("""
                              CREATE TABLE %s (
                                  shard             smallint NOT NULL,
                                  seq               bigint   NOT NULL,
                                  intended_at_nanos bigint   NOT NULL,
                                  payload           bytea    NOT NULL,
                                  PRIMARY KEY (shard, seq)
                              )
                              """.formatted(TABLE_NAME));
            for (var shard = 0; shard < shards; shard++) {
                statement.execute("DROP SEQUENCE IF EXISTS " + SEQUENCE_STEM + shard);
                // CACHE 1 so that a value allocated is a value that will be committed. A larger cache
                // burns values per session and would show up as permanent holes that say nothing
                // about the design under test.
                statement.execute("CREATE SEQUENCE " + SEQUENCE_STEM + shard + " START WITH 1 INCREMENT BY 1 CACHE 1");
            }
        }
    }

    private void resetSchema(int shards) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE " + TABLE_NAME);
            for (var shard = 0; shard < shards; shard++) {
                statement.execute("ALTER SEQUENCE " + SEQUENCE_STEM + shard + " RESTART WITH 1");
            }
        }
    }

    /**
     * Everything one measured run collects. Delivery is tracked per shard in a {@link BitSet}
     * because the invariant needs the exact set, not a count — a count cannot tell a message
     * delivered twice apart from one never delivered at all.
     */
    private static final class Harvest {
        private final LatencyRecorder endToEnd         = new LatencyRecorder("endToEnd");
        private final LatencyRecorder holeResolution   = new LatencyRecorder("holeResolution");
        private final LongAdder       messagesProduced = new LongAdder();
        private final LongAdder       messagesDelivered = new LongAdder();
        private final LongAdder       holesObserved    = new LongAdder();
        private final LongAdder       holesResolved    = new LongAdder();
        private final LongAdder       holesPermanent   = new LongAdder();
        private final LongAdder       holeChaseQueries = new LongAdder();
        private final AtomicInteger   maxPendingHoles  = new AtomicInteger();
        private final BitSet[]        delivered;

        Harvest(SeqGap settings) {
            this.delivered = new BitSet[settings.getShards()];
            Arrays.setAll(this.delivered, index -> new BitSet());
        }

        /**
         * @return true if this is the first delivery of that sequence value
         */
        boolean markDelivered(int shard, long seq) {
            var shardBits = delivered[shard];
            synchronized (shardBits) {
                var index = (int) seq;
                if (shardBits.get(index)) {
                    return false;
                }
                shardBits.set(index);
                return true;
            }
        }

        boolean wasDelivered(int shard, long seq) {
            var shardBits = delivered[shard];
            synchronized (shardBits) {
                return shardBits.get((int) seq);
            }
        }
    }
}
