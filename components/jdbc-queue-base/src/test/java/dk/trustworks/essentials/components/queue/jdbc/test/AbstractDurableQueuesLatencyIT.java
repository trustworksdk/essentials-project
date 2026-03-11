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

package dk.trustworks.essentials.components.queue.jdbc.test;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.DurableQueuesLoadIT;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.queue.postgresql.test_data.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;

import static dk.trustworks.essentials.shared.collections.Lists.partition;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractDurableQueuesLatencyIT<DURABLE_QUEUES extends DurableQueues>
        extends DurableQueuesLoadIT<DURABLE_QUEUES, GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {

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

    protected abstract String orderedSql();

    protected abstract String unorderedSql();

    protected abstract String oldSql();

    protected abstract Optional<QueuedMessage> fetchAndDeleteBySql(String sql, QueueName queueName);

    protected abstract Optional<QueuedMessage> fetchAndDeleteOrderedThenUnordered(String orderedSql, String unorderedSql, QueueName queueName);

    protected abstract List<QueuedMessage> fetchAndDeleteBatched(List<QueueName> queuesList,
                                                                 Map<QueueName, Integer> availableSlotPrQueue,
                                                                 QueueName queueName);

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
        var unorderedSql = unorderedSql();

        while (totalFetched.get() < targetQueriesToMeasure()) {
            for (var queueName : queuesList) {
                if (totalFetched.get() >= targetQueriesToMeasure()) {
                    break;
                }
                if (fetchedPerQueue.get(queueName).get() >= targetQueriesToMeasurePerQueue()) {
                    continue;
                }

                long t0 = System.nanoTime();
                var opt = fetchAndDeleteBySql(unorderedSql, queueName);
                long t1 = System.nanoTime();
                if (opt.isPresent()) {
                    latencies.add(t1 - t0);
                    totalFetched.incrementAndGet();
                    fetchedPerQueue.get(queueName).incrementAndGet();
                }
            }
        }

        return new QueryPerformanceResult(Duration.between(wallStart, Instant.now()).toMillis(),
                                          latencies.stream().mapToLong(x -> x).average().orElse(0) / 1_000.0,
                                          percentile(latencies, 0.95),
                                          percentile(latencies, 0.99));
    }

    @Test
    void measure_latency_multi_queue_unordered_old_query() {
        List<QueueName> queuesList = IntStream.range(0, QUEUE_COUNT)
                                              .mapToObj(i -> QueueName.of("PerfQ" + i))
                                              .toList();

        Map<QueueName, List<Message>> unorderedMessages = TestMessageFactory.createUnorderedMessages(TOTAL_PER_TEST, queuesList);

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            for (var queueName : queuesList) {
                List<Message> messages = unorderedMessages.get(queueName);
                for (List<Message> chunk : partition(messages, BATCH_SIZE)) {
                    var ids = durableQueues.queueMessages(queueName, chunk);
                    assertThat(ids).hasSize(chunk.size());
                }
            }
        });

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
        var orderedSql = orderedSql();

        while (totalFetched.get() < targetQueriesToMeasure()) {
            for (var queueName : queuesList) {
                if (totalFetched.get() >= targetQueriesToMeasure()) {
                    break;
                }
                if (fetchedPerQueue.get(queueName).get() >= targetQueriesToMeasurePerQueue()) {
                    continue;
                }

                long t0 = System.nanoTime();
                var queuedMessage = fetchAndDeleteBySql(orderedSql, queueName);
                long t1 = System.nanoTime();
                if (queuedMessage.isPresent()) {
                    latencies.add(t1 - t0);
                    fetchedPerQueue.get(queueName).incrementAndGet();
                    totalFetched.incrementAndGet();
                }
            }
        }

        return new QueryPerformanceResult(Duration.between(wallStart, Instant.now()).toMillis(),
                                          latencies.stream().mapToLong(x -> x).average().orElse(0) / 1_000.0,
                                          percentile(latencies, 0.95),
                                          percentile(latencies, 0.99));
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

        var orderedSql   = orderedSql();
        var unorderedSql = unorderedSql();

        while (totalFetched.get() < targetQueriesToMeasure()) {
            for (var queueName : queuesList) {
                if (totalFetched.get() >= targetQueriesToMeasure()) {
                    break;
                }
                if (fetchedPerQueue.get(queueName).get() >= targetQueriesToMeasurePerQueue()) {
                    continue;
                }

                long t0 = System.nanoTime();
                var deliveredMessage = fetchAndDeleteOrderedThenUnordered(orderedSql, unorderedSql, queueName);
                long t1 = System.nanoTime();
                if (deliveredMessage.isPresent()) {
                    latencies.add(t1 - t0);
                    fetchedPerQueue.get(queueName).incrementAndGet();
                    totalFetched.incrementAndGet();
                }
            }
        }

        return new QueryPerformanceResult(Duration.between(wallStart, Instant.now()).toMillis(),
                                          latencies.stream().mapToLong(x -> x).average().orElse(0) / 1_000.0,
                                          percentile(latencies, 0.95),
                                          percentile(latencies, 0.99));
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

        var oldSql = oldSql();

        while (totalFetched.get() < targetQueriesToMeasure()) {
            for (var queueName : queuesList) {
                if (totalFetched.get() >= targetQueriesToMeasure()) {
                    break;
                }
                if (fetchedPerQueue.get(queueName).get() >= targetQueriesToMeasurePerQueue()) {
                    continue;
                }

                long t0 = System.nanoTime();
                var queuedMessage = fetchAndDeleteBySql(oldSql, queueName);
                long t1 = System.nanoTime();
                if (queuedMessage.isPresent()) {
                    latencies.add(t1 - t0);
                    fetchedPerQueue.get(queueName).incrementAndGet();
                    totalFetched.incrementAndGet();
                }
            }
        }

        return new QueryPerformanceResult(Duration.between(wallStart, Instant.now()).toMillis(),
                                          latencies.stream().mapToLong(x -> x).average().orElse(0) / 1_000.0,
                                          percentile(latencies, 0.95),
                                          percentile(latencies, 0.99));
    }

    // @Test// takes over a minute
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
        var        totalFetched = new AtomicInteger();
        var        wallStart    = Instant.now();
        List<Long> latencies    = new ArrayList<>();

        Map<QueueName, Integer> availableSlotPrQueue = queuesList.stream().collect(Collectors.toMap(qn -> qn, qn -> 3));

        while (totalFetched.get() < TOTAL_PER_TEST) {
            for (var queueName : queuesList) {
                long t0 = System.nanoTime();
                var queuedMessages = fetchAndDeleteBatched(queuesList, availableSlotPrQueue, queueName);
                long t1 = System.nanoTime();
                if (!queuedMessages.isEmpty()) {
                    latencies.add(t1 - t0);
                    totalFetched.set(totalFetched.get() + queuedMessages.size());
                    System.out.println("Found '" + queuedMessages.size() + "' messages for queue '" + queueName + "'");
                }
            }
        }

        return new QueryPerformanceResult(Duration.between(wallStart, Instant.now()).toMillis(),
                                          latencies.stream().mapToLong(x -> x).average().orElse(0) / 1_000.0,
                                          percentile(latencies, 0.95),
                                          percentile(latencies, 0.99));
    }
}
