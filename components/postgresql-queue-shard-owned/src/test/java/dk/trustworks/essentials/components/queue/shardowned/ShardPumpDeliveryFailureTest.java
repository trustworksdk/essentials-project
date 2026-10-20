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
 * A delivery pass ({@link LeasedOwner#pumpOnce}) that keeps failing on a healthy connection is retried
 * {@link ShardPump#PUMP_RETRY_NANOS} later with the lease kept, so the shard is held and delivers nothing, and
 * {@code QueueHealth.unownedShards} cannot see it. It used to log a full stack trace at ERROR on every pass and count
 * nothing. Now every failure is counted in {@link ShardOwnerMetrics#pumpFailures} on the owner's metrics, and logged at
 * ERROR once a minute per subscription at most, with DEBUG for the rest. Only the failing shard is paced; its siblings on the
 * same pump are not.
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

    /**
     * One shard's failing statement is that shard's problem, not its pump's. A failing pass used to pace the whole pump
     * with a 200 ms sleep after the pass, so every other shard on it - across every queue it serves - was held to five
     * passes a second for as long as the one shard kept failing. Now only the failing shard is paced: it is retried
     * {@link ShardPump#PUMP_RETRY_NANOS} later, and its sibling keeps being served at its full rate.
     */
    @Test
    void a_healthy_shard_keeps_delivering_while_a_sibling_statement_keeps_failing() throws InterruptedException {
        a_healthy_shard_keeps_delivering_while_a_sibling_fails_with(() -> new SQLException("permission denied"));
    }

    /**
     * The unchecked variant. It was never pass-paced, but nothing paced the failing shard either: it was retried on every
     * pass, as fast as its siblings kept the pump busy. It is now paced like a failing statement, per shard.
     */
    @Test
    void a_healthy_shard_keeps_delivering_while_a_sibling_pass_keeps_throwing() throws InterruptedException {
        a_healthy_shard_keeps_delivering_while_a_sibling_fails_with(() -> new IllegalStateException("pass refused"));
    }

    private void a_healthy_shard_keeps_delivering_while_a_sibling_fails_with(Supplier<Exception> failure) throws InterruptedException {
        var metrics = new ShardOwnerMetrics();
        var failing = new StubOwner((short) 7, 1, metrics, failure, Integer.MAX_VALUE);
        var healthy = new DeliveringStubOwner((short) 7, 2, metrics);

        var startedAt = System.nanoTime();
        runUntil(() -> System.nanoTime() - startedAt >= TimeUnit.SECONDS.toNanos(1), failing, healthy);
        var ranForMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(healthy.passes.get())
                .as("the healthy shard is pumped at its own rate, not five times a second (ran %d ms)", ranForMillis)
                .isGreaterThanOrEqualTo(50);
        assertThat(metrics.pumpFailures.sum()).as("only the failing shard's passes are counted").isEqualTo(failing.passes.get());
        var retryMillis = TimeUnit.NANOSECONDS.toMillis(ShardPump.PUMP_RETRY_NANOS);
        assertThat(failing.passes.get())
                .as("the failing shard is retried every %d ms, neither abandoned nor on every pass (ran %d ms)", retryMillis, ranForMillis)
                .isGreaterThanOrEqualTo(2)
                .isLessThanOrEqualTo((int) (ranForMillis / retryMillis) + 2);
        assertThat(aboutShard(2)).as("nothing is logged about the healthy shard").isEmpty();
    }

    /**
     * A shard waiting out a failed pass is not pumped before its retry, so its own park deadline must not wake the pump:
     * it is typically 0 by then (the acknowledgement that failed is still pending), and folding it in parked the pump
     * 1 ms at a time, about a thousand passes a second, for as long as the shard kept failing. With an idle sibling
     * nothing else wakes the pump, so it passes about once per {@link ShardPump#PUMP_RETRY_NANOS}.
     */
    @Test
    void a_shard_waiting_out_a_failed_pass_does_not_wake_the_pump_on_its_own_deadline() throws InterruptedException {
        var metrics = new ShardOwnerMetrics();
        var failing = new StubOwner((short) 7, 1, metrics, () -> new IllegalStateException("pass refused"), Integer.MAX_VALUE) {
            @Override
            public long parkDeadlineMillis() {
                return 0L;
            }
        };
        var idle = new IdleStubOwner((short) 7, 2, metrics);

        var startedAt = System.nanoTime();
        runUntil(() -> System.nanoTime() - startedAt >= TimeUnit.SECONDS.toNanos(1), failing, idle);
        var ranForMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        var retryMillis = TimeUnit.NANOSECONDS.toMillis(ShardPump.PUMP_RETRY_NANOS);
        assertThat(failing.passes.get()).as("the failing shard is still retried (ran %d ms)", ranForMillis).isGreaterThanOrEqualTo(2);
        assertThat(idle.attentionAsked.get())
                .as("one pump pass per %d ms retry, not one per millisecond (ran %d ms)", retryMillis, ranForMillis)
                .isLessThanOrEqualTo((int) (ranForMillis / retryMillis) + 3);
    }

    /**
     * A retry that has fallen due is dropped before the shard's lease, delivery and attention checks, not only when the
     * shard is pumped. Left in place for a shard that then has nothing to do, it was 0 ms away on every later park, so
     * the pump woke every millisecond for it until the shard next needed attention.
     */
    @Test
    void a_due_retry_is_dropped_even_when_the_shard_then_needs_no_attention() throws InterruptedException {
        var metrics = new ShardOwnerMetrics();
        var failing = new StubOwner((short) 7, 1, metrics, () -> new IllegalStateException("pass refused"), Integer.MAX_VALUE) {
            private final AtomicBoolean asked = new AtomicBoolean();

            @Override
            public boolean needsAttention() {
                // Once: the pass fails, and by the time its retry falls due there is nothing left to do
                return asked.compareAndSet(false, true);
            }

            @Override
            public long parkDeadlineMillis() {
                return IdleStubOwner.FAR_DEADLINE_MILLIS;
            }
        };
        var idle = new IdleStubOwner((short) 7, 2, metrics);

        var startedAt = System.nanoTime();
        runUntil(() -> System.nanoTime() - startedAt >= TimeUnit.SECONDS.toNanos(1), failing, idle);
        var ranForMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        var retryMillis = TimeUnit.NANOSECONDS.toMillis(ShardPump.PUMP_RETRY_NANOS);
        assertThat(failing.passes.get()).as("pumped once, when it needed attention").isEqualTo(1);
        assertThat(idle.attentionAsked.get())
                .as("the pump parks on its owners' deadlines once the retry is due, not every millisecond (ran %d ms)", ranForMillis)
                .isLessThanOrEqualTo((int) (ranForMillis / retryMillis) + 3);
    }

    /**
     * A selection check that threw once and then completes without throwing ends the shard's run of failures, although
     * the shard, needing no attention, is not pumped. The run used to stay open until the shard was next pumped (or its
     * lease ended), so a shard sitting idle after one throw had its next, unrelated throw - hours later, say - reported as
     * the second failure in a row over the whole idle period, and its eventual recovery INFO claimed it had been failing
     * all along.
     */
    @Test
    void a_selection_check_that_threw_once_and_then_completes_ends_the_run_while_the_shard_is_idle() throws InterruptedException {
        var metrics = new ShardOwnerMetrics();
        var owner = new StubOwner((short) 7, 1, metrics, () -> new IllegalStateException("never thrown"), 0) {
            final AtomicInteger asked = new AtomicInteger();

            @Override
            public boolean needsAttention() {
                // Throws on the first and the sixth check, and needs no attention otherwise: never pumped
                var check = asked.incrementAndGet();
                if (check == 1 || check == 6) {
                    throw new IllegalStateException("attention check broke");
                }
                return false;
            }
        };

        runUntil(() -> owner.asked.get() >= 8, owner);

        assertThat(owner.passes.get()).as("premise: never attentive, so never pumped").isZero();
        assertThat(metrics.pumpFailures.sum()).isEqualTo(2);
        var aboutShard = aboutShard(1);
        assertThat(aboutShard.stream().map(ILoggingEvent::getLevel).toList())
                .as("each throw is a run of its own, closed by the next check that completes; the second, inside the "
                    + "subscription's minute, logged no ERROR, so its recovery is at DEBUG too")
                .containsExactly(Level.ERROR, Level.INFO, Level.DEBUG, Level.DEBUG);
        assertThat(aboutShard.get(0).getFormattedMessage()).contains("queue 7, unordered shard 1 threw while being selected")
                                                           .contains("1 failed pass(es) in a row");
        assertThat(aboutShard.get(1).getFormattedMessage()).contains("queue 7, unordered shard 1 passes its selection checks again after 1 failed pass(es)");
        assertThat(aboutShard.get(2).getFormattedMessage()).as("not the second in a row: the checks completed in between")
                                                           .contains("threw while being selected (failed pass 1 in a row)");
        assertThat(aboutShard.get(3).getFormattedMessage()).contains("queue 7, unordered shard 1 passes its selection checks again after 1 failed pass(es)");
    }

    private void runUntil(Callable<Boolean> done, LeasedOwner... owners) throws InterruptedException {
        runUntil(healthyDataSource(), ShardOwnerSettings.defaults(), done, owners);
    }

    private void runUntil(DataSource dataSource, ShardOwnerSettings settings, Callable<Boolean> done, LeasedOwner... owners)
            throws InterruptedException {
        var running = new AtomicBoolean(true);
        var pump = new ShardPump(new ShardOwnedStorage(dataSource, PUMP_QUEUE_ID), settings,
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
                .contains("pump-0: batched read for queue 9 (shards [0, 1]) failed")
                .contains("batchedReadFailures");
        assertThat(aboutBatch.getFirst().getThrowableProxy()).as("the first carries the stack trace").isNotNull();
        assertThat(aboutBatch.getLast().getThrowableProxy()).as("a repeat carries none").isNull();
        assertThat(aboutBatch.getLast().getFormattedMessage()).contains("java.sql.SQLException: permission denied");
    }

    /**
     * A second {@code consume()} on the same queue in one process (warned about, not refused) gives its owners their own
     * {@link ShardOwnerMetrics} on the same runtime, so one pump can serve owners of one queue id with two metrics objects.
     * Grouped by queue id alone, their batched read was one statement whose failures were all charged - counter and WARN
     * window - to whichever subscription's owner came first, and the other subscription's {@code batchedReadFailures}
     * stayed at zero while its shards' batched read failed on every pass.
     */
    @Test
    void a_failing_batched_read_is_charged_to_each_subscription_on_the_queue_not_to_the_first() throws InterruptedException {
        var first       = new ShardOwnerMetrics();
        var second      = new ShardOwnerMetrics();
        var firstOwner  = new BatchStubOwner((short) 9, 0, first);
        var firstOther  = new BatchStubOwner((short) 9, 1, first);
        var secondOwner = new BatchStubOwner((short) 9, 2, second);
        var secondOther = new BatchStubOwner((short) 9, 3, second);

        runUntil(() -> firstOwner.passes.get() >= 5, firstOwner, secondOwner, firstOther, secondOther);

        assertThat(first.batchedReadFailures.sum()).as("one failed batched read per pass for the first subscription")
                                                   .isEqualTo(firstOwner.passes.get());
        assertThat(second.batchedReadFailures.sum()).as("one failed batched read per pass for the second subscription")
                                                    .isEqualTo(secondOwner.passes.get());
        var warnings = logged.list.stream()
                                  .filter(event -> event.getFormattedMessage().contains("batched read for queue 9"))
                                  .filter(event -> event.getLevel() == Level.WARN)
                                  .toList();
        assertThat(warnings).as("each subscription takes its own WARN window").hasSize(2);
        assertThat(warnings.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .as("each WARN names its subscription's shards, so the two can be told apart")
                .anySatisfy(message -> assertThat(message).contains("batched read for queue 9 (shards [0, 1]) failed"))
                .anySatisfy(message -> assertThat(message).contains("batched read for queue 9 (shards [2, 3]) failed"));
        assertThat(first.pumpLoopFailures.sum() + second.pumpLoopFailures.sum()).isZero();
    }

    /**
     * The batched read calls owner code - {@link BatchReadableOwner#batchReadCursor()}, {@code batchSweepDue()},
     * {@code applyBatchRead(...)} - and its guard caught only {@code SQLException | RuntimeException}. A non-critical
     * {@link Error} from one of them (an {@link AssertionError}) went to the pump's outer catch instead: the pass ended
     * before any owner was pumped, the pump reconnected and reran every takeover, and failed again, so its shards
     * delivered nothing. Now it is a failed batched read like any other, and the shards read for themselves.
     */
    @Test
    void an_owner_whose_batch_read_hook_throws_a_non_critical_error_does_not_stop_the_pump() throws InterruptedException {
        var metrics = new ShardOwnerMetrics();
        var failing = new BatchStubOwner((short) 9, 0, metrics) {
            @Override
            public long batchReadCursor() {
                throw new AssertionError("broken cursor");
            }
        };
        var sibling = new BatchStubOwner((short) 9, 1, metrics);

        runUntil(() -> failing.passes.get() >= 5 && sibling.passes.get() >= 5, failing, sibling);

        assertThat(metrics.pumpLoopFailures.sum()).as("not a failed pass").isZero();
        assertThat(metrics.batchedReadFailures.sum()).as("a failed batched read, every pass").isEqualTo(failing.passes.get());
        assertThat(metrics.pumpFailures.sum()).as("the shards' own passes succeed").isZero();
        assertThat(logged.list.stream()
                              .filter(event -> event.getFormattedMessage().contains("batched read for queue 9"))
                              .map(ILoggingEvent::getFormattedMessage)
                              .toList())
                .last()
                .asString()
                .contains("java.lang.AssertionError: broken cursor");
    }

    /**
     * The pump reads its batch with ITS settings' {@code readBatchSize} - the runtime's - and an owner's own can be larger
     * (a runtime keeps its first borrower's settings, and a queue given a runtime keeps its own). The owner caps its
     * watermark at the last row of a read that returned a full batch, and it judged "full" against its own
     * {@code readBatchSize}, so a truncated batched read passed for a complete one and the watermark stepped over rows
     * the read never reached. The pump now hands each owner the limit its read was issued with.
     */
    @Test
    void a_batched_read_hands_each_owner_the_row_limit_the_pump_read_it_with() throws InterruptedException {
        var pumpSettings = ShardOwnerSettings.builder().setReadBatchSize(7).build();
        var metrics = new ShardOwnerMetrics();
        var first   = new BatchStubOwner((short) 9, 0, metrics);
        var second  = new BatchStubOwner((short) 9, 1, metrics);

        runUntil(emptyResultDataSource(), pumpSettings, () -> first.batchesApplied.get() >= 3 && second.batchesApplied.get() >= 3,
                 first, second);

        assertThat(metrics.batchedReadFailures.sum()).as("the batched read succeeds").isZero();
        assertThat(first.cursorLimits).as("the pump's readBatchSize, not the default 500").isNotEmpty().containsOnly(7);
        assertThat(second.cursorLimits).as("the pump's readBatchSize, not the default 500").isNotEmpty().containsOnly(7);
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
    // This test class's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees
    // its interface, which the context loader need not. The connection is a stateless stub handed out on every
    // getConnection(), holding nothing to close. Identity is the correct equals() for a proxy.
    @SuppressWarnings({"PMD.UseProperClassLoader", "PMD.CloseResource", "PMD.CompareObjectsWithEquals"})
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
     * Hands out connections whose every statement succeeds and returns no rows, so the pump's batched read completes and
     * is applied to the owners.
     */
    // This test class's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees
    // its interface, which the context loader need not. The result set, statement and connection are stateless stubs,
    // the same instance handed out on every call, holding nothing to close. Identity is the correct equals() for a proxy.
    @SuppressWarnings({"PMD.UseProperClassLoader", "PMD.CloseResource", "PMD.CompareObjectsWithEquals"})
    private static DataSource emptyResultDataSource() {
        var loader = ShardPumpDeliveryFailureTest.class.getClassLoader();
        var resultSet = (ResultSet) Proxy.newProxyInstance(loader, new Class<?>[]{ResultSet.class},
                                                           (proxy, method, args) -> switch (method.getName()) {
                                                               case "next", "wasNull" -> false;
                                                               case "close" -> null;
                                                               case "hashCode" -> System.identityHashCode(proxy);
                                                               case "equals" -> proxy == args[0];
                                                               case "toString" -> "empty result set";
                                                               default -> throw new UnsupportedOperationException(method.getName());
                                                           });
        var statement = (PreparedStatement) Proxy.newProxyInstance(loader, new Class<?>[]{PreparedStatement.class},
                                                                   (proxy, method, args) -> switch (method.getName()) {
                                                                       case "setArray", "setShort", "setInt", "setLong", "close" -> null;
                                                                       case "executeQuery" -> resultSet;
                                                                       case "hashCode" -> System.identityHashCode(proxy);
                                                                       case "equals" -> proxy == args[0];
                                                                       case "toString" -> "empty statement";
                                                                       default -> throw new UnsupportedOperationException(method.getName());
                                                                   });
        var connection = (Connection) Proxy.newProxyInstance(loader, new Class<?>[]{Connection.class},
                                                             (proxy, method, args) -> switch (method.getName()) {
                                                                 case "isValid" -> true;
                                                                 case "prepareStatement" -> statement;
                                                                 case "createArrayOf", "close" -> null;
                                                                 case "isClosed" -> false;
                                                                 case "hashCode" -> System.identityHashCode(proxy);
                                                                 case "equals" -> proxy == args[0];
                                                                 case "toString" -> "empty-result connection";
                                                                 default -> throw new UnsupportedOperationException(method.getName());
                                                             });
        return (DataSource) Proxy.newProxyInstance(loader, new Class<?>[]{DataSource.class},
                                                   (proxy, method, args) -> switch (method.getName()) {
                                                       case "getConnection" -> connection;
                                                       case "hashCode" -> System.identityHashCode(proxy);
                                                       case "equals" -> proxy == args[0];
                                                       case "toString" -> "empty-result data source";
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
        public LeaseRow leaseRow() {
            return LeaseRow.HELD;
        }

        @Override
        public void endKeepingLeaseRow() {
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
     * A shard with work on every pass: each pass delivers one message, so the pump does not park while it is served.
     */
    private static final class DeliveringStubOwner extends StubOwner {
        DeliveringStubOwner(short queueId, int shard, ShardOwnerMetrics metrics) {
            super(queueId, shard, metrics, () -> new IllegalStateException("never thrown"), 0);
        }

        @Override
        public int pumpOnce(Connection connection) {
            passes.incrementAndGet();
            return 1;
        }
    }

    /**
     * A shard with nothing to do and a far park deadline, so it never wakes the pump itself. It is asked
     * {@code needsAttention} once per pass, which makes {@link #attentionAsked} the pump's pass count.
     */
    private static final class IdleStubOwner extends StubOwner {
        static final long          FAR_DEADLINE_MILLIS = 10_000L;
        final        AtomicInteger attentionAsked      = new AtomicInteger();

        IdleStubOwner(short queueId, int shard, ShardOwnerMetrics metrics) {
            super(queueId, shard, metrics, () -> new IllegalStateException("never thrown"), 0);
        }

        @Override
        public boolean needsAttention() {
            attentionAsked.incrementAndGet();
            return false;
        }

        @Override
        public long parkDeadlineMillis() {
            return FAR_DEADLINE_MILLIS;
        }
    }

    /**
     * An ordered-lane owner whose own passes succeed, so it is read for in batch whenever a sibling of its queue is
     * attentive in the same pass.
     */
    private static class BatchStubOwner extends StubOwner implements BatchReadableOwner {
        final AtomicInteger  batchesApplied = new AtomicInteger();
        /** The cursor read's row limit of every batch applied, in order. */
        final Queue<Integer> cursorLimits   = new ConcurrentLinkedQueue<>();

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
        public void applyBatchRead(List<ShardOwnedStorage.OrderedRow> cursorRows, int cursorLimit, List<ShardOwnedStorage.OrderedRow> sweptRows,
                                   OptionalLong nextVisibleMillis) {
            cursorLimits.add(cursorLimit);
            batchesApplied.incrementAndGet();
        }
    }
}
