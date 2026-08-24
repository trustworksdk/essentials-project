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
import dk.trustworks.essentials.components.queue.postgresql.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two-table split measured <b>through the shipped component</b>, against the shared table it replaces.
 *
 * <h2>Why this needs to exist</h2>
 * The split's published figures — <b>1.38× overall and 1.62× on insert</b> for unordered traffic, 1.07× for ordered
 * — come from raw SQL against prototype schemas (see {@code docs/durable-queues.md}), not from
 * {@link PostgresqlSplitDurableQueues}. Those numbers appear in the user-facing summary, the class javadoc, the
 * module notes and the Spring property javadoc, so they are load-bearing advice resting on a prototype.
 * <p>
 * That is a known-dangerous position in this investigation. The cursor's prototype claim of <b>217×</b> became
 * <b>1.85×</b> when the same comparison was run through the component, because a prototype isolates one phase while
 * an end-to-end drain also pays the acknowledgement transaction that dominates everything. The split's prototype
 * arms measured raw insert/claim/ack loops with no framework, no per-message transaction and no consumer, so the
 * same dilution applies and the honest expectation is that the component number is <em>lower</em>.
 *
 * <h2>What is measured, and why index bytes are in the table</h2>
 * Enqueue and drain are timed separately, because the prototype's win was concentrated on insert and reporting only
 * a total would hide that. Index bytes are reported because they are the <b>mechanism</b>: the split's entire claim
 * is that unordered traffic stops paying maintenance on indexes it never uses. If the timings move without the
 * bytes moving, the explanation is wrong even when the number is right — the same reasoning that made the claim
 * statement counter necessary for batched fetch.
 * <p>
 * Both arms run on today's defaults — no batched acknowledgement, no batched fetch — so the comparison answers what
 * a deployment actually gets by flipping the flag, rather than what it could get by also changing something else.
 * <p>
 * Opt-in via {@code -Dbenchmark.run=true}; {@code -Dsplitbench.messages=4000}, {@code -Dsplitbench.orderedKeys=64}.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class SplitVsSharedTableBenchmarkIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("split-benchmark-db");

    private static final String BASE = "splitbench_queues";

    private HikariDataSource      dataSource;
    private JdbiUnitOfWorkFactory unitOfWorkFactory;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        dataSource.setUsername(postgreSQLContainer.getUsername());
        dataSource.setPassword(postgreSQLContainer.getPassword());
        dataSource.setAutoCommit(false);
        dataSource.setMaximumPoolSize(16);
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void shared_table_versus_split_for_unordered_and_ordered_traffic() {
        var messages    = Integer.getInteger("splitbench.messages", 4000);
        var orderedKeys = Integer.getInteger("splitbench.orderedKeys", 64);

        System.out.printf("%n%d messages per arm, %d ordered keys, batched acknowledgement in both arms: %s%n",
                          messages, orderedKeys, Boolean.getBoolean("splitbench.batchedAck"));
        System.out.printf("%-12s %-10s %-12s %-12s %-12s %-12s %-12s%n",
                          "traffic", "arm", "enqueue ms", "drain ms", "total ms", "index KB", "vs shared");

        var repetitions = Integer.getInteger("splitbench.repetitions", 3);
        var traffic = System.getProperty("splitbench.traffic", "both");
        var shapes = switch (traffic) {
            case "unordered" -> List.of(false);
            case "ordered" -> List.of(true);
            default -> List.of(false, true);
        };
        for (var ordered : shapes) {
            var label = ordered ? "ordered" : "unordered";
            // Medians of interleaved repetitions. Interleaved rather than all-of-one-arm-then-the-other because
            // consecutive runs of the same arm share warm caches and accumulated dead tuples, which is the
            // confound that produced a 5.7x artefact earlier in this investigation (§7).
            var sharedRuns = new ArrayList<Result>();
            var splitRuns  = new ArrayList<Result>();
            for (var repetition = 0; repetition < repetitions; repetition++) {
                sharedRuns.add(run(false, ordered, messages, orderedKeys));
                splitRuns.add(run(true, ordered, messages, orderedKeys));
            }
            // Per-repetition drains printed, not just the median. Ordered runs of an identical configuration have
            // differed by 4.75x, and a median hides whether that is drift, bimodality or one outlier - which is
            // exactly what has to be known before any ordered ratio can be quoted.
            System.out.printf("      [%s drains  shared=%s  split=%s]%n",
                              label,
                              sharedRuns.stream().map(r -> r.drainMs() + "ms").toList(),
                              splitRuns.stream().map(r -> r.drainMs() + "ms").toList());
            var shared = median(sharedRuns);
            var split  = median(splitRuns);
            System.out.printf("%-12s %-10s %-12d %-12d %-12d %-12d %-12s%n",
                              label, "shared", shared.enqueueMs, shared.drainMs, shared.totalMs(), shared.indexKb, "-");
            System.out.printf("%-12s %-10s %-12d %-12d %-12d %-12d %-12.2f%n",
                              label, "split", split.enqueueMs, split.drainMs, split.totalMs(), split.indexKb,
                              (double) shared.totalMs() / split.totalMs());
            System.out.printf("%-12s %-10s %-12.2f %-12.2f %-12.2f %-12.2f%n",
                              label, "ratio", ratio(shared.enqueueMs, split.enqueueMs), ratio(shared.drainMs, split.drainMs),
                              ratio(shared.totalMs(), split.totalMs()), ratio(shared.indexKb, split.indexKb));
        }
    }

    /**
     * Batched acknowledgement, applied identically to both arms when asked for.
     * <p>
     * At today's defaults the drain is bounded by one acknowledgement transaction per message, which the split does
     * not touch - so it swamps the index-maintenance difference the split exists to remove. Turning it on in both
     * arms does not favour either; it removes the thing hiding the effect, and answers the question a deployment
     * that has already enabled batched acknowledgement would ask.
     */
    private static BatchedAcknowledgementSettings acknowledgementSettings() {
        return Boolean.getBoolean("splitbench.batchedAck")
               ? BatchedAcknowledgementSettings.enabledWithDefaults()
               : BatchedAcknowledgementSettings.disabled();
    }

    /**
     * The polling interval, and the reason it is a knob rather than the default.
     * <p>
     * A drain of N messages takes at least {@code (N / parallelConsumers) x pollingInterval}, because the
     * centralized fetcher claims at most one slot's worth per queue per tick. At the defaults that floor is
     * 4 000 / 10 x 20 ms = 8 s - and the first run of this benchmark measured exactly 8 s in <b>every</b> arm,
     * shared and split, batched acknowledgement on and off. The drain was entirely polling-bound and could not have
     * shown a storage difference of any size. Raise the slot count and shorten the interval until the database is
     * the constraint, or the drain column is measuring the poll loop.
     */
    private static Duration pollingInterval() {
        return Duration.ofMillis(Integer.getInteger("splitbench.pollingIntervalMs", 20));
    }

    private static Result median(List<Result> results) {
        return new Result(medianOf(results, Result::enqueueMs),
                          medianOf(results, Result::drainMs),
                          medianOf(results, Result::indexKb));
    }

    private static long medianOf(List<Result> results, java.util.function.ToLongFunction<Result> field) {
        var values = results.stream().mapToLong(field).sorted().toArray();
        return values[values.length / 2];
    }

    private static double ratio(long shared, long split) {
        return split == 0 ? Double.NaN : (double) shared / split;
    }

    private Result run(boolean useSplit, boolean ordered, int messageCount, int orderedKeys) {
        dropTables();
        var durableQueues = useSplit
                            ? PostgresqlSplitDurableQueues.builder()
                                                          .setUnitOfWorkFactory(unitOfWorkFactory)
                                                          .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                          .setBaseQueueTableName(BASE)
                                                          .setBatchedAcknowledgementSettings(acknowledgementSettings())
                                                          .setPollingInterval(pollingInterval())
                                                          .build()
                            : PostgresqlDurableQueues.builder()
                                                     .setUnitOfWorkFactory(unitOfWorkFactory)
                                                     .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                     .setSharedQueueTableName(BASE)
                                                     .setUseBatchedAcknowledgement(Boolean.getBoolean("splitbench.batchedAck"))
                                                     .setCentralizedMessageFetcherPollingInterval(pollingInterval())
                                                     .build();
        durableQueues.start();
        try {
            var queueName = QueueName.of("SplitBench");

            // Enqueued in batches through the public API, identically for both arms - queueMessages is the path a
            // real producer uses and it is where the prototype located the win.
            var enqueueStartedAt = System.nanoTime();
            var batchSize        = 200;
            for (var offset = 0; offset < messageCount; offset += batchSize) {
                var batch = new ArrayList<Message>();
                for (var i = offset; i < Math.min(offset + batchSize, messageCount); i++) {
                    batch.add(ordered
                              ? OrderedMessage.of("m-" + i, "key-" + (i % orderedKeys), i / orderedKeys)
                              : Message.of("m-" + i));
                }
                durableQueues.queueMessages(queueName, batch);
            }
            var enqueueMs = Duration.ofNanos(System.nanoTime() - enqueueStartedAt).toMillis();

            // VACUUM ANALYZE both arms after seeding, before timing the drain.
            //
            // Not cosmetic. An index-only scan requires the visibility map to be set, which happens after a vacuum,
            // and the split's ordered drain was bimodal because of it: four repetitions at 13.8-17.2 s and one at
            // 4.6 s, while the shared arm stayed inside 11.5-14.3 s. That is a plan flipping between an index-only
            // scan and a heap-fetching one depending on whether autovacuum happened to have run, not a property of
            // either design. Normalising it makes the comparison about the schemas rather than about vacuum timing.
            vacuumAnalyze();

            // Index bytes read while the table is full, which is when maintenance cost is being paid.
            var indexKb = indexBytes() / 1024;
            // Per index, not just the total. The split's whole premise is "six indexes down to one", and a total
            // that barely moves means either the premise is wrong or something else is being counted - which the
            // breakdown settles and speculation does not.
            System.out.printf("      [%s/%s indexes: %s]%n",
                              useSplit ? "split" : "shared", ordered ? "ordered" : "unordered", indexSizes());

            // Counting the statements the drain actually issues, by shape. Theorising about where a 6x regression
            // comes from has already been wrong once (the two-transactions hypothesis, which the fix disproved), so
            // this counts rather than reasons.
            var statementsByShape = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>();
            var counting          = new java.util.concurrent.atomic.AtomicBoolean();
            unitOfWorkFactory.getJdbi().setSqlLogger(new SqlLogger() {
                @Override
                public void logAfterExecution(StatementContext context) {
                    if (!counting.get()) {
                        return;
                    }
                    var sql = context.getRenderedSql();
                    if (sql == null) {
                        return;
                    }
                    var shape = sql.contains("SKIP LOCKED") ? "claim"
                                : sql.startsWith("DELETE") ? "delete"
                                : sql.contains("is_being_delivered = FALSE, ") || sql.contains("reset") ? "reset"
                                : "other";
                    statementsByShape.computeIfAbsent(shape, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
                }
            });

            var handled       = new AtomicInteger();
            var drainStartedAt = System.nanoTime();
            counting.set(true);
            durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                           .setQueueName(queueName)
                                                           .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff()
                                                                                                .setRedeliveryDelay(Duration.ofMillis(100))
                                                                                                .setMaximumNumberOfRedeliveries(3)
                                                                                                .build())
                                                           .setParallelConsumers(Integer.getInteger("splitbench.parallelConsumers", 10))
                                                           .setQueueMessageHandler(message -> handled.incrementAndGet())
                                                           .build());
            var deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            while (handled.get() < messageCount && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            var drainMs = Duration.ofNanos(System.nanoTime() - drainStartedAt).toMillis();
            counting.set(false);
            System.out.printf("      [%s/%s statements: %s]%n",
                              useSplit ? "split" : "shared", ordered ? "ordered" : "unordered",
                              new java.util.TreeMap<>(statementsByShape).toString());

            assertThat(handled.get())
                    .as("%s arm must deliver every %s message", useSplit ? "split" : "shared", ordered ? "ordered" : "unordered")
                    .isEqualTo(messageCount);
            return new Result(enqueueMs, drainMs, indexKb);
        } finally {
            durableQueues.stop();
        }
    }

    /**
     * Index bytes across whichever tables the arm created - one for the shared arm, two for the split.
     */
    private long indexBytes() {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("""
                                                                       SELECT COALESCE(SUM(pg_relation_size(i.indexrelid)), 0)
                                                                         FROM pg_index i
                                                                         JOIN pg_class c ON c.oid = i.indrelid
                                                                        WHERE c.relname IN (:base, :unordered, :ordered)
                                                                       """)
                                                          .bind("base", BASE)
                                                          .bind("unordered", BASE + PostgresqlSplitDurableQueues.UNORDERED_TABLE_SUFFIX)
                                                          .bind("ordered", BASE + PostgresqlSplitDurableQueues.ORDERED_TABLE_SUFFIX)
                                                          .mapTo(Long.class)
                                                          .one());
    }

    /**
     * Retried, because this is teardown racing the previous arm's shutdown rather than anything about the product.
     * {@code stop()} returns before every worker connection is certainly gone, and a {@code DROP TABLE} that
     * collides with one deadlocks. It began failing only once the unordered arm got fast enough for the next arm's
     * drop to arrive while the last one was still unwinding.
     */
    /**
     * Every index on whichever tables the arm created, with its size - so "which indexes actually hold rows" is
     * answered rather than assumed. A partial index whose predicate matches nothing costs almost nothing to
     * maintain, and that is the difference between the split removing real work and removing empty structures.
     */
    private String indexSizes() {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("""
                                                                       SELECT i.relname || '=' || (pg_relation_size(i.oid) / 1024) || 'KB'
                                                                         FROM pg_index x
                                                                         JOIN pg_class c ON c.oid = x.indrelid
                                                                         JOIN pg_class i ON i.oid = x.indexrelid
                                                                        WHERE c.relname IN (:base, :unordered, :ordered)
                                                                        ORDER BY pg_relation_size(i.oid) DESC
                                                                       """)
                                                          .bind("base", BASE)
                                                          .bind("unordered", BASE + PostgresqlSplitDurableQueues.UNORDERED_TABLE_SUFFIX)
                                                          .bind("ordered", BASE + PostgresqlSplitDurableQueues.ORDERED_TABLE_SUFFIX)
                                                          .mapTo(String.class)
                                                          .list()
                                                          .toString());
    }

    private void vacuumAnalyze() {
        // VACUUM cannot run inside a transaction block, so it goes through a bare handle rather than a UnitOfWork.
        unitOfWorkFactory.getJdbi().useHandle(handle -> {
            // VACUUM cannot run inside a transaction block; Hikari hands out connections with autoCommit off.
            try {
                handle.getConnection().setAutoCommit(true);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Could not switch the connection to autoCommit for VACUUM", e);
            }
            for (var table : List.of(BASE, BASE + PostgresqlSplitDurableQueues.UNORDERED_TABLE_SUFFIX,
                                     BASE + PostgresqlSplitDurableQueues.ORDERED_TABLE_SUFFIX)) {
                var exists = handle.createQuery("SELECT to_regclass(:t) IS NOT NULL").bind("t", table).mapTo(Boolean.class).one();
                if (exists) {
                    // ANALYZE alone, VACUUM alone, or both - the two do different things and the operational
                    // advice differs: ANALYZE refreshes planner statistics and is cheap, VACUUM also sets the
                    // visibility map which is what an index-only scan needs.
                    handle.execute(System.getProperty("splitbench.maintenance", "VACUUM ANALYZE") + " " + table);
                }
            }
        });
    }

    private void dropTables() {
        for (var attempt = 1; ; attempt++) {
            try {
                dropTablesOnce();
                return;
            } catch (RuntimeException e) {
                if (attempt >= 5) {
                    throw e;
                }
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private void dropTablesOnce() {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE + PostgresqlSplitDurableQueues.UNORDERED_TABLE_SUFFIX);
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE + PostgresqlSplitDurableQueues.ORDERED_TABLE_SUFFIX);
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE);
        });
    }

    private record Result(long enqueueMs, long drainMs, long indexKb) {
        long totalMs() {
            return enqueueMs + drainMs;
        }
    }
}
