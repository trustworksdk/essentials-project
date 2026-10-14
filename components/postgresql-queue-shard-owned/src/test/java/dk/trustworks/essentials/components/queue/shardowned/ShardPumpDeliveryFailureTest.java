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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A delivery pass ({@link LeasedOwner#pumpOnce}) that keeps failing on a healthy connection is retried on the pump's next
 * pass with the lease kept, so the shard is held and delivers nothing, and {@code QueueHealth.unownedShards} cannot see
 * it. It used to log a full stack trace at ERROR on every pass - five a second on the statement path, paced by the 200 ms
 * sleep - and count nothing. Now every failure is counted in {@link ShardOwnerMetrics#pumpFailures} on the owner's
 * metrics, and logged at ERROR once a minute per queue at most, with DEBUG for the rest.
 */
class ShardPumpDeliveryFailureTest {
    private static final short PUMP_QUEUE_ID = 0;

    private ListAppender<ILoggingEvent>   logged;
    private ch.qos.logback.classic.Logger pumpLogger;

    @BeforeEach
    void captureLog() {
        logged = new ListAppender<>();
        logged.start();
        pumpLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardPump.class);
        pumpLogger.setLevel(Level.DEBUG);
        pumpLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        pumpLogger.detachAppender(logged);
        pumpLogger.setLevel(null);
    }

    @Test
    void a_pass_that_keeps_throwing_is_counted_and_logged_at_error_once_not_on_every_pass() throws InterruptedException {
        var metrics = new ShardOwnerMetrics();
        var owner   = new StubOwner((short) 7, 1, metrics, () -> new IllegalStateException("pass refused"), Integer.MAX_VALUE);

        runUntil(() -> owner.passes.get() >= 5, owner);

        assertThat(metrics.pumpFailures.sum()).as("every failed pass, on the owner's metrics").isEqualTo(owner.passes.get());
        var aboutShard = aboutShard(1);
        assertThat(aboutShard.stream().map(ILoggingEvent::getLevel).toList())
                .as("one ERROR, every repeat at DEBUG")
                .startsWith(Level.ERROR)
                .containsOnlyOnce(Level.ERROR)
                .hasSize(owner.passes.get());
        assertThat(aboutShard.getFirst().getFormattedMessage())
                .contains("pump-0: queue 7, unordered shard 1 threw during delivery")
                .contains("pumpFailures");
        assertThat(aboutShard.getFirst().getThrowableProxy()).as("the first carries the stack trace").isNotNull();
        assertThat(aboutShard.getLast().getThrowableProxy()).as("a repeat carries none").isNull();
        assertThat(aboutShard.getLast().getFormattedMessage()).contains("java.lang.IllegalStateException: pass refused");
    }

    @Test
    void a_statement_that_keeps_failing_on_a_healthy_connection_is_counted_and_logged_at_error_once() throws InterruptedException {
        var metrics = new ShardOwnerMetrics();
        var owner   = new StubOwner((short) 7, 2, metrics, () -> new SQLException("permission denied"), Integer.MAX_VALUE);

        runUntil(() -> owner.passes.get() >= 3, owner);

        assertThat(metrics.pumpFailures.sum()).isEqualTo(owner.passes.get());
        var aboutShard = aboutShard(2);
        assertThat(aboutShard.stream().map(ILoggingEvent::getLevel).toList())
                .startsWith(Level.ERROR)
                .containsOnlyOnce(Level.ERROR)
                .hasSize(owner.passes.get());
        assertThat(aboutShard.getFirst().getFormattedMessage())
                .contains("pump-0: queue 7, unordered shard 2 issued a statement that failed on a healthy connection");
        assertThat(aboutShard.getLast().getFormattedMessage()).contains("java.sql.SQLException: permission denied");
    }

    @Test
    void a_shard_whose_pass_succeeds_again_says_so_once() throws InterruptedException {
        var metrics = new ShardOwnerMetrics();
        var owner   = new StubOwner((short) 7, 3, metrics, () -> new IllegalStateException("transient"), 3);

        runUntil(() -> owner.passes.get() >= 6, owner);

        assertThat(metrics.pumpFailures.sum()).isEqualTo(3);
        var aboutShard = aboutShard(3);
        assertThat(aboutShard.stream().map(ILoggingEvent::getLevel).toList())
                .containsExactly(Level.ERROR, Level.DEBUG, Level.DEBUG, Level.INFO);
        assertThat(aboutShard.getLast().getFormattedMessage()).contains("delivering again after 3 failed pass(es)");
    }

    private void runUntil(Callable<Boolean> done, LeasedOwner... owners) throws InterruptedException {
        var running = new AtomicBoolean(true);
        var pump = new ShardPump(new ShardOwnedStorage(healthyDataSource(), PUMP_QUEUE_ID), ShardOwnerSettings.defaults(),
                                 new ShardOwnerMetrics(), running, new AtomicBoolean(false), "pump-0", Optional::empty);
        for (var owner : owners) {
            pump.add(owner);
        }
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            await().atMost(Duration.ofSeconds(10)).until(done);
        } finally {
            running.set(false);
            pump.wakeup().signal();
            assertThat(thread.join(Duration.ofSeconds(10))).as("the pump stops when running is cleared").isTrue();
        }
    }

    /**
     * The ordered lane reads once per queue for every attentive owner (two or more) before their own passes. When the
     * database refuses that read, the owners read for themselves, so the pass goes on - but the read is attempted again on
     * every pass, and its failure used to be logged at WARN with a full stack trace each time and counted nowhere.
     */
    @Test
    void a_batched_read_that_keeps_failing_is_counted_and_logged_at_warn_once_not_on_every_pass() throws InterruptedException {
        var metrics = new ShardOwnerMetrics();
        var first   = new BatchStubOwner((short) 9, 0, metrics);
        var second  = new BatchStubOwner((short) 9, 1, metrics);

        runUntil(() -> first.passes.get() >= 5, first, second);

        assertThat(first.batchesApplied.get() + second.batchesApplied.get()).as("the batched read never succeeded").isZero();
        assertThat(metrics.pumpFailures.sum()).as("the shards' own passes succeed").isZero();
        assertThat(metrics.batchedReadFailures.sum()).as("one batched read per pass, each failing").isEqualTo(first.passes.get());
        var aboutBatch = logged.list.stream()
                                    .filter(event -> event.getFormattedMessage().contains("batched read for queue 9"))
                                    .toList();
        assertThat(aboutBatch.stream().map(ILoggingEvent::getLevel).toList())
                .as("one WARN, every repeat at DEBUG")
                .startsWith(Level.WARN)
                .containsOnlyOnce(Level.WARN)
                .hasSize(first.passes.get());
        assertThat(aboutBatch.getFirst().getFormattedMessage())
                .contains("pump-0: batched read for queue 9 failed")
                .contains("batchedReadFailures");
        assertThat(aboutBatch.getFirst().getThrowableProxy()).as("the first carries the stack trace").isNotNull();
        assertThat(aboutBatch.getLast().getThrowableProxy()).as("a repeat carries none").isNull();
        assertThat(aboutBatch.getLast().getFormattedMessage()).contains("java.sql.SQLException: permission denied");
    }

    private List<ILoggingEvent> aboutShard(int shard) {
        return logged.list.stream()
                          .filter(event -> event.getFormattedMessage().contains("shard " + shard + " "))
                          .toList();
    }

    /**
     * Hands out connections that are valid and refuse every statement, as a revoked grant would: the stub owners issue
     * none, so only the pump's own batched read meets it.
     */
    private static DataSource healthyDataSource() {
        var loader = ShardPumpDeliveryFailureTest.class.getClassLoader();
        var connection = (Connection) Proxy.newProxyInstance(loader, new Class<?>[]{Connection.class},
                                                             (proxy, method, args) -> switch (method.getName()) {
                                                                 case "isValid" -> true;
                                                                 case "prepareStatement" -> throw new SQLException("permission denied");
                                                                 case "close" -> null;
                                                                 case "isClosed" -> false;
                                                                 case "hashCode" -> System.identityHashCode(proxy);
                                                                 case "equals" -> proxy == args[0];
                                                                 case "toString" -> "stub connection";
                                                                 default -> throw new UnsupportedOperationException(method.getName());
                                                             });
        return (DataSource) Proxy.newProxyInstance(loader, new Class<?>[]{DataSource.class},
                                                   (proxy, method, args) -> switch (method.getName()) {
                                                       case "getConnection" -> connection;
                                                       case "hashCode" -> System.identityHashCode(proxy);
                                                       case "equals" -> proxy == args[0];
                                                       case "toString" -> "stub data source";
                                                       default -> throw new UnsupportedOperationException(method.getName());
                                                   });
    }

    /**
     * Wants attention on every pass, and fails its first {@code failingPasses} passes with what {@code failure} supplies.
     */
    private static class StubOwner implements LeasedOwner {
        final         AtomicInteger              passes = new AtomicInteger();
        private final short                      queueId;
        private final int                        shard;
        private final ShardOwnerMetrics          metrics;
        private final Supplier<Exception> failure;
        private final int                        failingPasses;

        StubOwner(short queueId, int shard, ShardOwnerMetrics metrics, Supplier<Exception> failure, int failingPasses) {
            this.queueId = queueId;
            this.shard = shard;
            this.metrics = metrics;
            this.failure = failure;
            this.failingPasses = failingPasses;
        }

        @Override
        public int pumpOnce(Connection connection) throws SQLException {
            if (passes.incrementAndGet() > failingPasses) {
                return 0;
            }
            var e = failure.get();
            if (e instanceof SQLException sql) {
                throw sql;
            }
            throw (RuntimeException) e;
        }

        @Override
        public boolean needsAttention() {
            return true;
        }

        @Override
        public void onTakeover(Connection connection) {
        }

        @Override
        public void flushOnStop(Connection connection) {
        }

        @Override
        public long parkDeadlineMillis() {
            // A short park, so the throwing case passes several times a second as a real owner with a retry due would
            return 20L;
        }

        @Override
        public int shard() {
            return shard;
        }

        @Override
        public short queueId() {
            return queueId;
        }

        @Override
        public ShardOwnerMetrics ownerMetrics() {
            return metrics;
        }

        @Override
        public long fence() {
            return 1;
        }

        @Override
        public String lane() {
            return "unordered";
        }

        @Override
        public boolean leaseHeld() {
            return true;
        }

        @Override
        public void beginDraining() {
        }

        @Override
        public int inFlight() {
            return 0;
        }

        @Override
        public void onLeaseEnded(LeaseEnd reason) {
        }
    }

    /**
     * An ordered-lane owner whose own passes succeed, so it is read for in batch whenever a sibling of its queue is
     * attentive in the same pass.
     */
    private static final class BatchStubOwner extends StubOwner implements BatchReadableOwner {
        final AtomicInteger batchesApplied = new AtomicInteger();

        BatchStubOwner(short queueId, int shard, ShardOwnerMetrics metrics) {
            super(queueId, shard, metrics, () -> new IllegalStateException("never thrown"), 0);
        }

        @Override
        public String lane() {
            return "ordered";
        }

        @Override
        public long batchReadCursor() {
            return 0;
        }

        @Override
        public boolean batchSweepDue() {
            return false;
        }

        @Override
        public void applyBatchRead(List<ShardOwnedStorage.OrderedRow> cursorRows, List<ShardOwnedStorage.OrderedRow> sweptRows,
                                   OptionalLong nextVisibleMillis) {
            batchesApplied.incrementAndGet();
        }
    }
}
