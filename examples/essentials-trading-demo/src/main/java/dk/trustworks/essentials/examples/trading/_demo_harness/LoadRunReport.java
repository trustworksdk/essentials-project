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
package dk.trustworks.essentials.examples.trading._demo_harness;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountsAggregateTypes;
import dk.trustworks.essentials.examples.trading.orders.types.OrdersAggregateTypes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The outcome of an external load-driver run (design phase 6), worked out from what the brokerage recorded: orders by
 * status, the time from {@code OrderPlaced} to each {@code OrderFilled} and to the ledger's {@code TradeBooked}, those
 * latencies per placement bucket (so a burst's backlog and its drain show), payments by kind and status, queued and
 * dead-lettered messages per durable queue, and the largest tables. Server-side timestamps only - the driver measures its own requests.
 * <p>
 * The driver names every order {@code <runPrefix>O<n>} and every payment {@code <runPrefix>D<n>} / {@code W<n>}; its
 * customers' accounts share {@code accountPrefix} across runs.
 */
@Component
public class LoadRunReport {
    public record Latency(long count, double p50Ms, double p99Ms, double maxMs) {
    }

    public record Orders(long placed, Map<String, Long> byStatus, long nonTerminal, Latency placedToFilled, Latency placedToBooked, long fillsNotBooked) {
    }

    public record Bucket(Instant start, long placed, long fills, long booked, double p50BookedMs, double p99BookedMs) {
    }

    public record StoreSize(String table, long rows, long bytes) {
    }

    public record QueueDepth(long queued, long deadLetters) {
    }

    public record Report(String runPrefix, Instant at, Orders orders, Map<String, Long> payments, List<Bucket> buckets, Map<String, QueueDepth> queues,
                         List<StoreSize> largestTables) {
    }

    private final JdbcTemplate                                                            jdbcTemplate;
    private final ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private final DurableQueues                                                           durableQueues;

    public LoadRunReport(JdbcTemplate jdbcTemplate, ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore, DurableQueues durableQueues) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        this.durableQueues = requireNonNull(durableQueues, "No durableQueues provided");
    }

    private record Execution(String orderId, Instant placedAt, Instant filledAt, Instant bookedAt) {
    }

    public Report report(String runPrefix, String accountPrefix, Duration bucket) {
        requireNonNull(runPrefix, "No runPrefix provided");
        requireNonNull(accountPrefix, "No accountPrefix provided");
        requireNonNull(bucket, "No bucket provided");
        var byStatus = new TreeMap<String, Long>();
        jdbcTemplate.query("SELECT status, count(*) FROM projection_orders WHERE order_id LIKE ? GROUP BY status",
                           rs -> {
                               byStatus.put(rs.getString(1), rs.getLong(2));
                           },
                           runPrefix + "%");
        var nonTerminal = byStatus.entrySet().stream()
                                  .filter(e -> !Set.of("FILLED", "CANCELLED", "REJECTED", "EXPIRED").contains(e.getKey()))
                                  .mapToLong(Map.Entry::getValue).sum();

        var orders   = eventStore.getAggregateEventStreamConfiguration(OrdersAggregateTypes.ORDERS);
        var accounts = eventStore.getAggregateEventStreamConfiguration(AccountsAggregateTypes.ACCOUNTS);
        var o        = orders.eventStreamTableColumnNames;
        var a        = accounts.eventStreamTableColumnNames;
        // Table and column names come from the event store's configuration, not from a request
        var placed = jdbcTemplate.queryForObject("SELECT count(*) FROM " + orders.eventStreamTableName + " WHERE " + o.aggregateIdColumn + " LIKE ? AND " + o.eventTypeColumn + " LIKE '%.OrderPlaced'",
                                                 Long.class, runPrefix + "%");
        var executions = jdbcTemplate.query(
                "WITH placed AS (SELECT " + o.aggregateIdColumn + " AS order_id, " + o.timestampColumn + " AS at FROM " + orders.eventStreamTableName
                + "                WHERE " + o.aggregateIdColumn + " LIKE ? AND " + o.eventTypeColumn + " LIKE '%.OrderPlaced'),"
                + " fills AS (SELECT " + o.aggregateIdColumn + " AS order_id, " + o.eventPayloadColumn + "->>'executionId' AS execution_id, " + o.timestampColumn + " AS at"
                + "             FROM " + orders.eventStreamTableName + " WHERE " + o.aggregateIdColumn + " LIKE ? AND " + o.eventTypeColumn + " LIKE '%.OrderFilled'),"
                + " booked AS (SELECT " + a.eventPayloadColumn + "->'trade'->>'executionId' AS execution_id, min(" + a.timestampColumn + ") AS at FROM " + accounts.eventStreamTableName
                + "              WHERE " + a.aggregateIdColumn + " LIKE ? AND " + a.eventTypeColumn + " LIKE '%.TradeBooked' GROUP BY 1)"
                + " SELECT f.order_id, p.at, f.at, b.at FROM fills f JOIN placed p ON p.order_id = f.order_id LEFT JOIN booked b ON b.execution_id = f.execution_id",
                (rs, row) -> new Execution(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(),
                                           rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()),
                runPrefix + "%", runPrefix + "%", accountPrefix + "%");

        var toFilled = executions.stream().mapToLong(e -> Duration.between(e.placedAt(), e.filledAt()).toMillis()).sorted().toArray();
        var toBooked = executions.stream().filter(e -> e.bookedAt() != null).mapToLong(e -> Duration.between(e.placedAt(), e.bookedAt()).toMillis()).sorted().toArray();

        var placedPerBucket = new TreeMap<Instant, Long>();
        jdbcTemplate.query("SELECT " + o.timestampColumn + " FROM " + orders.eventStreamTableName + " WHERE " + o.aggregateIdColumn + " LIKE ? AND " + o.eventTypeColumn + " LIKE '%.OrderPlaced'",
                           rs -> {
                               placedPerBucket.merge(bucketOf(rs.getTimestamp(1).toInstant(), bucket), 1L, Long::sum);
                           },
                           runPrefix + "%");
        var executionsPerBucket = new TreeMap<Instant, List<Execution>>();
        executions.forEach(e -> executionsPerBucket.computeIfAbsent(bucketOf(e.placedAt(), bucket), k -> new ArrayList<>()).add(e));
        var buckets = new ArrayList<Bucket>();
        placedPerBucket.forEach((start, count) -> {
            var inBucket = executionsPerBucket.getOrDefault(start, List.of());
            var booked = inBucket.stream().filter(e -> e.bookedAt() != null).mapToLong(e -> Duration.between(e.placedAt(), e.bookedAt()).toMillis()).sorted().toArray();
            buckets.add(new Bucket(start, count, inBucket.size(), booked.length, percentile(booked, 0.50), percentile(booked, 0.99)));
        });

        var payments = new TreeMap<String, Long>();
        jdbcTemplate.query("SELECT kind, status, count(*) FROM projection_funding_payment WHERE reference LIKE ? GROUP BY kind, status",
                           rs -> {
                               payments.put(rs.getString(1) + ":" + rs.getString(2), rs.getLong(3));
                           },
                           runPrefix + "%");
        var queues = new TreeMap<String, QueueDepth>();
        for (var queueName : durableQueues.getQueueNames()) {
            queues.put(queueName.toString(), new QueueDepth(durableQueues.getTotalMessagesQueuedFor(queueName), durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)));
        }
        var largest = jdbcTemplate.query("SELECT relname, n_live_tup, pg_total_relation_size(relid) FROM pg_stat_user_tables ORDER BY 3 DESC LIMIT 15",
                                         (rs, row) -> new StoreSize(rs.getString(1), rs.getLong(2), rs.getLong(3)));
        return new Report(runPrefix, Instant.now(),
                          new Orders(placed == null ? 0 : placed, byStatus, nonTerminal, latency(toFilled), latency(toBooked), toFilled.length - toBooked.length),
                          payments, buckets, queues, largest);
    }

    private static Instant bucketOf(Instant at, Duration bucket) {
        var size = bucket.toMillis();
        return Instant.ofEpochMilli(at.toEpochMilli() / size * size);
    }

    private static Latency latency(long[] sortedMillis) {
        return new Latency(sortedMillis.length, percentile(sortedMillis, 0.50), percentile(sortedMillis, 0.99),
                           sortedMillis.length == 0 ? 0 : sortedMillis[sortedMillis.length - 1]);
    }

    private static double percentile(long[] sortedMillis, double percentile) {
        if (sortedMillis.length == 0) {
            return 0;
        }
        var index = (int) Math.min(sortedMillis.length - 1, Math.ceil(percentile * sortedMillis.length) - 1);
        return sortedMillis[Math.max(0, index)];
    }
}
