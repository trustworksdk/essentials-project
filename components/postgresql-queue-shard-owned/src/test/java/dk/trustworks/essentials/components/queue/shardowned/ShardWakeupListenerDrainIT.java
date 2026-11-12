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

package dk.trustworks.essentials.components.queue.shardowned;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.PGStream;
import org.postgresql.core.QueryExecutorBase;
import org.slf4j.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does Tier 1 ({@link ShardWakeupListener}) wake owners late under sustained notification traffic?
 * <p>
 * pgjdbc's {@code PGConnection.getNotifications(timeout)} ({@code QueryExecutorImpl.processNotifies(int)}, 42.7.14)
 * blocks for the first message only; after each notification it keeps reading for as long as
 * {@code PGStream.hasMessagePending()} finds another one, which peeks the socket with a 1 ms timeout. Nothing it has
 * read is handed back until a peek comes up empty, so a stream whose notifications arrive less than about a
 * millisecond apart is held inside the driver for as long as the stream stays that dense.
 * <p>
 * Shape: a producing runtime that serves nothing, so every wake-up leaves through its batched
 * {@link ShardWakeupNotifier}, and a consuming runtime in the same JVM standing in for another process - its owners
 * hear of new rows only through its listener (or their backstop). Producers enqueue in their own transactions at a
 * steady rate; latency is from {@code enqueue} returning (commit) to the handler running. The listener's connection is
 * wrapped so every {@code getNotifications} call is timed and counted.
 * <p>
 * Three arms per rate: Tier 1 with the backstop and sweeps pushed to 10 s (so a late wake-up shows at full size),
 * Tier 1 at the default settings (500 ms backstop), and a control at the default settings whose listener reads and
 * discards every notification - the backstop-only path.
 * <p>
 * Three more arms try candidate fixes ({@link Arm}); findings in {@code docs/plans/pgjdbc-notification-drain-options.md}.
 * <p>
 * Benchmark-gated: {@code -Dbenchmark.run=true}.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class ShardWakeupListenerDrainIT {
    private static final Logger log = LoggerFactory.getLogger(ShardWakeupListenerDrainIT.class);

    private static final int      SHARD_COUNT      = 8;
    private static final int      PRODUCER_THREADS = 8;
    private static final Duration WARMUP           = Duration.ofSeconds(2);
    private static final Duration MEASURE          = Duration.ofSeconds(10);
    private static final Duration LONG_BACKSTOP    = Duration.ofSeconds(10);
    private static final int[]    RATES            = Arrays.stream(System.getProperty("drain.rates", "200,1000,3000").split(",")).mapToInt(Integer::parseInt).toArray();

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(40);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    /**
     * The first three arms are the original measurement (§3.17). The rest are candidate fixes, each at the 10 s backstop
     * so that a late wake-up shows at full size (compare against {@code TIER1_LONG_BACKSTOP}), each a test-only stand-in
     * plugged in through {@link ListenerTap} or the producer's {@link DataSource} - nothing in the engine changes:
     * <ul>
     *     <li>{@code PINNED_PEEK} - reflection: {@code PGStream.nextStreamAvailableCheckTime} is set to
     *         {@code Long.MAX_VALUE} once, so {@code hasMessagePending()} never peeks the socket again and only reports
     *         bytes already read or already in the kernel's receive buffer. {@code getNotifications(200)} unchanged.</li>
     *     <li>{@code QUERY_POLL} - no reflection, no {@code processNotifies}: every millisecond the listener runs a
     *         trivial statement (whose response carries every notification the backend has queued, ended by
     *         ReadyForQuery) and takes the notifications from {@code BaseConnection.getQueryExecutor()}.</li>
     *     <li>{@code PACED_NOTIFIER} - stock listener; the producing runtime's notifier is held to one batch per 2 ms,
     *         so each process's notifications arrive in bursts with a gap the driver's 1 ms peek sees.</li>
     * </ul>
     * Select with {@code -Ddrain.arms=PINNED_PEEK,...} (default: all).
     */
    enum Arm {
        TIER1_LONG_BACKSTOP, TIER1_DEFAULTS, BACKSTOP_ONLY, PINNED_PEEK, QUERY_POLL, PACED_NOTIFIER;

        boolean longBackstop() {
            return this != TIER1_DEFAULTS && this != BACKSTOP_ONLY;
        }
    }

    private static final List<Arm> ARMS = Arrays.stream(System.getProperty("drain.arms", Arrays.stream(Arm.values()).map(Enum::name).reduce((a, b) -> a + "," + b).orElseThrow())
                                                        .split(","))
                                                .map(String::trim).map(Arm::valueOf).toList();
    private static final long      PACE_NANOS = Duration.ofMillis(2).toNanos();

    @Test
    void measure_cross_runtime_wakeup_latency_under_sustained_notification_traffic() throws Exception {
        // Warm-up arm: JIT, pool growth, container caches
        run(Arm.TIER1_DEFAULTS, 500, "warmup");

        var results = new ArrayList<String>();
        for (var rate : RATES) {
            for (var arm : ARMS) {
                results.add(run(arm, rate, "r" + rate + "-" + arm.name().toLowerCase(Locale.ROOT)));
            }
        }
        log.info("");
        log.info("===== CROSS-RUNTIME WAKE-UP LATENCY (commit -> handler), {} producer threads, {} shards, {} s measured =====",
                 PRODUCER_THREADS, SHARD_COUNT, MEASURE.toSeconds());
        log.info(String.format("%-6s %-20s %8s %9s %9s %9s %9s | %9s %9s %8s %8s %10s %10s %10s | %8s %8s",
                               "rate", "arm", "msgs", "p50 ms", "p90 ms", "p99 ms", "max ms",
                               "gN calls", "non-empty", "notifs", "maxBatch", "call p50ms", "call p99ms", "call maxms",
                               "keysSent", "batches"));
        results.forEach(log::info);
        log.info("non-empty = getNotifications calls that returned notifications; call ms = how long those calls spent in the driver");
        log.info("=====");
    }

    private String run(Arm arm, int rate, String queueName) throws Exception {
        var name = QueueName.of("listener-drain-" + queueName);
        ShardOwnedSchema.registerQueue(dataSource, name, SHARD_COUNT);

        var settings = arm.longBackstop()
                       ? ShardOwnerSettings.builder()
                                           .setPollBackstop(LONG_BACKSTOP)
                                           .setSweepInterval(LONG_BACKSTOP)
                                           .setMaxSweepInterval(LONG_BACKSTOP)
                                           .build()
                       : ShardOwnerSettings.defaults();

        var tap            = new ListenerTap(arm == Arm.BACKSTOP_ONLY, arm);
        var producerSource = arm == Arm.PACED_NOTIFIER ? paced(dataSource) : dataSource;
        var consumerSource = tap.wrap(dataSource);

        var totalPlanned = (int) (rate * (WARMUP.toMillis() + MEASURE.toMillis()) / 1000);
        var firstMeasured = (int) (rate * WARMUP.toMillis() / 1000);
        var commitNanos  = new AtomicLongArray(totalPlanned);
        var handledNanos = new AtomicLongArray(totalPlanned);
        var handled      = new AtomicInteger();

        var closeables       = new ArrayList<AutoCloseable>();
        try {
            var consumingRuntime = new ShardRuntime(consumerSource, settings);
            closeables.add(consumingRuntime);
            var consumer = PostgresqlMessageQueue.builder().setDataSource(consumerSource).setQueueName(name)
                                                 .setInstanceId("consumer").setSettings(settings).setRuntime(consumingRuntime).build();
            closeables.add(consumer);
            var producingRuntime = new ShardRuntime(producerSource, settings);
            closeables.add(producingRuntime);
            var producer = PostgresqlMessageQueue.builder().setDataSource(producerSource).setQueueName(name)
                                                 .setInstanceId("producer").setSettings(settings).setRuntime(producingRuntime).build();
            closeables.add(producer);

            var subscription = consumer.consume((messageId, key, payload, payloadType) -> {
                var now = System.nanoTime();
                var seq = ByteBuffer.wrap(payload).getInt();
                if (handledNanos.compareAndSet(seq, 0, now)) {
                    handled.incrementAndGet();
                }
            }, ConsumerOptions.defaults());
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .until(() -> subscription.unorderedShardsHeld() == SHARD_COUNT);
            Thread.sleep(1_000);
            tap.reset();
            var keysBefore    = producingRuntime.notifier().keysSent();
            var batchesBefore = producingRuntime.notifier().batchesSent();

            var intervalNanos = 1_000_000_000L / rate;
            var start         = System.nanoTime() + 50_000_000L;
            var producers     = Executors.newFixedThreadPool(PRODUCER_THREADS);
            var failures      = new AtomicInteger();
            for (var t = 0; t < PRODUCER_THREADS; t++) {
                var thread = t;
                producers.submit(() -> {
                    for (var seq = thread; seq < totalPlanned; seq += PRODUCER_THREADS) {
                        var wait = start + seq * intervalNanos - System.nanoTime();
                        if (wait > 0) {
                            LockSupport.parkNanos(wait);
                        }
                        var payload = new byte[200];
                        ByteBuffer.wrap(payload).putInt(seq);
                        try {
                            producer.enqueue(List.of(Message.of(payload, 1)));
                        } catch (Exception e) {
                            failures.incrementAndGet();
                            continue;
                        }
                        commitNanos.set(seq, System.nanoTime());
                    }
                });
            }
            producers.shutdown();
            assertThat(producers.awaitTermination(WARMUP.plus(MEASURE).toSeconds() + 60, TimeUnit.SECONDS)).isTrue();
            assertThat(failures.get()).isZero();
            // Not asserted: an arm that cannot keep up is a result, reported as undelivered
            try {
                Awaitility.await().atMost(LONG_BACKSTOP.multipliedBy(6)).until(() -> handled.get() == totalPlanned);
            } catch (org.awaitility.core.ConditionTimeoutException e) {
                log.warn("{} at {}/s: {} of {} delivered", arm, rate, handled.get(), totalPlanned);
            }
            var undelivered = 0;

            var latencies = new long[totalPlanned - firstMeasured];
            for (var seq = firstMeasured; seq < totalPlanned; seq++) {
                if (handledNanos.get(seq) == 0) {
                    undelivered++;
                    latencies[seq - firstMeasured] = Long.MAX_VALUE;
                } else {
                    latencies[seq - firstMeasured] = Math.max(0, handledNanos.get(seq) - commitNanos.get(seq));
                }
            }
            Arrays.sort(latencies);
            latencies = Arrays.copyOf(latencies, latencies.length - undelivered);
            var calls = tap.nonEmptyCallNanos();
            Collections.sort(calls);
            var line = String.format("%-6d %-20s %8d undelivered=%-6d %9.2f %9.2f %9.2f %9.2f | %9d %9d %8d %8d %10.2f %10.2f %10.2f | %8d %8d",
                                     rate, arm, latencies.length, undelivered,
                                     pct(latencies, 0.50), pct(latencies, 0.90), pct(latencies, 0.99), latencies[latencies.length - 1] / 1e6,
                                     tap.calls.sum(), calls.size(), tap.notifications.sum(), tap.maxBatch.get(),
                                     pct(calls, 0.50), pct(calls, 0.99), calls.isEmpty() ? 0 : calls.getLast() / 1e6,
                                     producingRuntime.notifier().keysSent() - keysBefore,
                                     producingRuntime.notifier().batchesSent() - batchesBefore);
            log.info(line);
            return line;
        } finally {
            for (var closeable : closeables.reversed()) {
                closeable.close();
            }
        }
    }

    /**
     * Test-only pacing of {@link ShardWakeupNotifier}: its thread takes one connection per batch, so holding that
     * {@code getConnection} until 2 ms after the previous one holds the notifier to one batch per 2 ms, and keys queued in
     * the meantime coalesce into the next batch - what a pacing notifier would do.
     */
    private static DataSource paced(DataSource target) {
        var last = new AtomicLong(System.nanoTime() - PACE_NANOS);
        return (DataSource) Proxy.newProxyInstance(ShardWakeupListenerDrainIT.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            if (method.getName().equals("getConnection") && "shard-queue-notifier".equals(Thread.currentThread().getName())) {
                var wait = last.get() + PACE_NANOS - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                }
                last.set(System.nanoTime());
            }
            return ListenerTap.invoke(target, method, args);
        });
    }

    private static double pct(long[] sorted, double p) {
        return sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1, (int) (sorted.length * p))] / 1e6;
    }

    private static double pct(List<Long> sorted, double p) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * p))) / 1e6;
    }

    /**
     * Wraps the connection the listener thread takes (and only that one, recognised by its thread name), timing every
     * {@code getNotifications} call. With {@code blind} it reads notifications off the socket as usual but hands none to
     * the listener - Tier 1 off, with the server side and the socket behaving exactly as with it on.
     */
    static final class ListenerTap {
        final         LongAdder        calls         = new LongAdder();
        final         LongAdder        notifications = new LongAdder();
        final         AtomicInteger    maxBatch      = new AtomicInteger();
        private final Queue<Long>      nonEmpty      = new ConcurrentLinkedQueue<>();
        private final boolean          blind;
        private final Arm              arm;

        ListenerTap(boolean blind, Arm arm) {
            this.blind = blind;
            this.arm = arm;
        }

        void reset() {
            calls.reset();
            notifications.reset();
            maxBatch.set(0);
            nonEmpty.clear();
        }

        List<Long> nonEmptyCallNanos() {
            return new ArrayList<>(nonEmpty);
        }

        DataSource wrap(DataSource target) {
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                var result = invoke(target, method, args);
                if (method.getName().equals("getConnection") && "shard-queue-listener".equals(Thread.currentThread().getName())) {
                    return wrapConnection((Connection) result);
                }
                return result;
            });
        }

        private Connection wrapConnection(Connection target) {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("unwrap") && args[0] == PGConnection.class) {
                    return wrapPg(target, target.unwrap(PGConnection.class));
                }
                return invoke(target, method, args);
            });
        }

        private PGConnection wrapPg(Connection connection, PGConnection target) throws Exception {
            if (arm == Arm.PINNED_PEEK) {
                pinPeek(target);
            }
            var poll = arm == Arm.QUERY_POLL ? new QueryPoll(connection, target) : null;
            return (PGConnection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PGConnection.class}, (proxy, method, args) -> {
                if (!method.getName().equals("getNotifications") || args == null || args.length != 1) {
                    return invoke(target, method, args);
                }
                var began  = System.nanoTime();
                var result = poll != null ? poll.next((Integer) args[0]) : (PGNotification[]) invoke(target, method, args);
                var spent  = System.nanoTime() - began;
                calls.increment();
                if (result != null && result.length > 0) {
                    notifications.add(result.length);
                    maxBatch.accumulateAndGet(result.length, Math::max);
                    nonEmpty.add(spent);
                    if (blind) {
                        return new PGNotification[0];
                    }
                }
                return result;
            });
        }

        /**
         * The one reflective write the PINNED_PEEK candidate needs. {@code PGStream.hasMessagePending()} skips its 1 ms
         * socket peek while {@code now < nextStreamAvailableCheckTime}; pinned at {@code Long.MAX_VALUE} it never peeks
         * again and answers from {@code available()} alone (pgjdbc's buffer, then the socket's). The first message of a
         * {@code getNotifications(timeout)} call is still a blocking read with the timeout, so nothing waits on the peek.
         */
        static void pinPeek(PGConnection pg) throws Exception {
            var executor = ((BaseConnection) pg).getQueryExecutor();
            var streamField = QueryExecutorBase.class.getDeclaredField("pgStream");
            streamField.setAccessible(true);
            var stream = (PGStream) streamField.get(executor);
            var next = PGStream.class.getDeclaredField("nextStreamAvailableCheckTime");
            next.setAccessible(true);
            next.setLong(stream, Long.MAX_VALUE);
        }

        /**
         * The QUERY_POLL candidate: never enters {@code processNotifies}. A trivial statement's response is read up to
         * ReadyForQuery, and every NotificationResponse in front of it lands in the executor's list, which
         * {@code QueryExecutor.getNotifications()} hands over without touching the socket.
         */
        static final class QueryPoll {
            private final PreparedStatement statement;
            private final BaseConnection    base;

            QueryPoll(Connection connection, PGConnection pg) throws Exception {
                this.statement = connection.prepareStatement("SELECT 1");
                this.base = (BaseConnection) pg;
            }

            PGNotification[] next(int timeoutMillis) throws Exception {
                var deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
                while (true) {
                    statement.execute();
                    var notifications = base.getQueryExecutor().getNotifications();
                    if (notifications.length > 0 || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
                        return notifications;
                    }
                    LockSupport.parkNanos(1_000_000L);
                }
            }
        }

        static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
