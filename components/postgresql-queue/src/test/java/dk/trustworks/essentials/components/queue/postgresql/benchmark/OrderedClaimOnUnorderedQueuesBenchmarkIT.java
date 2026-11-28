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

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.*;

/**
 * What the ordered claim costs on queues that never receive an ordered message (plan item C4): the classic engine runs
 * it first on every poll, and on such a queue it always returns no rows. Ten queues, unordered messages only, the
 * Spring starter's default wiring, at 0, 100 and 5 000 offered messages per second in total.
 * <p>
 * Reports per claim kind the statement count, the client-side time (execute to completion, as the single fetcher
 * thread sees it, so it includes the round trip) and the server-side time from {@code pg_stat_statements}, next to the
 * same figures for every statement the run sent. The ordered claim's share is the most that skipping it could save.
 * Uses only APIs that exist on every 0.60 release, so it runs before and after a change.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class OrderedClaimOnUnorderedQueuesBenchmarkIT {
    private static final int      QUEUES             = 10;
    private static final int      PARALLEL_CONSUMERS = 8;
    private static final Duration LOAD_DURATION      = Duration.ofSeconds(15);

    @Container
    private static final PostgreSQLContainer postgreSQLContainer =
            EssentialsTestContainers.postgres("ordered-claim-skip-db")
                                    .withCommand("postgres", "-c", "fsync=off",
                                                 "-c", "shared_preload_libraries=pg_stat_statements",
                                                 "-c", "pg_stat_statements.track=all");

    private HikariDataSource         dataSource;
    private Jdbi                     jdbi;
    private JdbiUnitOfWorkFactory    unitOfWorkFactory;
    private PostgresqlDurableQueues  durableQueues;
    private SpringWiredDurableQueues wired;

    private final ClientTiming ordered   = new ClientTiming();
    private final ClientTiming unordered = new ClientTiming();
    private final ClientTiming all       = new ClientTiming();

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        config.setUsername(postgreSQLContainer.getUsername());
        config.setPassword(postgreSQLContainer.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
        jdbi = Jdbi.create(dataSource);
        jdbi.useHandle(h -> h.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements"));
        jdbi.setSqlLogger(new SqlLogger() {
            @Override
            public void logAfterExecution(StatementContext context) {
                var start = context.getExecutionMoment();
                var end   = context.getCompletionMoment();
                if (start == null || end == null) {
                    return;
                }
                var nanos = Duration.between(start, end).toNanos();
                var sql   = context.getRenderedSql();
                all.record(nanos);
                if (sql != null && sql.contains("cte_ordered")) {
                    ordered.record(nanos);
                } else if (sql != null && sql.contains("cte_unordered")) {
                    unordered.record(nanos);
                }
            }
        });
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        wired = new SpringWiredDurableQueues(jdbi, unitOfWorkFactory);
        durableQueues = wired.durableQueues;
    }

    @AfterEach
    void tearDown() {
        wired.close();
        dataSource.close();
    }

    @Test
    void ordered_claim_cost_on_unordered_only_queues() throws Exception {
        var results = new ArrayList<String>();
        for (var rate : List.of(0, 100, 5_000)) {
            results.add(run(rate));
        }
        System.out.printf("%n=== Ordered claim on unordered-only queues: %d queues x %d consumers, %d s per rate ===%n%s%n%n",
                          QUEUES, PARALLEL_CONSUMERS, LOAD_DURATION.toSeconds(), String.join("\n", results));
    }

    private String run(int totalMessagesPerSecond) throws Exception {
        var queueNames = new ArrayList<QueueName>();
        var latencies  = new ConcurrentLinkedQueue<Long>();
        var handled    = new AtomicInteger();
        var consumers  = new ArrayList<DurableQueueConsumer>();
        for (var i = 0; i < QUEUES; i++) {
            var queueName = QueueName.of("Rate" + totalMessagesPerSecond + "-Q" + i);
            queueNames.add(queueName);
            consumers.add(durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                                         .setQueueName(queueName)
                                                                         .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                                         .setParallelConsumers(PARALLEL_CONSUMERS)
                                                                         .setQueueMessageHandler(message -> {
                                                                             latencies.add(Duration.between(message.getAddedTimestamp(), OffsetDateTime.now()).toMillis());
                                                                             handled.incrementAndGet();
                                                                         })
                                                                         .build()));
        }
        // Let the fetcher reach a steady state (and the idle back-off its cap) before counting
        Thread.sleep(3_000);
        resetCounters();

        var startedAt = System.nanoTime();
        var sent      = 0;
        if (totalMessagesPerSecond == 0) {
            Thread.sleep(LOAD_DURATION.toMillis());
        } else {
            // One tick per 10 ms; messages spread round-robin over the queues, one unit of work per queue per tick
            var perTickExact = totalMessagesPerSecond / 100.0;
            var carry        = 0.0;
            var nextQueue    = 0;
            var until        = System.nanoTime() + LOAD_DURATION.toNanos();
            var next         = System.nanoTime();
            while (System.nanoTime() < until) {
                carry += perTickExact;
                var thisTick = (int) carry;
                carry -= thisTick;
                var perQueue = new HashMap<QueueName, List<Message>>();
                for (var i = 0; i < thisTick; i++) {
                    perQueue.computeIfAbsent(queueNames.get(nextQueue), q -> new ArrayList<>()).add(Message.of("payload-" + sent++));
                    nextQueue = (nextQueue + 1) % QUEUES;
                }
                perQueue.forEach((queueName, batch) -> unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessages(queueName, batch)));
                next += 10_000_000L;
                var sleep = next - System.nanoTime();
                if (sleep > 0) {
                    Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
                }
            }
            var total = sent;
            Awaitility.waitAtMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(10)).until(() -> handled.get() >= total);
        }
        var elapsedSeconds = (System.nanoTime() - startedAt) / 1e9;

        var orderedClient   = ordered.snapshot();
        var unorderedClient = unordered.snapshot();
        var allClient       = all.snapshot();
        var orderedServer   = serverStats("%cte_ordered%");
        var unorderedServer = serverStats("%cte_unordered%");
        var allServer       = serverStats("%");
        consumers.forEach(DurableQueueConsumer::cancel);

        var sb = new StringBuilder();
        sb.append(String.format("  %,5d msg/s offered over %.1f s: %,d messages", totalMessagesPerSecond, elapsedSeconds, sent));
        if (!latencies.isEmpty()) {
            var sorted = latencies.stream().sorted().toList();
            sb.append(String.format(", latency p50 %d ms, p99 %d ms", percentile(sorted, 0.50), percentile(sorted, 0.99)));
        }
        sb.append('\n');
        sb.append(line("ordered claim", orderedClient, orderedServer, sent, elapsedSeconds));
        sb.append(line("unordered claim", unorderedClient, unorderedServer, sent, elapsedSeconds));
        sb.append(line("all statements", allClient, allServer, sent, elapsedSeconds));
        sb.append(String.format("      ordered claim share: %.1f%% of client statement time, %.1f%% of server statement time, %.1f%% of statements",
                                pct(orderedClient.totalNanos, allClient.totalNanos),
                                pct(orderedServer.totalMillis * 1e6, allServer.totalMillis * 1e6),
                                pct(orderedClient.calls, allClient.calls)));
        return sb.toString();
    }

    private static String line(String label, ClientSnapshot client, ServerStats server, int messages, double seconds) {
        return String.format("      %-16s %,8d calls (%6.1f/s%s), client %,9.1f ms total, mean %,7.1f us | server %,9.1f ms total, mean %,7.1f us, %,d rows%n",
                             label, client.calls, client.calls / seconds,
                             messages > 0 ? String.format(", %.3f per msg", (double) client.calls / messages) : "",
                             client.totalNanos / 1e6, client.calls == 0 ? 0 : client.totalNanos / 1e3 / client.calls,
                             server.totalMillis, server.calls == 0 ? 0 : server.totalMillis * 1e3 / server.calls, server.rows);
    }

    private void resetCounters() {
        jdbi.useHandle(h -> h.execute("SELECT pg_stat_statements_reset()"));
        ordered.reset();
        unordered.reset();
        all.reset();
    }

    private ServerStats serverStats(String queryPattern) {
        return jdbi.withHandle(h -> h.createQuery("""
                                                  SELECT COALESCE(SUM(calls), 0) AS calls, COALESCE(SUM(total_exec_time + total_plan_time), 0) AS total_ms, COALESCE(SUM(rows), 0) AS rows
                                                  FROM pg_stat_statements
                                                  WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                                                    AND query LIKE :pattern
                                                    AND query NOT LIKE '%pg_stat_statements%'
                                                  """)
                                     .bind("pattern", queryPattern)
                                     .map((rs, ctx) -> new ServerStats(rs.getLong("calls"), rs.getDouble("total_ms"), rs.getLong("rows")))
                                     .one());
    }

    private static double pct(double part, double whole) {
        return whole == 0 ? 0 : 100.0 * part / whole;
    }

    private static long percentile(List<Long> sorted, double percentile) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1));
    }

    private record ServerStats(long calls, double totalMillis, long rows) {
    }

    private record ClientSnapshot(long calls, long totalNanos) {
    }

    private static final class ClientTiming {
        private final AtomicLong calls      = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();

        void record(long nanos) {
            calls.incrementAndGet();
            totalNanos.addAndGet(nanos);
        }

        void reset() {
            calls.set(0);
            totalNanos.set(0);
        }

        ClientSnapshot snapshot() {
            return new ClientSnapshot(calls.get(), totalNanos.get());
        }
    }
}
