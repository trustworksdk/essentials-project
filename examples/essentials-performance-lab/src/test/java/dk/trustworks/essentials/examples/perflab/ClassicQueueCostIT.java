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

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.postgresql.ListenNotify;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.postgresql.*;
import dk.trustworks.essentials.examples.perflab.harness.*;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.PGConnection;
import org.slf4j.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 0 of {@code docs/plans/classic_queue_improvements.md}: what the classic engine's change-notification trigger
 * costs, and what the stuck-message reset query costs with and without a partial index on {@code delivery_ts}.
 * <p>
 * Classic engine only ({@link PostgresqlDurableQueues}). Within one load shape the arms vary one thing - the
 * notification trigger, or the extra index - and are interleaved by {@link AbRunner}, so they are comparable within
 * a run and not across runs.
 * <p>
 * The trigger is put in place explicitly after {@code start()} in every arm (all candidates dropped first), so no
 * arm inherits a trigger or index from the previous one through the shared table.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class ClassicQueueCostIT {
    private static final Logger log = LoggerFactory.getLogger(ClassicQueueCostIT.class);

    private static final int    PAYLOAD_BYTES        = 200;
    private static final int    REPETITIONS          = 3;
    private static final int    BATCHED_MESSAGES     = 20_000;
    private static final int    BATCH_SIZE           = 100;
    private static final int    CONCURRENT_MESSAGES  = 8_000;
    private static final int    CONCURRENT_PRODUCERS = 8;
    private static final int    SAMPLE_INTERVAL_MS   = 20;
    private static final String TABLE                = "perflab_classic_queues";
    private static final String RESET_TABLE          = "perflab_classic_reset";
    private static final String STATEMENT_TRIGGER    = "perflab_stmt_notify_" + TABLE;
    private static final String STATEMENT_FUNCTION   = "perflab_stmt_notify_" + TABLE + "_fn";
    private static final String STUCK_INDEX          = "idx_" + TABLE + "_stuck";
    /** The columns {@code PostgresqlDurableQueues} passes to its own trigger. */
    private static final String[] NOTIFY_COLUMNS     = {"id", "queue_name", "added_ts", "next_delivery_ts", "delivery_ts", "is_dead_letter_message", "is_being_delivered"};

    private static final JsonMapper JSON = JsonMapper.builder().build();

    enum Shape {BATCHED, CONCURRENT}

    /**
     * {@link #AFTER_COMMIT_COALESCED}: listener present, no trigger; producers hand the queue name to a
     * {@link CoalescingNotifier} after their enqueue transaction committed, which sends one autocommit
     * {@code pg_notify} statement per drained batch of queue names.
     */
    enum Trigger {ROW_INSERT_UPDATE, ROW_INSERT_ONLY, STATEMENT_PER_QUEUE, AFTER_COMMIT_COALESCED, NONE}

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;
    private Jdbi             jdbi;
    /** Statements the harness itself commits while a run is being measured, subtracted from the commit count. */
    private final AtomicLong harnessCommits = new AtomicLong();

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(60);
        dataSource = new HikariDataSource(config);
        jdbi = Jdbi.create(dataSource).installPlugin(new PostgresPlugin());
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Test 1 - notification trigger arms
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void notification_trigger_cost_per_arm() throws Exception {
        var environment = PgSnapshot.captureEnvironment(dataSource);
        var noListenerProbe = probeNoListenerInstallsNoTrigger();
        log.info("no-listener probe on a fresh table: {}", noListenerProbe);
        assertThat(noListenerProbe.get("userTriggers")).as("setCreateMultiTableChangeListener(false) must install no trigger").isEqualTo(0L);

        var output = new LinkedHashMap<String, Object>();
        output.put("comparison", "classic queue notification trigger arms");
        output.put("payloadBytes", PAYLOAD_BYTES);
        output.put("environment", environment);
        output.put("labPostgres", LabPostgres.describe());
        output.put("noListenerProbe", noListenerProbe);

        var tables = new ArrayList<String>();
        for (var shape : Shape.values()) {
            var arms = new LinkedHashMap<String, IntFunction<RunResult>>();
            arms.put("row-insert-update", rep -> measure(shape, Trigger.ROW_INSERT_UPDATE, false, rep, environment));
            arms.put("row-insert-only", rep -> measure(shape, Trigger.ROW_INSERT_ONLY, false, rep, environment));
            arms.put("statement-per-queue", rep -> measure(shape, Trigger.STATEMENT_PER_QUEUE, false, rep, environment));
            arms.put("after-commit-coalesced", rep -> measure(shape, Trigger.AFTER_COMMIT_COALESCED, false, rep, environment));
            arms.put("no-listener", rep -> measure(shape, Trigger.NONE, false, rep, environment));
            if (shape == Shape.BATCHED) {
                arms.put("row-insert-update+stuck-idx", rep -> measure(shape, Trigger.ROW_INSERT_UPDATE, true, rep, environment));
            }
            var results = new AbRunner(REPETITIONS).run(arms);
            var summaries = AbRunner.summarize(results);
            var table = renderTable(shape, results, summaries);
            tables.add(table);
            log.info("\n{}", table);
            output.put(shape.name().toLowerCase(Locale.ROOT) + ".summaries", summaries);
            output.put(shape.name().toLowerCase(Locale.ROOT) + ".runs", results);
            RunResult.writeAll("target/perf-lab-baseline/classic-queue-cost.json", output);

            var expected = shape == Shape.BATCHED ? BATCHED_MESSAGES : CONCURRENT_MESSAGES;
            results.forEach(result -> {
                assertThat(result.opsCompleted()).isEqualTo(expected);
                assertThat((Long) result.extra().get("inserts"))
                        .as("%s rep %d: table statistics had not settled", result.arm(), result.repetition())
                        .isEqualTo((long) expected);
            });
        }
        tables.forEach(table -> log.info("\n{}", table));
    }

    private RunResult measure(Shape shape, Trigger trigger, boolean stuckIndex, int repetition, Map<String, String> environment) {
        try {
            return doMeasure(shape, trigger, stuckIndex, repetition, environment);
        } catch (Exception e) {
            throw new IllegalStateException(shape + "/" + trigger + " failed", e);
        }
    }

    private RunResult doMeasure(Shape shape, Trigger trigger, boolean stuckIndex, int repetition, Map<String, String> environment) throws Exception {
        var messageCount = shape == Shape.BATCHED ? BATCHED_MESSAGES : CONCURRENT_MESSAGES;
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(jdbi))
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .setSharedQueueTableName(TABLE)
                                                   .setUseCentralizedMessageFetcher(true)
                                                   .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(20))
                                                   .setCreateMultiTableChangeListener(trigger != Trigger.NONE)
                                                   .build();
        durableQueues.start();
        var queueName = QueueName.of("bench-queue");
        var handled = new AtomicInteger();
        DurableQueueConsumer consumer = null;
        var stopped = false;
        try {
            applyArmSchema(trigger, stuckIndex);
            var triggers = userTriggers(TABLE);
            assertThat(triggers).as("triggers for %s", trigger).hasSize(trigger == Trigger.NONE || trigger == Trigger.AFTER_COMMIT_COALESCED ? 0 : 1);
            execute("TRUNCATE TABLE " + TABLE);

            consumer = durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                                     .setQueueName(queueName)
                                                                     .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                                     .setParallelConsumers(20)
                                                                     .setQueueMessageHandler(message -> handled.incrementAndGet())
                                                                     .build());

            harnessCommits.set(0);
            // Notifier statements are part of the arm's cost, so they are not subtracted from the commit count
            var notifier = trigger == Trigger.AFTER_COMMIT_COALESCED ? new CoalescingNotifier(ListenNotify.resolveTableChangeChannelName(TABLE)) : null;
            try (var notifications = new NotificationCounter(ListenNotify.resolveTableChangeChannelName(TABLE));
                 var sampler = new WaitEventSampler()) {
                var before = PgSnapshot.capture(dataSource, List.of(TABLE));
                var startNanos = System.nanoTime();

                var payload = incompressibleText(PAYLOAD_BYTES);
                if (shape == Shape.BATCHED) {
                    for (var batch = 0; batch < messageCount / BATCH_SIZE; batch++) {
                        var messages = new ArrayList<Message>(BATCH_SIZE);
                        for (var index = 0; index < BATCH_SIZE; index++) {
                            messages.add(Message.of(new BenchPayload(payload)));
                        }
                        durableQueues.queueMessages(queueName, messages);
                        // queueMessages ran in its own unit of work, which has committed by now
                        if (notifier != null) {
                            notifier.queued(queueName);
                        }
                    }
                } else {
                    var executor = Executors.newFixedThreadPool(CONCURRENT_PRODUCERS);
                    try {
                        var futures = new ArrayList<Future<?>>();
                        for (var producer = 0; producer < CONCURRENT_PRODUCERS; producer++) {
                            futures.add(executor.submit(() -> {
                                for (var index = 0; index < messageCount / CONCURRENT_PRODUCERS; index++) {
                                    durableQueues.queueMessage(queueName, Message.of(new BenchPayload(payload)));
                                    if (notifier != null) {
                                        notifier.queued(queueName);
                                    }
                                }
                            }));
                        }
                        for (var future : futures) {
                            future.get(10, TimeUnit.MINUTES);
                        }
                    } finally {
                        executor.shutdownNow();
                    }
                }
                Awaitility.await().atMost(Duration.ofSeconds(300))
                          .untilAsserted(() -> assertThat(handled.get()).isEqualTo(messageCount));
                Awaitility.await().atMost(Duration.ofSeconds(60))
                          .pollInterval(Duration.ofMillis(50))
                          .untilAsserted(() -> assertThat(rowCount(TABLE)).isZero());
                var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

                sampler.stop();
                if (notifier != null) {
                    notifier.close();
                }
                // Notifications are delivered asynchronously after commit; let the last ones arrive
                Thread.sleep(500);
                notifications.stop();
                consumer.stop();
                consumer = null;
                durableQueues.stop();
                stopped = true;

                settleStatistics(before, messageCount);
                var after = PgSnapshot.capture(dataSource, List.of(TABLE));
                return buildResult(shape, trigger, stuckIndex, repetition, elapsedMillis, messageCount, before, after,
                                   notifications.count(), sampler, triggers, environment, notifier);
            } finally {
                if (notifier != null) {
                    notifier.close();
                }
            }
        } finally {
            if (consumer != null) {
                consumer.stop();
            }
            if (!stopped) {
                durableQueues.stop();
            }
        }
    }

    private void applyArmSchema(Trigger trigger, boolean stuckIndex) throws SQLException {
        execute("DROP TRIGGER IF EXISTS notify_on_" + TABLE + "_changes ON " + TABLE);
        execute("DROP TRIGGER IF EXISTS " + STATEMENT_TRIGGER + " ON " + TABLE);
        execute("DROP INDEX IF EXISTS " + STUCK_INDEX);
        switch (trigger) {
            case ROW_INSERT_UPDATE -> jdbi.useHandle(handle -> ListenNotify.addChangeNotificationTriggerToTable(
                    handle, TABLE, List.of(ListenNotify.SqlOperation.INSERT, ListenNotify.SqlOperation.UPDATE), NOTIFY_COLUMNS));
            case ROW_INSERT_ONLY -> jdbi.useHandle(handle -> ListenNotify.addChangeNotificationTriggerToTable(
                    handle, TABLE, List.of(ListenNotify.SqlOperation.INSERT), NOTIFY_COLUMNS));
            case STATEMENT_PER_QUEUE -> {
                // added_ts is not in the payload the plan describes, but QueueTableNotificationHandler builds a
                // DefaultQueuedMessage from the notification and that requires a non-null addedTimestamp - without it
                // every notification is logged as an error and wakes nothing. now() is the transaction's start time,
                // so the payload stays identical for every statement of one transaction and PostgreSQL folds them.
                execute("CREATE OR REPLACE FUNCTION " + STATEMENT_FUNCTION + "() RETURNS trigger AS $$\n" +
                        "BEGIN\n" +
                        "  PERFORM pg_notify('" + ListenNotify.resolveTableChangeChannelName(TABLE) + "',\n" +
                        "                    json_build_object('table_name', TG_TABLE_NAME, 'sql_operation', 'INSERT',\n" +
                        "                                      'queue_name', q.queue_name, 'added_ts', now())::text)\n" +
                        "  FROM (SELECT DISTINCT queue_name FROM new_rows) q;\n" +
                        "  RETURN NULL;\n" +
                        "END;\n" +
                        "$$ LANGUAGE plpgsql");
                execute("CREATE TRIGGER " + STATEMENT_TRIGGER + " AFTER INSERT ON " + TABLE +
                        " REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION " + STATEMENT_FUNCTION + "()");
            }
            case AFTER_COMMIT_COALESCED, NONE -> {
            }
        }
        if (stuckIndex) {
            execute("CREATE INDEX " + STUCK_INDEX + " ON " + TABLE + " (delivery_ts) WHERE is_being_delivered");
        }
    }

    private Map<String, Object> probeNoListenerInstallsNoTrigger() throws Exception {
        var probeTable = "perflab_classic_nolistener_probe";
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(jdbi))
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .setSharedQueueTableName(probeTable)
                                                   .setCreateMultiTableChangeListener(false)
                                                   .build();
        durableQueues.start();
        try {
            var triggers = userTriggers(probeTable);
            return Map.of("table", probeTable, "userTriggers", (long) triggers.size(), "triggers", triggers);
        } finally {
            durableQueues.stop();
            execute("DROP TABLE IF EXISTS " + probeTable + " CASCADE");
        }
    }

    private List<String> userTriggers(String table) throws SQLException {
        var triggers = new ArrayList<String>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT tgname, pg_get_triggerdef(oid) FROM pg_trigger WHERE tgrelid = ?::regclass AND NOT tgisinternal")) {
            statement.setString(1, table);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    triggers.add(resultSet.getString(2));
                }
            }
        }
        return triggers;
    }

    private RunResult buildResult(Shape shape, Trigger trigger, boolean stuckIndex, int repetition, long elapsedMillis, int messageCount,
                                  PgSnapshot before, PgSnapshot after, long notifications, WaitEventSampler sampler,
                                  List<String> triggers, Map<String, String> environment, CoalescingNotifier notifier) {
        var delta = after.deltaFrom(before);
        var updates = delta.getOrDefault("table." + TABLE + ".n_tup_upd", 0L);
        var deletes = delta.getOrDefault("table." + TABLE + ".n_tup_del", 0L);
        var commits = delta.getOrDefault("db.xact_commit", 0L) - harnessCommits.get();
        var extra = new LinkedHashMap<String, Object>();
        extra.put("trigger", trigger.name());
        extra.put("stuckIndex", stuckIndex);
        extra.put("triggers", triggers);
        extra.put("inserts", delta.getOrDefault("table." + TABLE + ".n_tup_ins", 0L));
        extra.put("tupleUpdates", updates);
        extra.put("tupleDeletes", deletes);
        extra.put("deadTuplesCreatedPerMessage", (double) (updates + deletes) / messageCount);
        extra.put("updatesPerMessage", (double) updates / messageCount);
        extra.put("harnessCommitsSubtracted", harnessCommits.get());
        extra.put("commitsPerMessage", (double) commits / messageCount);
        extra.put("notifications", notifications);
        extra.put("notificationsPerMessage", (double) notifications / messageCount);
        extra.put("notifierStatements", notifier == null ? 0L : notifier.statements());
        extra.put("notifierStatementsPerMessage", notifier == null ? 0.0d : (double) notifier.statements() / messageCount);
        extra.put("notifierQueueNamesSent", notifier == null ? 0L : notifier.queueNamesSent());
        extra.put("activeSamples", sampler.activeSamples());
        extra.put("sampleTicks", sampler.ticks());
        extra.put("waitLockShare", sampler.share(key -> key.startsWith("Lock/")));
        extra.put("waitLockObjectShare", sampler.share(key -> key.equals("Lock/object")));
        extra.put("waitLWLockShare", sampler.share(key -> key.startsWith("LWLock/")));
        extra.put("waitWalWriteShare", sampler.share(key -> key.toLowerCase(Locale.ROOT).endsWith("/walwrite")));
        extra.put("waitWalSyncShare", sampler.share(key -> key.toLowerCase(Locale.ROOT).endsWith("/walsync")));
        extra.put("waitEvents", sampler.counts());
        return new RunResult("classic-queue-" + shape.name().toLowerCase(Locale.ROOT), armName(trigger, stuckIndex), repetition, Instant.now(), elapsedMillis,
                             messageCount,
                             elapsedMillis == 0 ? 0.0d : messageCount * 1000.0d / elapsedMillis,
                             Map.of("messageCount", messageCount, "payloadBytes", PAYLOAD_BYTES, "shape", shape.name()),
                             List.of(), delta, Map.of(), environment, extra);
    }

    private static String armName(Trigger trigger, boolean stuckIndex) {
        var name = switch (trigger) {
            case ROW_INSERT_UPDATE -> "row-insert-update";
            case ROW_INSERT_ONLY -> "row-insert-only";
            case STATEMENT_PER_QUEUE -> "statement-per-queue";
            case AFTER_COMMIT_COALESCED -> "after-commit-coalesced";
            case NONE -> "no-listener";
        };
        return stuckIndex ? name + "+stuck-idx" : name;
    }

    private static String renderTable(Shape shape, List<RunResult> results, List<AbRunner.ArmSummary> summaries) {
        var builder = new StringBuilder();
        builder.append("======== CLASSIC QUEUE ").append(shape).append(", ")
               .append(shape == Shape.BATCHED ? BATCHED_MESSAGES + " msgs, 100/tx, 1 producer" : CONCURRENT_MESSAGES + " msgs, 1/tx, 8 producers")
               .append(", ").append(REPETITIONS).append(" reps, ").append(PAYLOAD_BYTES).append("-byte payloads ========\n");
        builder.append(String.format("%-28s %9s %6s %8s %7s %8s %8s %6s %7s %7s %7s %7s %7s %9s %9s%n",
                                     "arm", "WAL B/msg", "IQR%", "dead/msg", "upd/msg", "cmt/msg", "ntf/msg",
                                     "Lock%", "LkObj%", "LWLk%", "WALWr%", "WalSy%", "samples", "msgs/s", "nStmt/msg"));
        for (var summary : summaries) {
            var arm = results.stream().filter(r -> r.arm().equals(summary.arm())).toList();
            var wal = summary.walBytesPerOperation();
            builder.append(String.format("%-28s %9.0f %5.1f%% %8.2f %7.2f %8.3f %8.3f %5.1f%% %6.1f%% %6.1f%% %6.1f%% %6.1f%% %7d %9.0f %9.4f%n",
                                         summary.arm(),
                                         wal.median(),
                                         100.0d * wal.interQuartileRange() / Math.max(1.0d, wal.median()),
                                         mean(arm, "deadTuplesCreatedPerMessage"),
                                         mean(arm, "updatesPerMessage"),
                                         mean(arm, "commitsPerMessage"),
                                         mean(arm, "notificationsPerMessage"),
                                         100 * mean(arm, "waitLockShare"),
                                         100 * mean(arm, "waitLockObjectShare"),
                                         100 * mean(arm, "waitLWLockShare"),
                                         100 * mean(arm, "waitWalWriteShare"),
                                         100 * mean(arm, "waitWalSyncShare"),
                                         arm.stream().mapToLong(r -> (Long) r.extra().get("activeSamples")).sum(),
                                         summary.throughputPerSecond().median(),
                                         mean(arm, "notifierStatementsPerMessage")));
        }
        builder.append("WAL = median, IQR relative; other ratios = mean of reps; wait shares = share of sampled active backends (pooled over reps, mean of per-rep shares)\n");
        return builder.toString();
    }

    private static double mean(List<RunResult> results, String key) {
        return results.stream().mapToDouble(r -> ((Number) r.extra().get(key)).doubleValue()).average().orElse(Double.NaN);
    }

    /**
     * Wait for the tuple counters to reach what this run is known to have done (copied from
     * {@code ShardOwnedVsBaselineCostIT}, relative to the before-snapshot because the table is truncated, not
     * re-created, between runs), then for {@code xact_commit} to stop moving so idle backends' pending counts are in.
     */
    private void settleStatistics(PgSnapshot before, long messages) throws Exception {
        var inserts = before.gauge("table." + TABLE + ".n_tup_ins") + messages;
        var deletes = before.gauge("table." + TABLE + ".n_tup_del") + messages;
        var deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        var settled = false;
        while (System.nanoTime() < deadline && !settled) {
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement("SELECT n_tup_ins, n_tup_del FROM pg_stat_user_tables WHERE relname = ?")) {
                statement.setString(1, TABLE);
                harnessCommits.incrementAndGet();
                try (var resultSet = statement.executeQuery()) {
                    settled = resultSet.next() && resultSet.getLong(1) >= inserts && resultSet.getLong(2) >= deletes;
                }
            }
            if (!settled) {
                Thread.sleep(250L);
            }
        }
        if (!settled) {
            throw new IllegalStateException("Table statistics for " + TABLE + " never caught up with " + messages + " inserts and deletes");
        }
        // Idle backends flush pending counters on a timer; wait until xact_commit only moves by our own probe
        var previous = xactCommit();
        for (var attempt = 0; attempt < 15; attempt++) {
            Thread.sleep(1_100L);
            var current = xactCommit();
            if (current - previous <= 1) {
                return;
            }
            previous = current;
        }
        log.warn("xact_commit did not settle within 16 s; commits per message may be slightly low");
    }

    private long xactCommit() throws SQLException {
        harnessCommits.incrementAndGet();
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /**
     * Option 3 of step 1: a single thread that sends the notifications for enqueues that have already committed.
     * Producers add the queue name to a shared set and signal; the thread drains the set and, when it is non-empty,
     * sends one autocommit statement notifying every drained queue name, then waits for the next signal. The payload
     * mirrors the statement-per-queue arm's ({@code added_ts} is required by {@code DefaultQueuedMessage}).
     */
    private final class CoalescingNotifier implements AutoCloseable {
        private final Connection    connection;
        private final String        sql;
        private final Set<String>   pending         = ConcurrentHashMap.newKeySet();
        private final Semaphore     signal          = new Semaphore(0);
        private final AtomicBoolean running         = new AtomicBoolean(true);
        private final AtomicLong    statements      = new AtomicLong();
        private final AtomicLong    queueNamesSent  = new AtomicLong();
        private final Thread        thread;
        private       boolean       closed;

        CoalescingNotifier(String channel) throws SQLException {
            connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            connection.setAutoCommit(true);
            sql = "SELECT pg_notify('" + channel + "', json_build_object('table_name', '" + TABLE + "', 'sql_operation', 'INSERT', " +
                  "'queue_name', q, 'added_ts', now())::text) FROM unnest(?::text[]) q";
            thread = Thread.ofPlatform().name("coalescing-notifier").start(this::loop);
        }

        void queued(QueueName queueName) {
            pending.add(queueName.toString());
            signal.release();
        }

        private void loop() {
            try (var statement = connection.prepareStatement(sql)) {
                while (true) {
                    var stopping = !running.get();
                    if (!stopping) {
                        signal.tryAcquire(100, TimeUnit.MILLISECONDS);
                    }
                    signal.drainPermits();
                    var drained = new ArrayList<String>();
                    for (var iterator = pending.iterator(); iterator.hasNext(); ) {
                        drained.add(iterator.next());
                        iterator.remove();
                    }
                    if (!drained.isEmpty()) {
                        statement.setArray(1, connection.createArrayOf("text", drained.toArray()));
                        try (var ignored = statement.executeQuery()) {
                            statements.incrementAndGet();
                            queueNamesSent.addAndGet(drained.size());
                        }
                    }
                    if (stopping) {
                        return;
                    }
                }
            } catch (Exception e) {
                log.error("Coalescing notifier failed", e);
            }
        }

        long statements() {
            return statements.get();
        }

        long queueNamesSent() {
            return queueNamesSent.get();
        }

        @Override
        public void close() throws Exception {
            if (closed) {
                return;
            }
            closed = true;
            running.set(false);
            signal.release();
            thread.join(5_000);
            connection.close();
        }
    }

    /** Counts notifications on the queue table's channel on a connection of its own. */
    private final class NotificationCounter implements AutoCloseable {
        private final Connection    connection;
        private final AtomicLong    count   = new AtomicLong();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread        thread;

        NotificationCounter(String channel) throws SQLException {
            connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            try (var statement = connection.createStatement()) {
                statement.execute("LISTEN " + channel);
            }
            var pg = connection.unwrap(PGConnection.class);
            thread = Thread.ofPlatform().name("notification-counter").start(() -> {
                while (running.get()) {
                    try {
                        var received = pg.getNotifications(100);
                        if (received != null) {
                            count.addAndGet(received.length);
                        }
                    } catch (SQLException e) {
                        if (running.get()) {
                            log.error("Notification counter failed", e);
                        }
                        return;
                    }
                }
            });
        }

        long count() {
            return count.get();
        }

        void stop() throws InterruptedException {
            running.set(false);
            thread.join(2_000);
        }

        @Override
        public void close() throws Exception {
            stop();
            connection.close();
        }
    }

    /** Samples {@code pg_stat_activity} for active client backends (excluding itself) every {@value #SAMPLE_INTERVAL_MS} ms. */
    private final class WaitEventSampler implements AutoCloseable {
        private final Connection                connection;
        private final Map<String, Long>         counts  = new ConcurrentHashMap<>();
        private final AtomicLong                active  = new AtomicLong();
        private final AtomicLong                ticks   = new AtomicLong();
        private final AtomicBoolean             running = new AtomicBoolean(true);
        private final Thread                    thread;

        WaitEventSampler() throws SQLException {
            connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            thread = Thread.ofPlatform().name("wait-event-sampler").start(() -> {
                try (var statement = connection.prepareStatement(
                        "SELECT coalesce(wait_event_type, 'CPU'), coalesce(wait_event, '') FROM pg_stat_activity " +
                        "WHERE state = 'active' AND pid <> pg_backend_pid() AND datname = current_database() AND backend_type = 'client backend'")) {
                    while (running.get()) {
                        var startNanos = System.nanoTime();
                        harnessCommits.incrementAndGet();
                        ticks.incrementAndGet();
                        try (var resultSet = statement.executeQuery()) {
                            while (resultSet.next()) {
                                active.incrementAndGet();
                                counts.merge(resultSet.getString(1) + "/" + resultSet.getString(2), 1L, Long::sum);
                            }
                        }
                        var sleepMillis = SAMPLE_INTERVAL_MS - (System.nanoTime() - startNanos) / 1_000_000L;
                        if (sleepMillis > 0) {
                            Thread.sleep(sleepMillis);
                        }
                    }
                } catch (Exception e) {
                    if (running.get()) {
                        log.error("Wait event sampler failed", e);
                    }
                }
            });
        }

        void stop() throws InterruptedException {
            running.set(false);
            thread.join(2_000);
        }

        long activeSamples() {
            return active.get();
        }

        long ticks() {
            return ticks.get();
        }

        Map<String, Long> counts() {
            return new TreeMap<>(counts);
        }

        double share(java.util.function.Predicate<String> key) {
            var total = active.get();
            if (total == 0) {
                return 0.0d;
            }
            return counts.entrySet().stream().filter(entry -> key.test(entry.getKey())).mapToLong(Map.Entry::getValue).sum() / (double) total;
        }

        @Override
        public void close() throws Exception {
            stop();
            connection.close();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Test 2 - stuck-message reset query
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void stuck_message_reset_query_cost_with_and_without_partial_index() throws Exception {
        execute("DROP TABLE IF EXISTS " + RESET_TABLE + " CASCADE");
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(jdbi))
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .setSharedQueueTableName(RESET_TABLE)
                                                   .setCreateMultiTableChangeListener(false)
                                                   .build();
        durableQueues.start();
        durableQueues.stop();

        var columns = "id, queue_name, message_payload, message_payload_type, added_ts, next_delivery_ts, delivery_ts, " +
                      "is_being_delivered, is_dead_letter_message, delivery_mode";
        var payload = "jsonb_build_object('payload', substr(repeat(md5(g::text) || md5((g * 7)::text), 4), 1, 190))";
        // 100 000 ready
        execute("INSERT INTO " + RESET_TABLE + " (" + columns + ") SELECT 'r' || g, 'q' || (g % 3), " + payload +
                ", 'BenchPayload', now() - interval '10 minutes', now() - (g % 600) * interval '1 second', NULL, FALSE, FALSE, 'NORMAL' " +
                "FROM generate_series(1, 100000) g");
        // 2 000 dead letters
        execute("INSERT INTO " + RESET_TABLE + " (" + columns + ") SELECT 'd' || g, 'q' || (g % 3), " + payload +
                ", 'BenchPayload', now() - interval '1 day', now() - interval '1 day', now() - interval '1 day', FALSE, TRUE, 'NORMAL' " +
                "FROM generate_series(1, 2000) g");
        // 200 stuck being delivered
        execute("INSERT INTO " + RESET_TABLE + " (" + columns + ") SELECT 's' || g, 'q' || (g % 3), " + payload +
                ", 'BenchPayload', now() - interval '1 hour', now() - interval '1 hour', now() - interval '1 hour', TRUE, FALSE, 'NORMAL' " +
                "FROM generate_series(1, 200) g");
        execute("VACUUM ANALYZE " + RESET_TABLE);

        var sql = new DurableQueuesSql(RESET_TABLE).getResetMessagesStuckBeingDeliveredAcrossMultipleQueuesSql()
                                                   .replace(":now", "now()")
                                                   .replace(":error", "'Handler Processing of the Message was determined to have Timed Out'")
                                                   .replace(":threshold", "now() - interval '30 seconds'")
                                                   .replace("<queueNames>", "'q0', 'q1', 'q2'");
        log.info("Reset SQL under test:\n{}", sql);

        var output = new LinkedHashMap<String, Object>();
        output.put("rows", Map.of("ready", 100_000, "deadLetters", 2_000, "stuck", 200));
        output.put("tableSizeBytes", scalar("SELECT pg_total_relation_size('" + RESET_TABLE + "')"));
        output.put("sql", sql);
        var lines = new ArrayList<String>();
        for (var withIndex : List.of(false, true)) {
            if (withIndex) {
                execute("CREATE INDEX idx_" + RESET_TABLE + "_stuck ON " + RESET_TABLE + " (delivery_ts) WHERE is_being_delivered");
                execute("ANALYZE " + RESET_TABLE);
                output.put("indexSizeBytes", scalar("SELECT pg_relation_size('idx_" + RESET_TABLE + "_stuck')"));
            }
            var runs = new ArrayList<Map<String, Object>>();
            for (var run = 0; run < 5; run++) {
                // Each rolled-back run leaves 200 dead row versions behind; vacuum so every run sees the same table
                execute("VACUUM " + RESET_TABLE);
                runs.add(explain(sql));
            }
            var times = runs.stream().mapToDouble(r -> (Double) r.get("executionMs")).sorted().toArray();
            var median = times[times.length / 2];
            var label = withIndex ? "with (delivery_ts) WHERE is_being_delivered" : "without index";
            output.put(withIndex ? "withIndex" : "withoutIndex", Map.of("medianExecutionMs", median, "runs", runs));
            for (var r : runs) {
                lines.add(String.format("%-44s %-48s rows=%-4s scanHit=%-6s scanRead=%-6s totalHit=%-6s totalRead=%-6s dirtied=%-5s exec=%.3f ms",
                                        label, r.get("scanNode"), r.get("rowsUpdated"), r.get("scanSharedHit"), r.get("scanSharedRead"), r.get("sharedHit"), r.get("sharedRead"),
                                        r.get("sharedDirtied"), r.get("executionMs")));
            }
            lines.add(String.format("%-44s median execution %.3f ms", label, median));
        }
        RunResult.writeAll("target/perf-lab-baseline/classic-queue-reset-query.json", output);
        log.info("\n======== STUCK-MESSAGE RESET QUERY (100 000 ready + 2 000 DLQ + 200 stuck, 3 queues) ========\n{}\n" +
                 "table {} bytes, partial index {} bytes",
                 String.join("\n", lines), output.get("tableSizeBytes"), output.get("indexSizeBytes"));
    }

    private Map<String, Object> explain(String sql) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)) {
                resultSet.next();
                var root = JSON.readTree(resultSet.getString(1)).get(0);
                var plan = root.get("Plan");
                var scan = plan.has("Plans") ? plan.get("Plans").get(0) : plan;
                var scanDescription = scan.get("Node Type").asString() + (scan.has("Index Name") ? " " + scan.get("Index Name").asString() : "");
                var result = new LinkedHashMap<String, Object>();
                result.put("topNode", plan.get("Node Type").asString());
                result.put("scanNode", scanDescription);
                result.put("rowsUpdated", scan.get("Actual Rows").asLong());
                result.put("sharedHit", plan.get("Shared Hit Blocks").asLong());
                result.put("sharedRead", plan.get("Shared Read Blocks").asLong());
                result.put("sharedDirtied", plan.get("Shared Dirtied Blocks").asLong());
                result.put("scanSharedHit", scan.get("Shared Hit Blocks").asLong());
                result.put("scanSharedRead", scan.get("Shared Read Blocks").asLong());
                result.put("planningMs", root.get("Planning Time").asDouble());
                result.put("executionMs", root.get("Execution Time").asDouble());
                result.put("plan", JSON.writeValueAsString(plan));
                return result;
            } finally {
                connection.rollback();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long scalar(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private long rowCount(String table) throws SQLException {
        harnessCommits.incrementAndGet();
        return scalar("SELECT count(*) FROM " + table);
    }

    private static String incompressibleText(int size) {
        var raw = new byte[size];
        new Random(size).nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw).substring(0, size);
    }

    public record BenchPayload(String payload) {
    }
}
