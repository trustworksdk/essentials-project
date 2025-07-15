/*
 *
 *  * Copyright 2021-2025 the original author or authors.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      https://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package dk.trustworks.essentials.components.queue.postgresql;

import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dk.trustworks.essentials.components.foundation.json.JacksonJSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.DurableQueuesLoadIT;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkException;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.foundation.types.CorrelationId;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import dk.trustworks.essentials.reactive.LocalEventBus;
import org.jdbi.v3.core.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;

import static dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule.createObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public abstract class PostgresqlDurableQueuesLatencyIT extends DurableQueuesLoadIT<PostgresqlDurableQueues, GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {

    public static final int TOTAL_PER_TEST = 100_000;
    public static final int QUEUE_COUNT    = 5;
    public static final int BATCH_SIZE     = 500;

    public static double percentile(List<Long> values, double p) {
        Collections.sort(values);
        int idx = (int)Math.ceil(values.size() * p) - 1;
        return values.get(Math.max(idx, 0)) / 1_000.0;
    }

    protected abstract long targetQueriesToMeasure();
    protected abstract long targetQueriesToMeasurePerQueue();

    @Container
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("test")
            .withPassword("test")
            .withUsername("test");

    @Override
    protected PostgresqlDurableQueues createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        return PostgresqlDurableQueues.builder()
                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                      .setQueuePollingOptimizerFactory(consumeFromQueue -> new QueuePollingOptimizer.SimpleQueuePollingOptimizer(consumeFromQueue, 100, 1000))
                                      .setMultiTableChangeListener(new MultiTableChangeListener<>(unitOfWorkFactory.getJdbi(),
                                                                                                  Duration.ofMillis(100),
                                                                                                  new JacksonJSONSerializer(
                                                                                                          createObjectMapper(
                                                                                                                  new Jdk8Module(),
                                                                                                                  new JavaTimeModule(),
                                                                                                                  new EssentialTypesJacksonModule())
                                                                                                  ),
                                                                                                  LocalEventBus.builder().build(),
                                                                                                  true))
                                      .setUseCentralizedMessageFetcher(false)
                                      .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(30))
                                      .setUseOrderedUnorderedQuery(false)
                                      .build();
    }

    @Override
    protected JdbiUnitOfWorkFactory createUnitOfWorkFactory() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                    postgreSQLContainer.getUsername(),
                    postgreSQLContainer.getPassword());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());
        return new JdbiUnitOfWorkFactory(jdbi);
    }

    @Override
    protected void resetQueueStorage(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
    }

    @Test
    void measure_latency_multi_queue_unordered() {
        // prepare 5 queueNames
        List<QueueName> queuesList = IntStream.range(0, QUEUE_COUNT)
                                              .mapToObj(i -> QueueName.of("PerfQ" + i))
                                              .toList();

        IntStream.range(0, TOTAL_PER_TEST / BATCH_SIZE).forEach(batch -> {
            int start = batch * BATCH_SIZE;
            int end   = start + BATCH_SIZE;
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                List<Message> messages = new ArrayList<>(BATCH_SIZE);
                IntStream.range(start, end).forEach(i -> {
                    messages.add(Message.of(
                            "M" + i,
                            MessageMetaData.of("correlation_id", CorrelationId.random(),
                                               "trace_id", UUID.randomUUID().toString())
                                       ));
                });
                for (int q = 0; q < QUEUE_COUNT; q++) {
                    List<Message> sub = new ArrayList<>();
                    for (int i = start + q; i < end; i += QUEUE_COUNT) {
                        sub.add(messages.get(i - start));
                    }
                    var ids = durableQueues.queueMessages(queuesList.get(q), sub);
                    assertThat(ids).hasSize(sub.size());
                }
            });
        });

        AtomicInteger totalFetched = new AtomicInteger(0);
        Map<QueueName,AtomicInteger> fetchedPerQueue = queuesList.stream()
                                                                 .collect(Collectors.toMap(qn -> qn, qn -> new AtomicInteger(0)));

        Instant wallStart = Instant.now();
        List<Long> lats = new ArrayList<>(TOTAL_PER_TEST);
        AtomicInteger delivered = new AtomicInteger();

        while (delivered.get() < targetQueriesToMeasure()) {
            for (QueueName qn : queuesList) {
                if (fetchedPerQueue.get(qn).get() >= targetQueriesToMeasurePerQueue()) {
                    continue;
                }
                unitOfWorkFactory.usingUnitOfWork(uow -> {
                    long t0 = System.nanoTime();
                    var opt = uow.handle().createQuery(durableQueues.buildUnorderedSqlStatement())
                               .bind("queueName", qn)
                               .bind("now", Instant.now())
                                .bind("limit", 1)
                               .map(durableQueues.getQueuedMessageMapper())
                               .findOne();
                    long t1 = System.nanoTime();
                    if (opt.isPresent()) {
                        lats.add(t1 - t0);
                        delivered.incrementAndGet();
                        totalFetched.incrementAndGet();
                        fetchedPerQueue.get(qn).incrementAndGet();
                        uow.handle().createUpdate("DELETE FROM durable_queues WHERE id = :id")
                         .bind("id", opt.get().getId())
                         .execute();
                    }
                });
            }
            if (totalFetched.get() >= targetQueriesToMeasure()) {
                break;
            }
        }

        long totalMs = java.time.Duration.between(wallStart, Instant.now()).toMillis();
        double avgUs = lats.stream().mapToLong(x -> x).average().orElse(0) / 1_000.0;
        double p95   = percentile(lats, 0.95);

        System.out.printf(
                "multi-queue unordered: total=%d ms, avg=%.2f µs, p95=%.2f µs%n",
                totalMs, avgUs, p95
                         );
    }

    @Test
    void measure_latency_multi_queue_ordered() {
        List<QueueName> queuesList = IntStream.range(0, QUEUE_COUNT)
                                              .mapToObj(i -> QueueName.of("PerfQueue" + i))
                                              .toList();

        List<List<String>> keysPerQueue = new ArrayList<>(QUEUE_COUNT);
        IntStream.range(0, QUEUE_COUNT).forEach(q -> {
            List<String> keys = IntStream.range(0, 100)
                                         .mapToObj(i -> "Key" + q + "-" + i)
                                         .toList();
            keysPerQueue.add(keys);
        });
        List<int[]> counters = new ArrayList<>(QUEUE_COUNT);
        for (int i = 0; i < QUEUE_COUNT; i++) counters.add(new int[keysPerQueue.get(i).size()]);

        IntStream.range(0, TOTAL_PER_TEST / BATCH_SIZE).forEach(batch -> {
            int start = batch * BATCH_SIZE, end = start + BATCH_SIZE;
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                for (int q = 0; q < QUEUE_COUNT; q++) {
                    List<OrderedMessage> messages = new ArrayList<>();
                    var keys = keysPerQueue.get(q);
                    var ctr  = counters.get(q);
                    for (int i = start + q; i < end; i += QUEUE_COUNT) {
                        int idx = i % keys.size();
                        messages.add(OrderedMessage.of(
                                "M" + i,
                                keys.get(idx),
                                ctr[idx]++,
                                MessageMetaData.of("correlation_id", CorrelationId.random(),
                                                   "trace_id", UUID.randomUUID().toString())
                                                  ));
                    }
                    var ids = durableQueues.queueMessages(queuesList.get(q), messages);
                    assertThat(ids).hasSize(messages.size());
                }
            });
        });

        AtomicInteger totalFetched = new AtomicInteger(0);
        Map<QueueName,AtomicInteger> fetchedPerQueue = queuesList.stream()
                                                                 .collect(Collectors.toMap(qn -> qn, qn -> new AtomicInteger(0)));


        Instant startWall = Instant.now();
        List<Long>    lats = new ArrayList<>(TOTAL_PER_TEST);
        AtomicInteger done = new AtomicInteger();

        while (done.get() < targetQueriesToMeasure()) {
            for (QueueName qn : queuesList) {
                if (fetchedPerQueue.get(qn).get() >= targetQueriesToMeasurePerQueue()) {
                    continue;
                }
                unitOfWorkFactory.usingUnitOfWork(uow -> {
                    long t0 = System.nanoTime();
                    var opt = uow.handle().createQuery(durableQueues.buildOrderedSqlStatement(false))
                                    .bind("queueName", qn)
                                    .bind("now", Instant.now())
                                    .bind("limit", 1)
                                    .map(durableQueues.getQueuedMessageMapper())
                                    .findOne();
                    long t1 = System.nanoTime();
                    if (opt.isPresent()) {
                        lats.add(t1 - t0);
                        done.incrementAndGet();
                        totalFetched.incrementAndGet();
                        fetchedPerQueue.get(qn).incrementAndGet();
                        uow.handle().createUpdate("DELETE FROM durable_queues WHERE id = :id")
                              .bind("id", opt.get().getId())
                              .execute();
                    }
                });
            }
            if (totalFetched.get() >= targetQueriesToMeasure()) {
                break;
            }
        }

        long totalMs = Duration.between(startWall, Instant.now()).toMillis();
        double avgUs  = lats.stream().mapToLong(x->x).average().orElse(0)/1_000.0;
        double p95     = percentile(lats, 0.95);

        System.out.printf(
                "multi-queue ordered: total=%d ms, avg=%.2f µs, p95=%.2f µs%n",
                totalMs, avgUs, p95
                         );
    }

    @Test
    void measure_latency_multi_queue_mixed() {
        List<QueueName> queuesList = IntStream.range(0, QUEUE_COUNT)
                                              .mapToObj(i -> QueueName.of("PerfQ" + i))
                                              .toList();
        List<int[]> orderCounters = new ArrayList<>(QUEUE_COUNT);
        for (int i = 0; i < QUEUE_COUNT; i++) orderCounters.add(new int[100]); // 100 keys

        for (int batch = 0; batch < TOTAL_PER_TEST / BATCH_SIZE; batch++) {
            int start = batch * BATCH_SIZE, end = start + BATCH_SIZE;
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                for (int q = 0; q < QUEUE_COUNT; q++) {
                    QueueName qn = queuesList.get(q);

                    // split this batch into two lists:
                    List<Message> unorderedBatch = new ArrayList<>();
                    List<OrderedMessage> orderedBatch = new ArrayList<>();

                    for (int i = start + q; i < end; i += QUEUE_COUNT) {
                        if ((i & 1) == 0) {
                            // unordered
                            unorderedBatch.add(Message.of(
                                    "M" + i,
                                    MessageMetaData.of(
                                            "correlation_id", CorrelationId.random(),
                                            "trace_id", UUID.randomUUID().toString()
                                                      )
                                                         ));
                        } else {
                            // ordered
                            int keyIdx = (i / QUEUE_COUNT) % 100;
                            orderedBatch.add(OrderedMessage.of(
                                    "M" + i,
                                    "Key" + q + "-" + keyIdx,
                                    orderCounters.get(q)[keyIdx]++,
                                    MessageMetaData.of(
                                            "correlation_id", CorrelationId.random(),
                                            "trace_id", UUID.randomUUID().toString()
                                                      )
                                                              ));
                        }
                    }

                    if (!unorderedBatch.isEmpty()) {
                        var uids = durableQueues.queueMessages(qn, unorderedBatch);
                        assertThat(uids).hasSize(unorderedBatch.size());
                    }
                    if (!orderedBatch.isEmpty()) {
                        var oids = durableQueues.queueMessages(qn, orderedBatch);
                        assertThat(oids).hasSize(orderedBatch.size());
                    }
                }
            });
        }

        AtomicInteger totalFetched = new AtomicInteger();
        Map<QueueName,AtomicInteger> fetchedPerQueue = queuesList.stream()
                                                                 .collect(Collectors.toMap(qn -> qn, qn -> new AtomicInteger()));

        Instant wallStart = Instant.now();
        List<Long> latencies = new ArrayList<>();

        String orderedSql   = durableQueues.buildOrderedSqlStatement(false);
        String unorderedSql = durableQueues.buildUnorderedSqlStatement();

        outer:
        while (totalFetched.get() < targetQueriesToMeasure()) {
            for (QueueName qn : queuesList) {
                if (fetchedPerQueue.get(qn).get() >= targetQueriesToMeasurePerQueue()) {
                    continue;
                }

                boolean didDeliver = unitOfWorkFactory.withUnitOfWork(uow -> {
                    Handle h = uow.handle();

                    long t0 = System.nanoTime();
                    var optO = h.createQuery(orderedSql)
                                .bind("queueName", qn)
                                .bind("now", Instant.now())
                                .bind("limit", 1)
                                .map(durableQueues.getQueuedMessageMapper())
                                .findOne();
                    long t1 = System.nanoTime();
                    if (optO.isPresent()) {
                        latencies.add(t1 - t0);
                        fetchedPerQueue.get(qn).incrementAndGet();
                        totalFetched.incrementAndGet();
                        h.createUpdate("DELETE FROM durable_queues WHERE id = :id")
                         .bind("id", optO.get().getId())
                         .execute();
                        return true;
                    }

                    long t2 = System.nanoTime();
                    var optU = h.createQuery(unorderedSql)
                                .bind("queueName", qn)
                                .bind("now", Instant.now())
                                .bind("limit", 1)
                                .map(durableQueues.getQueuedMessageMapper())
                                .findOne();
                    long t3 = System.nanoTime();
                    if (optU.isPresent()) {
                        latencies.add(t3 - t2);
                        fetchedPerQueue.get(qn).incrementAndGet();
                        totalFetched.incrementAndGet();
                        h.createUpdate("DELETE FROM durable_queues WHERE id = :id")
                         .bind("id", optU.get().getId())
                         .execute();
                        return true;
                    }

                    return false;
                });

                if (didDeliver && totalFetched.get() >= targetQueriesToMeasure()) {
                    break outer;
                }
            }
        }

        long totalMs = Duration.between(wallStart, Instant.now()).toMillis();
        double avgUs = latencies.stream().mapToLong(x -> x).average().orElse(0) / 1_000.0;
        double p95   = percentile(latencies, 0.95);

        System.out.printf(
                "multi-queue mixed: total=%dms, avg=%.2fµs, p95=%.2fµs%n",
                totalMs, avgUs, p95
                         );
    }
}

