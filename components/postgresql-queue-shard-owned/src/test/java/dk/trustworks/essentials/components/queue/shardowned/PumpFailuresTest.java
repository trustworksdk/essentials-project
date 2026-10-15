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

import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A delivery pass that keeps failing is retried {@link ShardPump#PUMP_RETRY_NANOS} later with the shard's lease kept. Every failure must
 * be counted where {@code ShardOwnedQueue.metrics()} sees it, and logged at ERROR once a minute at most per queue: not
 * once per pass, and not once per shard.
 */
class PumpFailuresTest {

    private final AtomicLong                    clock = new AtomicLong(1_000_000_000L);
    private       ListAppender<ILoggingEvent>   logged;
    private       ch.qos.logback.classic.Logger pumpLogger;

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
    void a_failing_pass_is_counted_every_time_and_logged_at_error_once_a_minute() {
        var metrics  = new ShardOwnerMetrics();
        var owner    = new StubOwner((short) 3, 5, metrics);
        var failures = new PumpFailures("pump-0", clock::get);

        failures.failed(owner, "issued a statement that failed on a healthy connection", new SQLException("refused"));
        assertThat(metrics.pumpFailures.sum()).isEqualTo(1);
        assertThat(metrics.snapshot()).containsEntry("pumpFailures", 1L);
        assertThat(levels()).containsExactly(Level.ERROR);
        assertThat(logged.list.getFirst().getFormattedMessage())
                .contains("pump-0: queue 3, unordered shard 5 issued a statement that failed on a healthy connection")
                .contains("1 failed pass(es) in a row")
                .contains("its pass is tried again " + TimeUnit.NANOSECONDS.toMillis(ShardPump.PUMP_RETRY_NANOS) + " ms later")
                .contains("QueueHealth.unownedShards does not count it")
                .contains("ShardOwnedQueue.metrics() counts every failure under pumpFailures");
        assertThat(logged.list.getFirst().getThrowableProxy()).as("the first carries the stack trace").isNotNull();

        // Retried PUMP_RETRY_NANOS apart: counted, at DEBUG, no stack trace
        for (var pass = 0; pass < 5; pass++) {
            clock.addAndGet(200_000_000L);
            failures.failed(owner, "issued a statement that failed on a healthy connection", new SQLException("refused"));
        }
        assertThat(metrics.pumpFailures.sum()).isEqualTo(6);
        assertThat(levels()).containsExactly(Level.ERROR, Level.DEBUG, Level.DEBUG, Level.DEBUG, Level.DEBUG, Level.DEBUG);
        assertThat(logged.list.getLast().getThrowableProxy()).isNull();
        assertThat(logged.list.getLast().getFormattedMessage()).contains("(failed pass 6 in a row): java.sql.SQLException: refused");

        // A minute after the first ERROR: one more, with the stack trace and the run so far
        clock.set(1_000_000_000L + ErrorWindow.EVERY_NANOS);
        failures.failed(owner, "threw during delivery", new IllegalStateException("broken"));
        assertThat(levels().getLast()).isEqualTo(Level.ERROR);
        assertThat(logged.list.getLast().getFormattedMessage())
                .contains("queue 3, unordered shard 5 threw during delivery")
                .contains("7 failed pass(es) in a row over 60 s");
        assertThat(logged.list.getLast().getThrowableProxy()).isNotNull();

        // And back to DEBUG
        clock.addAndGet(200_000_000L);
        failures.failed(owner, "threw during delivery", new IllegalStateException("broken"));
        assertThat(levels().getLast()).isEqualTo(Level.DEBUG);

        failures.succeeded(owner);
        assertThat(levels().getLast()).isEqualTo(Level.INFO);
        assertThat(logged.list.getLast().getFormattedMessage()).contains("queue 3, unordered shard 5 is delivering again after 8 failed pass(es) in 60 s");
        assertThat(metrics.pumpFailures.sum()).as("a running total").isEqualTo(8);

        // A shard that never failed, or no longer does, logs nothing on success
        var before = logged.list.size();
        failures.succeeded(owner);
        failures.succeeded(new StubOwner((short) 3, 6, metrics));
        assertThat(logged.list).hasSize(before);
    }

    /**
     * What makes a pass fail is rarely one shard's: a revoked grant or a broken trigger fails every shard of the queue, on
     * every pump serving it. One ERROR a minute for the queue, not one per shard.
     */
    @Test
    void shards_failing_together_are_logged_at_error_once_a_minute_per_queue() {
        var metrics = new ShardOwnerMetrics();
        var other   = new ShardOwnerMetrics();
        var pumpZero = new PumpFailures("pump-0", clock::get);
        var pumpOne  = new PumpFailures("pump-1", clock::get);
        var first    = new StubOwner((short) 4, 0, metrics);
        var second   = new StubOwner((short) 4, 1, metrics);
        var third    = new StubOwner((short) 4, 2, metrics);
        var elsewhere = new StubOwner((short) 5, 0, other);

        pumpZero.failed(first, "threw during delivery", new IllegalStateException("broken"));
        pumpZero.failed(second, "threw during delivery", new IllegalStateException("broken"));
        pumpOne.failed(third, "threw during delivery", new IllegalStateException("broken"));
        pumpOne.failed(elsewhere, "threw during delivery", new IllegalStateException("broken"));
        assertThat(levels()).as("one ERROR per queue").containsExactly(Level.ERROR, Level.DEBUG, Level.DEBUG, Level.ERROR);
        assertThat(metrics.pumpFailures.sum()).isEqualTo(3);
        assertThat(other.pumpFailures.sum()).isEqualTo(1);
    }

    @Test
    void a_shard_whose_lease_ended_starts_a_new_run_if_it_fails_again() {
        var metrics  = new ShardOwnerMetrics();
        var owner    = new StubOwner((short) 1, 0, metrics);
        var failures = new PumpFailures("pump-0", clock::get);
        failures.failed(owner, "threw during delivery", new IllegalStateException("broken"));
        failures.failed(owner, "threw during delivery", new IllegalStateException("broken"));

        failures.forget(owner);
        failures.succeeded(owner);
        assertThat(levels()).as("forgotten, so no recovery is reported").containsExactly(Level.ERROR, Level.DEBUG);

        failures.failed(owner, "threw during delivery", new IllegalStateException("broken"));
        assertThat(logged.list.getLast().getFormattedMessage()).contains("(failed pass 1 in a row)");
    }

    /**
     * The ordered lane's batched read is retried on every pass too, by every pump serving the queue. One WARN a minute per
     * queue, every failure counted, and an INFO once it succeeds again.
     */
    @Test
    void a_failing_batched_read_is_counted_every_time_and_logged_at_warn_once_a_minute_per_queue() {
        var metrics  = new ShardOwnerMetrics();
        var other    = new ShardOwnerMetrics();
        var pumpZero = new PumpFailures("pump-0", clock::get);
        var pumpOne  = new PumpFailures("pump-1", clock::get);

        pumpZero.batchedReadFailed((short) 6, metrics, new SQLException("permission denied"));
        assertThat(levels()).containsExactly(Level.WARN);
        assertThat(logged.list.getFirst().getFormattedMessage())
                .contains("pump-0: batched read for queue 6 failed; those shards read for themselves this pass")
                .contains("1 failed batched read(s) in a row")
                .contains("batchedReadFailures");
        assertThat(logged.list.getFirst().getThrowableProxy()).as("the first carries the stack trace").isNotNull();

        clock.addAndGet(200_000_000L);
        pumpZero.batchedReadFailed((short) 6, metrics, new SQLException("permission denied"));
        pumpOne.batchedReadFailed((short) 6, metrics, new SQLException("permission denied"));
        pumpOne.batchedReadFailed((short) 7, other, new SQLException("permission denied"));
        assertThat(levels()).as("one WARN per queue, across pumps").containsExactly(Level.WARN, Level.DEBUG, Level.DEBUG, Level.WARN);
        assertThat(logged.list.get(1).getThrowableProxy()).isNull();
        assertThat(logged.list.get(1).getFormattedMessage()).contains("(failure 2 in a row)").contains("java.sql.SQLException: permission denied");
        assertThat(metrics.batchedReadFailures.sum()).isEqualTo(3);
        assertThat(metrics.snapshot()).containsEntry("batchedReadFailures", 3L);
        assertThat(other.batchedReadFailures.sum()).isEqualTo(1);
        assertThat(metrics.pumpFailures.sum()).as("not a failed delivery pass").isZero();

        // A minute after the queue's WARN: one more, with the stack trace
        clock.set(1_000_000_000L + ErrorWindow.EVERY_NANOS);
        pumpZero.batchedReadFailed((short) 6, metrics, new SQLException("permission denied"));
        assertThat(levels().getLast()).isEqualTo(Level.WARN);
        assertThat(logged.list.getLast().getFormattedMessage()).contains("3 failed batched read(s) in a row over 60 s");
        assertThat(logged.list.getLast().getThrowableProxy()).isNotNull();

        pumpZero.batchedReadSucceeded((short) 6);
        assertThat(levels().getLast()).isEqualTo(Level.INFO);
        assertThat(logged.list.getLast().getFormattedMessage()).contains("pump-0: batched read for queue 6 succeeds again after 3 failure(s) in 60 s");

        // Not failing on this pump (any more): nothing to report
        var before = logged.list.size();
        pumpZero.batchedReadSucceeded((short) 6);
        pumpZero.batchedReadSucceeded((short) 7);
        assertThat(logged.list).hasSize(before);
    }

    private List<Level> levels() {
        return logged.list.stream().map(ILoggingEvent::getLevel).toList();
    }

    private record StubOwner(short queueId, int shard, ShardOwnerMetrics ownerMetrics) implements LeasedOwner {
        @Override
        public int pumpOnce(Connection connection) {
            return 0;
        }

        @Override
        public boolean needsAttention() {
            return false;
        }

        @Override
        public void onTakeover(Connection connection) {
        }

        @Override
        public void flushOnStop(Connection connection) {
        }

        @Override
        public long parkDeadlineMillis() {
            return Long.MAX_VALUE;
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
}
