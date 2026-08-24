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

package dk.trustworks.essentials.components.queue.postgresql.benchmark;

import com.zaxxer.hikari.HikariDataSource;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Steady state: producers and consumers running <b>together</b> against a small backlog.
 *
 * <h2>Why this exists</h2>
 * Every other benchmark in this module seeds N messages and then drains them with no concurrent arrivals. That is
 * backlog recovery, and it is a real case, but it is not how a queue normally runs — and it differs in ways that
 * have already changed conclusions here:
 * <ul>
 *     <li><b>Table and index size.</b> A drain runs from tens of thousands of rows down to zero; a steady-state
 *     queue holds tens.</li>
 *     <li><b>Planner statistics.</b> A burst-loaded table has none, which measured at <b>11×</b> on the ordered
 *     claim (§25). A continuously written and read table gives autovacuum a reason to visit.</li>
 *     <li><b>Dead tuples.</b> Burst-drain makes one large wave; steady state makes a trickle, which is what
 *     autovacuum is built for and what §13 found never triggered under a burst.</li>
 *     <li><b>Contention.</b> No drain benchmark has inserts competing with claims for the same pages.</li>
 * </ul>
 *
 * <h2>What it measures, and the check that makes it meaningful</h2>
 * Producers run at a fixed rate; consumers run continuously. After a warm-up the measurement window records
 * delivered throughput and enqueue-to-delivery latency percentiles.
 * <p>
 * <b>The backlog check is what separates this from another drain benchmark.</b> If the arrival rate exceeds
 * capacity the backlog grows without bound and the run degenerates into backlog recovery — measuring the thing this
 * class exists to avoid. So the depth is sampled throughout and reported, and a run whose backlog is still growing
 * at the end is reported as not having reached steady state rather than quietly averaged.
 * <p>
 * Latency, not throughput, is the interesting output here: at a sustainable arrival rate both arms deliver exactly
 * what is produced, so throughput is an input. What differs is how long a message waits.
 * <p>
 * Opt-in via {@code -Dbenchmark.run=true}. {@code -Dsteady.ratePerSecond=2000},
 * {@code -Dsteady.warmupSeconds=10}, {@code -Dsteady.measureSeconds=30}.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class SteadyStateThroughputBenchmarkIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("steady-state-benchmark-db");

    private static final String TABLE = PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME;

    private HikariDataSource      dataSource;
    private JdbiUnitOfWorkFactory unitOfWorkFactory;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        dataSource.setUsername(postgreSQLContainer.getUsername());
        dataSource.setPassword(postgreSQLContainer.getPassword());
        dataSource.setAutoCommit(false);
        dataSource.setMaximumPoolSize(24);
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void batched_acknowledgement_in_steady_state() {
        var ratePerSecond   = Integer.getInteger("steady.ratePerSecond", 2000);
        var warmupSeconds   = Integer.getInteger("steady.warmupSeconds", 10);
        var measureSeconds  = Integer.getInteger("steady.measureSeconds", 20);

        System.out.printf("%nsteady state: %d msg/s offered, %ds warm-up, %ds measured%n",
                          ratePerSecond, warmupSeconds, measureSeconds);
        System.out.printf("%-14s %-12s %-12s %-12s %-12s %-14s%n",
                          "batched ack", "delivered", "throughput/s", "p50 ms", "p99 ms", "backlog end");
        for (var batchedAck : List.of(false, true)) {
            var result = run(batchedAck, ratePerSecond, warmupSeconds, measureSeconds);
            System.out.printf("%-14s %-12d %-12d %-12d %-12d %-14s%n",
                              batchedAck, result.delivered, result.delivered / measureSeconds,
                              result.p50Ms, result.p99Ms,
                              result.backlogGrowing ? result.backlogEnd + " GROWING" : String.valueOf(result.backlogEnd));
        }
    }

    private Result run(boolean batchedAck, int ratePerSecond, int warmupSeconds, int measureSeconds) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("DROP TABLE IF EXISTS " + TABLE));
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(unitOfWorkFactory)
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .setUseBatchedAcknowledgement(batchedAck)
                                                   .build();
        durableQueues.start();

        var queueName = QueueName.of("SteadyState");
        var latencies = new ConcurrentLinkedQueue<Long>();
        var delivered = new AtomicLong();
        var measuring = new AtomicBoolean();
        var producers = Executors.newScheduledThreadPool(4);
        try {
            durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                           .setQueueName(queueName)
                                                           .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff()
                                                                                                .setRedeliveryDelay(Duration.ofMillis(100))
                                                                                                .setMaximumNumberOfRedeliveries(3)
                                                                                                .build())
                                                           .setParallelConsumers(20)
                                                           .setQueueMessageHandler(message -> {
                                                               if (measuring.get()) {
                                                                   // Enqueue-to-delivery, taken from the persisted
                                                                   // added timestamp rather than a client clock.
                                                                   latencies.add(Duration.between(message.getAddedTimestamp().toInstant(), Instant.now()).toMillis());
                                                                   delivered.incrementAndGet();
                                                               }
                                                           })
                                                           .build());

            // Producers at a fixed rate, spread across threads so one slow enqueue does not throttle the offered
            // load - the point is to offer a constant arrival rate, not to measure the producer.
            var perTick = Math.max(1, ratePerSecond / 100);
            for (var producer = 0; producer < 4; producer++) {
                producers.scheduleAtFixedRate(() -> {
                    try {
                        var batch = new ArrayList<Message>();
                        for (var i = 0; i < perTick / 4; i++) {
                            batch.add(Message.of("m"));
                        }
                        if (!batch.isEmpty()) {
                            durableQueues.queueMessages(queueName, batch);
                        }
                    } catch (Exception e) {
                        // A produce failure must not kill the schedule; it shows up as a lower offered rate.
                    }
                }, 0, 10, TimeUnit.MILLISECONDS);
            }

            sleepSeconds(warmupSeconds);
            var backlogSamples = new ArrayList<Long>();
            measuring.set(true);
            for (var second = 0; second < measureSeconds; second++) {
                sleepSeconds(1);
                backlogSamples.add(backlog(queueName, durableQueues));
            }
            measuring.set(false);

            // "Still growing" compares the last third against the first third rather than endpoints, so one noisy
            // sample cannot decide it.
            var third      = Math.max(1, backlogSamples.size() / 3);
            var early      = average(backlogSamples.subList(0, third));
            var late       = average(backlogSamples.subList(backlogSamples.size() - third, backlogSamples.size()));
            var growing    = late > early * 1.5 && late > 500;

            var sorted = latencies.stream().sorted().toList();
            assertThat(sorted).as("nothing was delivered during the measurement window").isNotEmpty();
            return new Result(delivered.get(),
                              sorted.get((int) (sorted.size() * 0.50)),
                              sorted.get((int) (sorted.size() * 0.99)),
                              backlogSamples.get(backlogSamples.size() - 1),
                              growing);
        } finally {
            producers.shutdownNow();
            durableQueues.stop();
        }
    }

    private long backlog(QueueName queueName, PostgresqlDurableQueues durableQueues) {
        return durableQueues.getTotalMessagesQueuedFor(queueName);
    }

    private static long average(List<Long> values) {
        return (long) values.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private static void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Result(long delivered, long p50Ms, long p99Ms, long backlogEnd, boolean backlogGrowing) {
    }
}
