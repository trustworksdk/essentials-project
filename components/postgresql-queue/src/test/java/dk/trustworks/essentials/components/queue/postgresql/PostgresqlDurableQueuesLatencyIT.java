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
import dk.trustworks.essentials.components.foundation.postgresql.MultiTableChangeListener;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.DurableQueuesLoadIT;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.queue.postgresql.test_data.*;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import dk.trustworks.essentials.reactive.LocalEventBus;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;

import static dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule.createObjectMapper;
import static dk.trustworks.essentials.shared.collections.Lists.partition;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public abstract class PostgresqlDurableQueuesLatencyIT extends DurableQueuesLoadIT<PostgresqlDurableQueues, GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {

    public static final int TOTAL_PER_TEST = 100_000;
    public static final int QUEUE_COUNT    = 5;
    public static final int BATCH_SIZE     = 500;

    public static double percentile(List<Long> values, double p) {
        Collections.sort(values);
        int idx = (int) Math.ceil(values.size() * p) - 1;
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
        return new JdbiUnitOfWorkFactory(jdbi);
    }

    @Override
    protected void resetQueueStorage(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
    }

    @Test
    void measure_latency_multi_queue_unordered() {
        List<QueueName> queuesList = IntStream.range(0, QUEUE_COUNT)
                                              .mapToObj(i -> QueueName.of("PerfQ" + i))
                                              .toList();

        Map<QueueName, List<Message>> unorderedMessages = TestMessageFactory.createUnorderedMessages(TOTAL_PER_TEST, queuesList);

        for (var queueName : queuesList) {
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                List<Message> messages = unorderedMessages.get(queueName);
                for (List<Message> chunk : partition(messages, BATCH_SIZE)) {
                    var ids = durableQueues.queueMessages(queueName, chunk);
                    assertThat(ids).hasSize(chunk.size());
                }
            });
        }

        QueryPerformanceResult result = unorderedQuery(queuesList);
        System.out.println("Unordered workload performance: " + result);
    }

    private QueryPerformanceResult unorderedQuery(List<QueueName> queuesList) {
        AtomicInteger totalFetched = new AtomicInteger(0);
        Map<QueueName, AtomicInteger> fetchedPerQueue = queuesList.stream()
                                                                  .collect(Collectors.toMap(qn -> qn, qn -> new AtomicInteger(0)));

        Instant    wallStart = Instant.now();
        List<Long> latencies = new ArrayList<>(TOTAL_PER_TEST);

        outer:
        while (totalFetched.get() < targetQueriesToMeasure()) {
            for (var queueName : queuesList) {
                if (fetchedPerQueue.get(queueName).get() >= targetQueriesToMeasurePerQueue()) {
                    continue;
                }
                unitOfWorkFactory.usingUnitOfWork(uow -> {
                    long t0 = System.nanoTime();
                    var opt = uow.handle().createQuery(durableQueues.buildUnorderedSqlStatement())
                                 .bind("queueName", queueName)
                                 .bind("now", Instant.now())
                                 .bind("limit", 1)
                                 .map(durableQueues.getQueuedMessageMapper())
                                 .findOne();
                    long t1 = System.nanoTime();
                    if (opt.isPresent()) {
                        latencies.add(t1 - t0);
                        totalFetched.incrementAndGet();
                        fetchedPerQueue.get(queueName).incrementAndGet();
                        uow.handle().createUpdate("DELETE FROM durable_queues WHERE id = :id")
                           .bind("id", opt.get().getId())
                           .execute();
                    }
                });
                if (totalFetched.get() >= targetQueriesToMeasure()) {
                    break outer;
                }
            }
        }

        return new QueryPerformanceResult(Duration.between(wallStart, Instant.now()).toMillis(),
                                          latencies.stream().mapToLong(x -> x).average().orElse(0) / 1_000.0,
                                          percentile(latencies, 0.95));
    }

    @Test
    void measure_latency_multi_queue_unordered_old_query() {
        List<QueueName> queuesList = IntStream.range(0, QUEUE_COUNT)
                                              .mapToObj(i -> QueueName.of("PerfQ" + i))
                                              .toList();

        Map<QueueName, List<Message>> unorderedMessages = TestMessageFactory.createUnorderedMessages(TOTAL_PER_TEST, queuesList);

        for (var queueName : queuesList) {
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                List<Message> messages = unorderedMessages.get(queueName);
                for (List<Message> chunk : partition(messages, BATCH_SIZE)) {
                    var ids = durableQueues.queueMessages(queueName, chunk);
                    assertThat(ids).hasSize(chunk.size());
                }
            });
        }

        QueryPerformanceResult result = oldQuery(queuesList);
        System.out.println("Unordered workload performance old query: " + result);
    }

    @Test
    void measure_latency_multi_queue_ordered() {
        List<QueueName> queuesList = IntStream.range(0, QUEUE_COUNT)
                                              .mapToObj(i -> QueueName.of("PerfQueue" + i))
                                              .toList();

        var orderedMap = TestMessageFactory.createOrderedMessages(TOTAL_PER_TEST, queuesList, 75000);

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            for (var queueName : queuesList) {
                List<OrderedMessage> messages = orderedMap.get(queueName);
                for (List<OrderedMessage> chunk : partition(messages, BATCH_SIZE)) {
                    var ids = durableQueues.queueMessages(queueName, chunk);
                    assertThat(ids).hasSize(chunk.size());
                }
            }
        });

        QueryPerformanceResult result = orderedQuery(queuesList);
        System.out.println("Ordered workload performance: " + result);
    }

    private QueryPerformanceResult orderedQuery(List<QueueName> queuesList) {
        var totalFetched = new AtomicInteger();
        var fetchedPerQueue = queuesList.stream()
                                        .collect(Collectors.toMap(qn -> qn, qn -> new AtomicInteger()));

        var        wallStart = Instant.now();
        List<Long> latencies = new ArrayList<>();

        var orderedSql = durableQueues.buildOrderedSqlStatement(false);

        outer:
        while (totalFetched.get() < targetQueriesToMeasure()) {
            for (var queueName : queuesList) {
                if (fetchedPerQueue.get(queueName).get() >= targetQueriesToMeasurePerQueue()) {
                    continue;
                }
                unitOfWorkFactory.usingUnitOfWork(uow -> {
                    long t0 = System.nanoTime();
                    var queuedMessage = uow.handle().createQuery(orderedSql)
                                           .bind("queueName", queueName)
                                           .bind("now", Instant.now())
                                           .bind("limit", 1)
                                           .map(durableQueues.getQueuedMessageMapper())
                                           .findOne();
                    long t1 = System.nanoTime();
                    if (queuedMessage.isPresent()) {
                        latencies.add(t1 - t0);
                        fetchedPerQueue.get(queueName).incrementAndGet();
                        totalFetched.incrementAndGet();
                        uow.handle().createUpdate("DELETE FROM durable_queues WHERE id = :id")
                           .bind("id", queuedMessage.get().getId())
                           .execute();
                    }
                });
                if (totalFetched.get() >= targetQueriesToMeasure()) {
                    break outer;
                }
            }
        }

        return new QueryPerformanceResult(Duration.between(wallStart, Instant.now()).toMillis(),
                                          latencies.stream().mapToLong(x -> x).average().orElse(0) / 1_000.0,
                                          percentile(latencies, 0.95));
    }

    @Test
    void measure_latency_multi_queue_ordered_old_query() {
        List<QueueName> queuesList = IntStream.range(0, QUEUE_COUNT)
                                              .mapToObj(i -> QueueName.of("PerfQueue" + i))
                                              .toList();

        var orderedMap = TestMessageFactory.createOrderedMessages(TOTAL_PER_TEST, queuesList, 75000);

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            for (var queueName : queuesList) {
                List<OrderedMessage> messages = orderedMap.get(queueName);
                for (List<OrderedMessage> chunk : partition(messages, BATCH_SIZE)) {
                    var ids = durableQueues.queueMessages(queueName, chunk);
                    assertThat(ids).hasSize(chunk.size());
                }
            }
        });

        QueryPerformanceResult result = oldQuery(queuesList);
        System.out.println("Ordered workload performance old query: " + result);
    }

    @Test
    void measure_latency_multi_queue_mixed() {
        var queuesList = IntStream.range(0, QUEUE_COUNT)
                                  .mapToObj(i -> QueueName.of("PerfQ" + i))
                                  .toList();

        int half         = TOTAL_PER_TEST / 2;
        var unorderedMap = TestMessageFactory.createUnorderedMessages(half, queuesList);
        var orderedMap   = TestMessageFactory.createOrderedMessages(half, queuesList, 40000);

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            for (var queueName : queuesList) {
                var unOrderedMessages = unorderedMap.get(queueName);
                for (List<Message> chunk : partition(unOrderedMessages, BATCH_SIZE)) {
                    var unOrderedIds = durableQueues.queueMessages(queueName, chunk);
                    assertThat(unOrderedIds).hasSize(chunk.size());
                }

                var orderedMessages = orderedMap.get(queueName);
                for (List<OrderedMessage> chunk : partition(orderedMessages, BATCH_SIZE)) {
                    var orderedIds = durableQueues.queueMessages(queueName, chunk);
                    assertThat(orderedIds).hasSize(chunk.size());
                }
            }
        });

        QueryPerformanceResult result = orderedUnorderedQuery(queuesList);

        System.out.println("Mixed workload performance: " + result);
    }

    private QueryPerformanceResult orderedUnorderedQuery(List<QueueName> queuesList) {
        var totalFetched = new AtomicInteger();
        var fetchedPerQueue = queuesList.stream()
                                        .collect(Collectors.toMap(qn -> qn, qn -> new AtomicInteger()));

        var        wallStart = Instant.now();
        List<Long> latencies = new ArrayList<>();

        var orderedSql   = durableQueues.buildOrderedSqlStatement(false);
        var unorderedSql = durableQueues.buildUnorderedSqlStatement();

        outer:
        while (totalFetched.get() < targetQueriesToMeasure()) {
            for (var queueName : queuesList) {
                if (fetchedPerQueue.get(queueName).get() >= targetQueriesToMeasurePerQueue()) {
                    continue;
                }

                boolean didDeliver = unitOfWorkFactory.withUnitOfWork(uow -> {
                    var handle = uow.handle();

                    long t0 = System.nanoTime();
                    var queuedOrderedMessage = handle.createQuery(orderedSql)
                                                     .bind("queueName", queueName)
                                                     .bind("now", Instant.now())
                                                     .bind("limit", 1)
                                                     .map(durableQueues.getQueuedMessageMapper())
                                                     .findOne();
                    long t1 = System.nanoTime();
                    if (queuedOrderedMessage.isPresent()) {
                        latencies.add(t1 - t0);
                        fetchedPerQueue.get(queueName).incrementAndGet();
                        totalFetched.incrementAndGet();
                        handle.createUpdate("DELETE FROM durable_queues WHERE id = :id")
                              .bind("id", queuedOrderedMessage.get().getId())
                              .execute();
                        return true;
                    }

                    long t2 = System.nanoTime();
                    var queuedMessage = handle.createQuery(unorderedSql)
                                              .bind("queueName", queueName)
                                              .bind("now", Instant.now())
                                              .bind("limit", 1)
                                              .map(durableQueues.getQueuedMessageMapper())
                                              .findOne();
                    long t3 = System.nanoTime();
                    if (queuedMessage.isPresent()) {
                        latencies.add(t3 - t2);
                        fetchedPerQueue.get(queueName).incrementAndGet();
                        totalFetched.incrementAndGet();
                        handle.createUpdate("DELETE FROM durable_queues WHERE id = :id")
                              .bind("id", queuedMessage.get().getId())
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

        return new QueryPerformanceResult(Duration.between(wallStart, Instant.now()).toMillis(),
                                          latencies.stream().mapToLong(x -> x).average().orElse(0) / 1_000.0,
                                          percentile(latencies, 0.95));
    }

    @Test
    void measure_latency_multi_queue_mixed_old_query() {
        var queuesList = IntStream.range(0, QUEUE_COUNT)
                                  .mapToObj(i -> QueueName.of("PerfQ" + i))
                                  .toList();

        int half         = TOTAL_PER_TEST / 2;
        var unorderedMap = TestMessageFactory.createUnorderedMessages(half, queuesList);
        var orderedMap   = TestMessageFactory.createOrderedMessages(half, queuesList, 40000);

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            for (var queueName : queuesList) {
                var unOrderedMessages = unorderedMap.get(queueName);
                for (List<Message> chunk : partition(unOrderedMessages, BATCH_SIZE)) {
                    var unOrderedIds = durableQueues.queueMessages(queueName, chunk);
                    assertThat(unOrderedIds).hasSize(chunk.size());
                }

                var orderedMessages = orderedMap.get(queueName);
                for (List<OrderedMessage> chunk : partition(orderedMessages, BATCH_SIZE)) {
                    var orderedIds = durableQueues.queueMessages(queueName, chunk);
                    assertThat(orderedIds).hasSize(chunk.size());
                }
            }
        });

        QueryPerformanceResult result = oldQuery(queuesList);

        System.out.println("Mixed workload performance old query: " + result);
    }

    private QueryPerformanceResult oldQuery(List<QueueName> queuesList) {
        var totalFetched = new AtomicInteger();
        var fetchedPerQueue = queuesList.stream()
                                        .collect(Collectors.toMap(qn -> qn, qn -> new AtomicInteger()));
        var        wallStart = Instant.now();
        List<Long> latencies = new ArrayList<>();

        var oldSql = durableQueues.buildGetNextMessageReadyForDeliverySqlStatement(Collections.emptySet());

        outer:
        while (totalFetched.get() < targetQueriesToMeasure()) {
            for (var queueName : queuesList) {
                if (fetchedPerQueue.get(queueName).get() >= targetQueriesToMeasurePerQueue()) {
                    continue;
                }
                unitOfWorkFactory.usingUnitOfWork(uow -> {
                    long t0 = System.nanoTime();
                    var queuedMessage = uow.handle().createQuery(oldSql)
                                           .bind("queueName", queueName)
                                           .bind("now", Instant.now())
                                           .bind("limit", 1)
                                           .map(durableQueues.getQueuedMessageMapper())
                                           .findOne();
                    long t1 = System.nanoTime();
                    if (queuedMessage.isPresent()) {
                        latencies.add(t1 - t0);
                        fetchedPerQueue.get(queueName).incrementAndGet();
                        totalFetched.incrementAndGet();
                        uow.handle().createUpdate("DELETE FROM durable_queues WHERE id = :id")
                           .bind("id", queuedMessage.get().getId())
                           .execute();
                    }
                });
                if (totalFetched.get() >= targetQueriesToMeasure()) {
                    break outer;
                }
            }
        }

        return new QueryPerformanceResult(Duration.between(wallStart, Instant.now()).toMillis(),
                                          latencies.stream().mapToLong(x -> x).average().orElse(0) / 1_000.0,
                                          percentile(latencies, 0.95));
    }

    //@Test takes over a minute
    void measure_latency_multi_queue_mixed_batched() {
        var queuesList = IntStream.range(0, 20)
                                  .mapToObj(i -> QueueName.of("PerfQ" + i))
                                  .toList();

        int half         = TOTAL_PER_TEST / 2;
        var unorderedMap = TestMessageFactory.createUnorderedMessages(half, queuesList);
        var orderedMap   = TestMessageFactory.createOrderedMessages(half, queuesList, 40000);

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            for (var queueName : queuesList) {
                var unOrderedMessages = unorderedMap.get(queueName);
                for (List<Message> chunk : partition(unOrderedMessages, BATCH_SIZE)) {
                    var unOrderedIds = durableQueues.queueMessages(queueName, chunk);
                    assertThat(unOrderedIds).hasSize(chunk.size());
                }

                var orderedMessages = orderedMap.get(queueName);
                for (List<OrderedMessage> chunk : partition(orderedMessages, BATCH_SIZE)) {
                    var orderedIds = durableQueues.queueMessages(queueName, chunk);
                    assertThat(orderedIds).hasSize(chunk.size());
                }
            }
        });

        QueryPerformanceResult result = batchedQuery(queuesList);
        System.out.println("Mixed workload performance batched: " + result);
    }

    private QueryPerformanceResult batchedQuery(List<QueueName> queuesList) {
        var totalFetched = new AtomicInteger();
        var        wallStart = Instant.now();
        List<Long> latencies = new ArrayList<>();

        Map<QueueName, Integer> availableSlotPrQueue = queuesList.stream().collect(Collectors.toMap(qn -> qn, qn -> 3));
        var batchedSql = durableQueues.buildBatchedSqlStatement(Map.of(), availableSlotPrQueue, queuesList);

        while (totalFetched.get() < TOTAL_PER_TEST) {
            for (var queueName : queuesList) {
                unitOfWorkFactory.usingUnitOfWork(uow -> {
                    long t0 = System.nanoTime();
                    var queuedMessages = uow.handle().createQuery(batchedSql)
                                           .bind("queueName", queueName)
                                           .bind("now", Instant.now())
                                           .bind("limit", 1)
                                           .map(durableQueues.getQueuedMessageMapper())
                                            .list();
                    long t1 = System.nanoTime();
                    if (!queuedMessages.isEmpty()) {
                        latencies.add(t1 - t0);
                        totalFetched.set(totalFetched.get() + queuedMessages.size());
                        System.out.println("Found '" + queuedMessages.size() + "' messages for queue '" + queueName + "'");
                        uow.handle()
                           .createUpdate("DELETE FROM durable_queues WHERE id IN (<ids>)")
                           .bindList("ids", queuedMessages.stream().map(QueuedMessage::getId).toList())
                           .execute();
                    }
                });
            }
        }

        return new QueryPerformanceResult(Duration.between(wallStart, Instant.now()).toMillis(),
                                          latencies.stream().mapToLong(x -> x).average().orElse(0) / 1_000.0,
                                          percentile(latencies, 0.95));
    }


}

