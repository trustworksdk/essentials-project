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
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.PGConnection;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * How a pump's exit treats the shards it served, and what it takes for a pump to exit at all.
 * <p>
 * On an orderly stop ({@code running} cleared) the pump serves none of its shards any more, so none of them is awaiting
 * a takeover there. Anything a retry could cure no longer ends the pump: it reconnects, as from a lost connection. A pump
 * that DIES while the runtime runs - on a critical error - is different: nothing restarts it and the heartbeat keeps
 * renewing its shards' leases, so its shards are still leased and unserved. They are counted in
 * {@code shardsOnDeadPumps}, its stalled shards stay counted in {@code shardsAwaitingTakeover}, and shards acquired later
 * go to a live pump. A stop forgets all of that once the dead pump's thread is known to have ended, whether or not every
 * other pump ended within the stop's wait.
 */
class ShardPumpExitTest {
    /**
     * A pump's storage handle is bound to no queue in particular, as in {@code ShardRuntime}: it only opens connections.
     */
    private static final short PUMP_QUEUE_ID = 0;

    private ListAppender<ILoggingEvent>   logged;
    private ch.qos.logback.classic.Logger pumpLogger;
    private Level                         pumpLoggerLevel;

    @BeforeEach
    void captureLog() {
        logged = new ListAppender<>();
        logged.start();
        pumpLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardPump.class);
        pumpLoggerLevel = pumpLogger.getLevel();
        pumpLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        pumpLogger.detachAppender(logged);
        pumpLogger.setLevel(pumpLoggerLevel);
    }

    @Test
    void a_pump_that_dies_while_running_keeps_its_shards_counted_and_says_so_at_error() {
        var told    = new CopyOnWriteArrayList<String>();
        var metrics = new ShardOwnerMetrics(observer(told));
        var running = new AtomicBoolean(true);
        var pump    = pump(running);
        pump.add(new StubOwner((short) 7, 1, metrics, Behaviour.TAKEOVER_FAILS));
        pump.add(new StubOwner((short) 7, 2, metrics, Behaviour.DIES));

        assertThatThrownBy(pump::run).as("the pump's death is not swallowed").isSameAs(StubOwner.DEATH);

        assertThat(running.get()).as("premise: the runtime is still running").isTrue();
        assertThat(pump.isDead()).isTrue();
        assertThat(metrics.shardsAwaitingTakeover.get()).as("shard 1 is still leased and still unserved").isEqualTo(1);
        assertThat(metrics.shardsOnDeadPumps.get()).as("both shards stay leased to the dead pump").isEqualTo(2);
        assertThat(told).as("the observer is not told the stall ended").containsExactly("UNORDERED/1=true");
        var died = loggedEvents().stream()
                                 .filter(event -> event.getLevel() == Level.ERROR)
                                 .filter(event -> event.getFormattedMessage().contains("pump-0 died"))
                                 .toList();
        assertThat(died).hasSize(1);
        assertThat(died.getFirst().getFormattedMessage())
                .contains("2 shard(s)")
                .contains("stay leased to this instance")
                .contains("shardsOnDeadPumps")
                .contains("shardsAwaitingTakeover");
        assertThat(died.getFirst().getThrowableProxy().getMessage()).isEqualTo(StubOwner.DEATH.getMessage());
    }

    /**
     * Anything a retry could cure no longer ends the pump. An {@code AssertionError} thrown outside every per-shard guard
     * - here by the pool handing out the pump's connection - used to end the thread and leave every shard on the pump
     * leased and served by nobody.
     */
    @Test
    void a_pump_whose_pass_throws_a_non_critical_error_reconnects_and_keeps_serving_its_shards() throws InterruptedException {
        var metrics     = new ShardOwnerMetrics();
        var pumpMetrics = new ShardOwnerMetrics();
        var running     = new AtomicBoolean(true);
        var pump        = pump(new ShardOwnedStorage(failingDataSource(1, new CopyOnWriteArrayList<>()), PUMP_QUEUE_ID), running,
                               pumpMetrics, new AtomicBoolean(false));
        var first       = new StubOwner((short) 7, 1, metrics, Behaviour.SERVED);
        var beside      = new StubOwner((short) 7, 2, metrics, Behaviour.SERVED);
        pump.add(first);
        pump.add(beside);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> beside.pumped.get() > 0 && first.pumped.get() > 0);

            assertThat(thread.isAlive()).as("the pump is still running").isTrue();
            assertThat(pump.isDead()).isFalse();
            assertThat(metrics.pumpLoopFailures.sum()).as("counted on the queue's metrics").isEqualTo(1);
            assertThat(pumpMetrics.pumpLoopFailures.sum()).as("and on the runtime's").isEqualTo(1);
            assertThat(first.takeovers.get()).as("taken over on the connection the pump reconnected with").isEqualTo(1);
            assertThat(metrics.shardsOnDeadPumps.get()).isZero();
            var errors = loggedEvents().stream().filter(event -> event.getLevel() == Level.ERROR).toList();
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().getFormattedMessage())
                    .contains("pump-0")
                    .contains("pumpLoopFailures")
                    .as("states the reconnect pace actually used")
                    .contains("reconnects in " + TimeUnit.NANOSECONDS.toMillis(ShardPump.RECONNECT_NANOS) + " ms")
                    .doesNotContain("died");
            assertThat(errors.getFirst().getThrowableProxy().getMessage()).isEqualTo(StubOwner.PASS_FAILURE.getMessage());
        } finally {
            running.set(false);
            pump.wakeup().signal();
            assertThat(thread.join(Duration.ofSeconds(10))).isTrue();
        }
    }

    /**
     * A failure that does not clear is paced and does not flood the log: one ERROR with the stack trace, every other
     * failure at DEBUG, one pass every 200 ms rather than as fast as the pump can throw, and an INFO once a pass completes.
     */
    @Test
    void a_pass_failure_that_persists_is_paced_and_logged_at_error_once() throws InterruptedException {
        pumpLogger.setLevel(Level.DEBUG);
        var failures = 5;
        var failedAt = new CopyOnWriteArrayList<Long>();
        var metrics  = new ShardOwnerMetrics();
        var running  = new AtomicBoolean(true);
        var pump     = pump(new ShardOwnedStorage(failingDataSource(failures, failedAt), PUMP_QUEUE_ID), running,
                            new ShardOwnerMetrics(), new AtomicBoolean(false));
        var served   = new StubOwner((short) 7, 1, metrics, Behaviour.SERVED);
        pump.add(served);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            // The INFO follows the pass that completed, so it is what to wait for.
            await().atMost(Duration.ofSeconds(10))
                   .until(() -> loggedEvents().stream().anyMatch(event -> event.getLevel() == Level.INFO));

            assertThat(served.pumped.get()).as("served once the failure cleared").isPositive();
            assertThat(metrics.pumpLoopFailures.sum()).isEqualTo(failures);
            assertThat(failedAt).hasSize(failures);
            assertThat(Duration.ofNanos(failedAt.getLast() - failedAt.getFirst()))
                    .as("paced at 200 ms per failed pass, not spinning")
                    .isGreaterThanOrEqualTo(Duration.ofMillis((failures - 1) * 190L));
            var passFailures = loggedEvents().stream()
                                             .filter(event -> event.getFormattedMessage().contains("outside its per-shard guards"))
                                             .toList();
            assertThat(passFailures.stream().filter(event -> event.getLevel() == Level.ERROR))
                    .as("one ERROR inside the minute's window").hasSize(1);
            assertThat(passFailures.stream().filter(event -> event.getLevel() == Level.DEBUG))
                    .as("every later failure at DEBUG").hasSize(failures - 1);
            assertThat(passFailures.stream().filter(event -> event.getLevel() == Level.DEBUG))
                    .as("without a stack trace").allMatch(event -> event.getThrowableProxy() == null);
            assertThat(loggedEvents().stream()
                                     .filter(event -> event.getLevel() == Level.INFO)
                                     .map(ILoggingEvent::getFormattedMessage))
                    .containsExactly("pump-0: a pass completed again after " + failures + " failed pass(es)");
        } finally {
            running.set(false);
            pump.wakeup().signal();
            assertThat(thread.join(Duration.ofSeconds(10))).isTrue();
        }
    }

    /**
     * A non-critical {@code Error} from one owner's delivery pass is that owner's alone, like an unchecked exception. It
     * used to get past the per-owner guard, end the pass for every owner after it and reconnect the pump, rerunning every
     * owner's takeover, five times a second while it persisted.
     */
    @Test
    void an_owner_whose_delivery_keeps_throwing_a_non_critical_error_does_not_hold_up_its_sibling() throws InterruptedException {
        var metrics     = new ShardOwnerMetrics();
        var pumpMetrics = new ShardOwnerMetrics();
        var running     = new AtomicBoolean(true);
        var pump        = pump(running, pumpMetrics);
        var failing     = new StubOwner((short) 7, 1, metrics, Behaviour.DELIVERY_ERRORS);
        var beside      = new StubOwner((short) 7, 2, metrics, Behaviour.SERVED);
        pump.add(failing);
        pump.add(beside);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> failing.pumped.get() >= 3 && beside.pumped.get() >= 3);

            assertThat(metrics.pumpFailures.sum()).as("counted as the owner's failure").isGreaterThanOrEqualTo(3);
            assertThat(metrics.pumpLoopFailures.sum()).as("not as the pass's").isZero();
            assertThat(pumpMetrics.pumpLoopFailures.sum()).isZero();
            assertThat(failing.takeovers.get()).as("no reconnect reran the takeovers").isEqualTo(1);
            assertThat(beside.takeovers.get()).isEqualTo(1);
            assertThat(thread.isAlive()).isTrue();
            assertThat(pump.isDead()).isFalse();
        } finally {
            running.set(false);
            pump.wakeup().signal();
            assertThat(thread.join(Duration.ofSeconds(10))).isTrue();
        }
    }

    /**
     * A non-critical {@code Error} from one owner's takeover stalls that owner alone, counted as a stalled takeover. It
     * used to reconnect the pump on every attempt, so no owner after it was ever pumped.
     */
    @Test
    void an_owner_whose_takeover_keeps_throwing_a_non_critical_error_stalls_alone() throws InterruptedException {
        var metrics     = new ShardOwnerMetrics();
        var pumpMetrics = new ShardOwnerMetrics();
        var running     = new AtomicBoolean(true);
        var pump        = pump(running, pumpMetrics);
        var failing     = new StubOwner((short) 7, 1, metrics, Behaviour.TAKEOVER_ERRORS);
        var beside      = new StubOwner((short) 7, 2, metrics, Behaviour.SERVED);
        pump.add(failing);
        pump.add(beside);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> metrics.takeoverFailures.sum() >= 3 && beside.pumped.get() > 0);

            assertThat(metrics.shardsAwaitingTakeover.get()).as("the failing shard is a stalled takeover").isEqualTo(1);
            assertThat(metrics.pumpLoopFailures.sum()).as("not a failed pass").isZero();
            assertThat(pumpMetrics.pumpLoopFailures.sum()).isZero();
            assertThat(beside.takeovers.get()).as("no reconnect reran the sibling's takeover").isEqualTo(1);
            assertThat(thread.isAlive()).isTrue();
        } finally {
            running.set(false);
            pump.wakeup().signal();
            assertThat(thread.join(Duration.ofSeconds(10))).isTrue();
        }
    }

    /**
     * A selection check ({@code leaseHeld}, {@code deliveryPermitted}, {@code needsAttention}) that keeps throwing is its
     * owner's failure alone, like a throwing {@code pumpOnce}. It used to end the pass in {@code serve()}'s outer catch:
     * no owner on the pump was pumped, and the pump reconnected and reran every owner's takeover five times a second for
     * as long as it threw, so one owner's bug stopped every shard of every queue on the pump.
     */
    @ParameterizedTest
    @EnumSource(value = Behaviour.class, names = {"LEASE_CHECK_ERRORS", "PERMIT_ERRORS", "ATTENTION_ERRORS"})
    void an_owner_whose_selection_check_keeps_throwing_does_not_hold_up_its_sibling(Behaviour behaviour) throws InterruptedException {
        var metrics     = new ShardOwnerMetrics();
        var pumpMetrics = new ShardOwnerMetrics();
        var running     = new AtomicBoolean(true);
        var pump        = pump(running, pumpMetrics);
        var failing     = new StubOwner((short) 7, 1, metrics, behaviour);
        var beside      = new StubOwner((short) 7, 2, metrics, Behaviour.SERVED);
        pump.add(failing);
        pump.add(beside);
        var startedAt = System.nanoTime();
        var thread    = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> metrics.pumpFailures.sum() >= 3 && beside.pumped.get() >= 3);

            // Read before the clock, so the bound can only be generous. Each charge sets a retry PUMP_RETRY_NANOS out
            // and the next charge waits for it, so the n-th charge comes at least (n - 1) retries after the start. A
            // check charged twice per retry (the selection guard and the end-of-pass lease sweep both counting the same
            // throw) breaks the bound by its third charge.
            var charged = metrics.pumpFailures.sum();
            var elapsed = System.nanoTime() - startedAt;
            assertThat(charged).as("charged once per retry, not by every check that saw the throw")
                               .isLessThanOrEqualTo(elapsed / ShardPump.PUMP_RETRY_NANOS + 1);
            assertThat(metrics.pumpLoopFailures.sum()).as("not a failed pass").isZero();
            assertThat(pumpMetrics.pumpLoopFailures.sum()).isZero();
            assertThat(failing.pumped.get()).as("never selected, so never pumped").isZero();
            assertThat(failing.takeovers.get()).as("no reconnect reran the takeovers").isEqualTo(1);
            assertThat(beside.takeovers.get()).isEqualTo(1);
            assertThat(pump.shardsServed()).as("an owner whose lease check throws is kept, and retried").isEqualTo(2);
            assertThat(thread.isAlive()).isTrue();
            assertThat(pump.isDead()).isFalse();
            assertThat(loggedEvents().stream().map(ILoggingEvent::getFormattedMessage))
                    .anyMatch(message -> message.contains("shard 1 threw while being selected"))
                    .noneMatch(message -> message.contains("outside its per-shard guards"));
        } finally {
            running.set(false);
            pump.wakeup().signal();
            assertThat(thread.join(Duration.ofSeconds(10))).isTrue();
        }
    }

    /**
     * A stopping pump flushes every owner's acknowledgements, past one whose flush throws, and does not report the throw
     * as a failed pass: the pump is stopping, so it neither reconnects nor runs any takeover again. The throw used to
     * reach {@code serve()}'s outer catch, which skipped the owners after it, counted a {@code pumpLoopFailures} and
     * logged at ERROR a reconnect that never came.
     */
    @Test
    void a_stopping_pump_flushes_every_owner_past_one_whose_flush_throws() throws InterruptedException {
        var stopped = stopFlushing(healthyDataSource(), Behaviour.FLUSH_ERRORS);

        assertThat(stopped.beside().flushes.get()).as("the owner after it is still flushed").isEqualTo(1);
        var warned = assertStopNotCountedAndWarnedOnce(stopped);
        assertThat(warned.getFormattedMessage()).contains("pump-0").contains("queue 7, unordered shard 1").contains("on stop failed");
        assertThat(warned.getThrowableProxy().getMessage()).isEqualTo(StubOwner.OWNER_ERROR.getMessage());
    }

    /**
     * A flush statement the database refuses on a healthy connection is that owner's alone too: the owners after it are
     * still flushed. It used to reach {@code serve()}'s {@code SQLException} catch, which skipped them, counted a
     * {@code connectionFailures} and logged a reconnect that never came.
     */
    @Test
    void a_stopping_pump_flushes_every_owner_past_one_whose_flush_statement_fails_on_a_healthy_connection() throws InterruptedException {
        var stopped = stopFlushing(healthyDataSource(), Behaviour.FLUSH_STATEMENT_FAILS);

        assertThat(stopped.beside().flushes.get()).as("the owner after it is still flushed").isEqualTo(1);
        var warned = assertStopNotCountedAndWarnedOnce(stopped);
        assertThat(warned.getFormattedMessage()).contains("pump-0").contains("queue 7, unordered shard 1").contains("on stop failed");
        assertThat(warned.getThrowableProxy().getMessage()).isEqualTo(StubOwner.STATEMENT_FAILURE.getMessage());
    }

    /**
     * A flush that fails because the connection itself is gone ends the flushing: every later flush would fail the same
     * way, so the pump says so once, names what it could not flush, and stops.
     */
    @Test
    void a_stopping_pump_that_loses_its_connection_while_flushing_stops_flushing() throws InterruptedException {
        var stopped = stopFlushing(invalidDataSource(), Behaviour.FLUSH_STATEMENT_FAILS);

        assertThat(stopped.beside().flushes.get()).as("nothing is flushed on a broken connection").isZero();
        var warned = assertStopNotCountedAndWarnedOnce(stopped);
        assertThat(warned.getFormattedMessage()).contains("pump-0").contains("lost its connection flushing").contains("queue 7, unordered shard 1");
        assertThat(warned.getThrowableProxy().getMessage()).isEqualTo(StubOwner.STATEMENT_FAILURE.getMessage());
    }

    /**
     * Run a pump serving an owner with {@code behaviour}, then a {@link Behaviour#SERVED} one, and stop it with
     * {@code flushOnExit} set.
     */
    private StoppedPump stopFlushing(DataSource dataSource, Behaviour behaviour) throws InterruptedException {
        var metrics     = new ShardOwnerMetrics();
        var pumpMetrics = new ShardOwnerMetrics();
        var running     = new AtomicBoolean(true);
        var pump        = pump(new ShardOwnedStorage(dataSource, PUMP_QUEUE_ID), running, pumpMetrics, new AtomicBoolean(true));
        var failing     = new StubOwner((short) 7, 1, metrics, behaviour);
        var beside      = new StubOwner((short) 7, 2, metrics, Behaviour.SERVED);
        pump.add(failing);
        pump.add(beside);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        await().atMost(Duration.ofSeconds(10)).until(() -> failing.pumped.get() > 0 && beside.pumped.get() > 0);

        running.set(false);
        pump.wakeup().signal();
        assertThat(thread.join(Duration.ofSeconds(10))).as("the pump stops when running is cleared").isTrue();
        assertThat(failing.flushes.get()).as("premise: the failing owner's flush was tried").isEqualTo(1);
        return new StoppedPump(pump, beside, metrics, pumpMetrics);
    }

    private record StoppedPump(ShardPump pump, StubOwner beside, ShardOwnerMetrics metrics, ShardOwnerMetrics pumpMetrics) {
    }

    /**
     * A stop is neither a failed pass nor a lost connection to recover from, and announces no reconnect.
     *
     * @return the one WARN the pump logged
     */
    private ILoggingEvent assertStopNotCountedAndWarnedOnce(StoppedPump stopped) {
        assertThat(stopped.metrics().pumpLoopFailures.sum()).as("a stop is not a failed pass").isZero();
        assertThat(stopped.pumpMetrics().pumpLoopFailures.sum()).isZero();
        assertThat(stopped.pumpMetrics().connectionFailures.sum()).as("nor a lost connection the pump recovers from").isZero();
        assertThat(stopped.pump().isDead()).isFalse();
        assertThat(loggedEvents().stream().filter(event -> event.getLevel() == Level.ERROR)).as("no reconnect announced").isEmpty();
        var warned = loggedEvents().stream().filter(event -> event.getLevel() == Level.WARN).toList();
        assertThat(warned).hasSize(1);
        assertThat(warned.getFirst().getFormattedMessage()).doesNotContain("reconnecting");
        return warned.getFirst();
    }

    /**
     * Thrown outside every per-owner guard while the pump is stopping, here by an owner's {@code parkDeadlineMillis}: the
     * pump exits without counting a failed pass or announcing a reconnect that will not come.
     */
    @Test
    void a_pass_that_fails_as_the_pump_stops_is_not_counted_as_a_failed_pass() throws InterruptedException {
        var metrics     = new ShardOwnerMetrics();
        var pumpMetrics = new ShardOwnerMetrics();
        var running     = new AtomicBoolean(true);
        var pump        = pump(running, pumpMetrics);
        var stopping    = new StubOwner((short) 7, 1, metrics, Behaviour.PARK_ERRORS_AS_IT_STOPS);
        stopping.beforeFailing = () -> running.set(false);
        pump.add(stopping);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        assertThat(thread.join(Duration.ofSeconds(10))).as("the pump stops when running is cleared").isTrue();

        assertThat(stopping.pumped.get()).as("premise: the pass got as far as parking").isEqualTo(1);
        var warned = assertStopNotCountedAndWarnedOnce(new StoppedPump(pump, stopping, metrics, pumpMetrics));
        assertThat(warned.getFormattedMessage()).contains("pump-0").contains("while the pump was stopping");
        assertThat(warned.getThrowableProxy().getMessage()).isEqualTo(StubOwner.PASS_FAILURE.getMessage());
    }

    /**
     * A connection lost while the pump is stopping is not a connection failure the pump recovers from: it exits, so it
     * neither counts {@code connectionFailures} nor says it is reconnecting.
     */
    @Test
    void a_pump_that_loses_its_connection_as_it_stops_neither_counts_it_nor_says_it_reconnects() throws InterruptedException {
        var metrics     = new ShardOwnerMetrics();
        var pumpMetrics = new ShardOwnerMetrics();
        var running     = new AtomicBoolean(true);
        var pump        = pump(new ShardOwnedStorage(invalidDataSource(), PUMP_QUEUE_ID), running, pumpMetrics, new AtomicBoolean(false));
        var stopping    = new StubOwner((short) 7, 1, metrics, Behaviour.DELIVERY_STATEMENT_FAILS_AS_IT_STOPS);
        stopping.beforeFailing = () -> running.set(false);
        pump.add(stopping);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        assertThat(thread.join(Duration.ofSeconds(10))).as("the pump stops when running is cleared").isTrue();

        assertThat(stopping.pumped.get()).as("premise: the failing pass ran").isEqualTo(1);
        var warned = assertStopNotCountedAndWarnedOnce(new StoppedPump(pump, stopping, metrics, pumpMetrics));
        assertThat(warned.getFormattedMessage()).contains("pump-0").contains("lost its connection while stopping");
        assertThat(warned.getThrowableProxy().getMessage()).isEqualTo(StubOwner.STATEMENT_FAILURE.getMessage());
    }

    /**
     * {@code parkDeadlineMillis} is left outside the per-owner guards, so a throw from it ends the pass after every owner
     * has been pumped: the pump reconnects and runs every owner's takeover again, on the connection it reconnected with.
     */
    @Test
    void a_pass_ended_by_an_owners_park_deadline_reconnects_and_reruns_every_takeover() throws InterruptedException {
        var metrics     = new ShardOwnerMetrics();
        var pumpMetrics = new ShardOwnerMetrics();
        var running     = new AtomicBoolean(true);
        var pump        = pump(running, pumpMetrics);
        var failing     = new StubOwner((short) 7, 1, metrics, Behaviour.PARK_ERRORS_ONCE);
        var beside      = new StubOwner((short) 7, 2, metrics, Behaviour.SERVED);
        pump.add(failing);
        pump.add(beside);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            await().atMost(Duration.ofSeconds(10))
                   .until(() -> loggedEvents().stream().anyMatch(event -> event.getLevel() == Level.INFO));

            assertThat(metrics.pumpLoopFailures.sum()).as("one failed pass").isEqualTo(1);
            assertThat(pumpMetrics.pumpLoopFailures.sum()).isEqualTo(1);
            assertThat(failing.takeovers.get()).as("taken over again on the connection the pump reconnected with").isEqualTo(2);
            assertThat(beside.takeovers.get()).as("every owner's takeover reran, not only the failing one's").isEqualTo(2);
            assertThat(failing.pumped.get()).as("pumped before the throw, and after the reconnect").isGreaterThanOrEqualTo(2);
            assertThat(beside.pumped.get()).isGreaterThanOrEqualTo(2);
            assertThat(metrics.pumpFailures.sum()).as("not charged to the owner").isZero();
            var errors = loggedEvents().stream().filter(event -> event.getLevel() == Level.ERROR).toList();
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().getFormattedMessage()).contains("pump-0").contains("2 shard(s)").contains("pumpLoopFailures");
            assertThat(errors.getFirst().getThrowableProxy().getMessage()).isEqualTo(StubOwner.PASS_FAILURE.getMessage());
            assertThat(thread.isAlive()).isTrue();
            assertThat(pump.isDead()).isFalse();
        } finally {
            running.set(false);
            pump.wakeup().signal();
            assertThat(thread.join(Duration.ofSeconds(10))).isTrue();
        }
    }

    /**
     * A {@code parkDeadlineMillis} that keeps throwing ends every pass that delivered nothing, each one reconnecting and
     * rerunning every takeover, and is reported as the run of failed passes it is. The pass used to be reported complete
     * before it parked: an INFO "completed again" on every pass, and a count of failed passes in a row that never got
     * past one.
     */
    @Test
    void a_park_deadline_that_keeps_throwing_is_reported_as_one_run_of_failed_passes() throws InterruptedException {
        pumpLogger.setLevel(Level.DEBUG);
        var metrics     = new ShardOwnerMetrics();
        var pumpMetrics = new ShardOwnerMetrics();
        var running     = new AtomicBoolean(true);
        var pump        = pump(running, pumpMetrics);
        var failing     = new StubOwner((short) 7, 1, metrics, Behaviour.PARK_ERRORS);
        var beside      = new StubOwner((short) 7, 2, metrics, Behaviour.SERVED);
        pump.add(failing);
        pump.add(beside);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> metrics.pumpLoopFailures.sum() >= 3);

            assertThat(beside.pumped.get()).as("each pass still pumps before it fails").isGreaterThanOrEqualTo(3);
            assertThat(beside.takeovers.get()).as("each failed pass reran every takeover").isGreaterThanOrEqualTo(3);
            assertThat(loggedEvents().stream().map(ILoggingEvent::getFormattedMessage))
                    .as("no pass is reported complete while it keeps failing")
                    .noneMatch(message -> message.contains("completed again"))
                    .anyMatch(message -> message.contains("(3 failed pass(es) in a row)"));
            assertThat(loggedEvents().stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1);
            assertThat(thread.isAlive()).isTrue();
        } finally {
            running.set(false);
            pump.wakeup().signal();
            assertThat(thread.join(Duration.ofSeconds(10))).isTrue();
        }
    }

    @Test
    void a_pump_stopped_in_order_no_longer_counts_its_stalled_shards() throws InterruptedException {
        var told    = new CopyOnWriteArrayList<String>();
        var metrics = new ShardOwnerMetrics(observer(told));
        var running = new AtomicBoolean(true);
        var pump    = pump(running);
        pump.add(new StubOwner((short) 7, 1, metrics, Behaviour.TAKEOVER_FAILS));
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        await().atMost(Duration.ofSeconds(10)).until(() -> metrics.shardsAwaitingTakeover.get() == 1);

        running.set(false);
        pump.wakeup().signal();
        assertThat(thread.join(Duration.ofSeconds(10))).as("the pump stops when running is cleared").isTrue();

        assertThat(metrics.shardsAwaitingTakeover.get()).as("none of its shards is served by it any more").isZero();
        assertThat(told).containsExactly("UNORDERED/1=true", "UNORDERED/1=false");
        assertThat(loggedEvents().stream().map(ILoggingEvent::getFormattedMessage)).noneMatch(message -> message.contains("died"));
        assertThat(pump.isDead()).isFalse();
    }

    /**
     * The stop ends the leases the dead pump was keeping, so its shards are awaiting nothing any more. Before it, nothing
     * ever forgot them: the metrics outlive the runtime, so the restart the death's ERROR recommends left
     * {@code shardsAwaitingTakeover} (and the observer's gauge) stuck above zero for the rest of the process.
     */
    @Test
    void stopping_the_runtime_forgets_the_shards_of_a_pump_that_had_died() {
        var told    = new CopyOnWriteArrayList<String>();
        var metrics = new ShardOwnerMetrics(observer(told));
        var runtime = new ShardRuntime(listenableDataSource(), pumps(1), new ShardOwnerMetrics());
        try {
            runtime.register((short) 7, "unordered", 1, new StubOwner((short) 7, 1, metrics, Behaviour.TAKEOVER_FAILS));
            await().atMost(Duration.ofSeconds(10)).until(() -> metrics.shardsAwaitingTakeover.get() == 1);
            runtime.register((short) 7, "unordered", 2, new StubOwner((short) 7, 2, metrics, Behaviour.DIES));
            awaitDeath("pump-0");
            assertThat(metrics.shardsAwaitingTakeover.get()).as("premise: the dead pump kept its stalled shard counted").isEqualTo(1);
            assertThat(metrics.shardsOnDeadPumps.get()).as("premise: both shards are on the dead pump").isEqualTo(2);
        } finally {
            runtime.stop();
        }

        assertThat(metrics.shardsAwaitingTakeover.get()).as("the stop ended the lease the dead pump was keeping").isZero();
        assertThat(metrics.shardsOnDeadPumps.get()).isZero();
        assertThat(told).containsExactly("UNORDERED/1=true", "UNORDERED/1=false");
    }

    /**
     * A shard acquired after a pump died is routed to a live pump. Routing used to hash over every pump, dead ones
     * included, so a rebalance kept handing the dead pump new shards that nobody served.
     */
    @Test
    void a_shard_registered_after_its_pump_died_is_served_by_a_live_pump() {
        var metrics = new ShardOwnerMetrics();
        var runtime = new ShardRuntime(listenableDataSource(), pumps(2), new ShardOwnerMetrics());
        try {
            var shards = shardsRoutedTo(0, 2, 2);
            runtime.register((short) 7, "unordered", shards.get(0), new StubOwner((short) 7, shards.get(0), metrics, Behaviour.DIES));
            awaitDeath("pump-0");
            assertThat(metrics.shardsOnDeadPumps.get()).as("premise: the dying shard is on the dead pump").isEqualTo(1);

            var later  = new StubOwner((short) 7, shards.get(1), metrics, Behaviour.SERVED);
            var wakeup = runtime.wakeupFor((short) 7, "unordered", shards.get(1));
            runtime.register((short) 7, "unordered", shards.get(1), later);

            await().atMost(Duration.ofSeconds(10)).until(() -> later.pumped.get() > 0);
            assertThat(metrics.shardsOnDeadPumps.get()).as("the later shard is on a live pump").isEqualTo(1);
            assertThat(wakeup.cascadesTo(runtime.pumpFor((short) 7, "unordered", shards.get(1)).wakeup()))
                    .as("its wake-up reaches the pump that serves it").isTrue();
        } finally {
            runtime.stop();
        }
        assertThat(metrics.shardsOnDeadPumps.get()).isZero();
    }

    /**
     * A shard whose wake-up was created while its pump lived, and that is acquired again after the pump died, gets a
     * wake-up that reaches its new pump. The wake-up is cached per shard, so it used to keep signalling the dead one.
     */
    @Test
    void a_shard_acquired_again_after_its_pump_died_is_woken_on_its_new_pump() {
        var metrics = new ShardOwnerMetrics();
        var runtime = new ShardRuntime(listenableDataSource(), pumps(2), new ShardOwnerMetrics());
        try {
            var shards = shardsRoutedTo(0, 2, 2);
            var before = runtime.wakeupFor((short) 7, "unordered", shards.get(1));
            runtime.register((short) 7, "unordered", shards.get(0), new StubOwner((short) 7, shards.get(0), metrics, Behaviour.DIES));
            awaitDeath("pump-0");

            var after = runtime.wakeupFor((short) 7, "unordered", shards.get(1));

            assertThat(after).isNotSameAs(before);
            assertThat(after.cascadesTo(runtime.pumpFor((short) 7, "unordered", shards.get(1)).wakeup())).isTrue();
        } finally {
            runtime.stop();
        }
    }

    /**
     * With no live pump left the shard still goes to a dead one, and it is counted: that is the state
     * {@code shardsOnDeadPumps} exists to report.
     */
    @Test
    void a_shard_registered_when_every_pump_is_dead_is_counted_as_on_a_dead_pump() {
        var metrics = new ShardOwnerMetrics();
        var runtime = new ShardRuntime(listenableDataSource(), pumps(1), new ShardOwnerMetrics());
        try {
            runtime.register((short) 7, "unordered", 1, new StubOwner((short) 7, 1, metrics, Behaviour.DIES));
            awaitDeath("pump-0");

            runtime.register((short) 7, "unordered", 2, new StubOwner((short) 7, 2, metrics, Behaviour.SERVED));

            assertThat(metrics.shardsOnDeadPumps.get()).isEqualTo(2);
        } finally {
            runtime.stop();
        }
        assertThat(metrics.shardsOnDeadPumps.get()).isZero();
    }

    /**
     * A stop whose wait for the pumps times out still forgets what a pump that had already died was keeping counted: its
     * thread has ended, so its state is safe to read. It used to be forgotten only when every pump ended within the wait,
     * so one slow pump left the dead one's stalls counted for the rest of the process.
     */
    @Test
    void a_stop_that_outwaits_a_slow_pump_still_forgets_what_a_dead_pump_kept_counted() {
        var told           = new CopyOnWriteArrayList<String>();
        var metrics        = new ShardOwnerMetrics(observer(told));
        var runtime        = new ShardRuntime(listenableDataSource(), pumps(2), new ShardOwnerMetrics());
        var deadPumpShards = shardsRoutedTo(0, 2, 2);
        var slow           = new StubOwner((short) 7, shardsRoutedTo(1, 2, 1).getFirst(), new ShardOwnerMetrics(), Behaviour.BLOCKS);
        try {
            runtime.register((short) 7, "unordered", slow.shard(), slow);
            await().atMost(Duration.ofSeconds(10)).until(() -> slow.pumped.get() > 0);
            runtime.register((short) 7, "unordered", deadPumpShards.get(0),
                             new StubOwner((short) 7, deadPumpShards.get(0), metrics, Behaviour.TAKEOVER_FAILS));
            await().atMost(Duration.ofSeconds(10)).until(() -> metrics.shardsAwaitingTakeover.get() == 1);
            runtime.register((short) 7, "unordered", deadPumpShards.get(1),
                             new StubOwner((short) 7, deadPumpShards.get(1), metrics, Behaviour.DIES));
            awaitDeath("pump-0");

            runtime.shutdownStarting(() -> Duration.ofMillis(100));
            runtime.stop();

            assertThat(slow.returned.get()).as("premise: the slow pump outlived the stop's wait").isZero();
            assertThat(metrics.shardsAwaitingTakeover.get()).isZero();
            assertThat(metrics.shardsOnDeadPumps.get()).isZero();
            assertThat(told).containsExactly("UNORDERED/" + deadPumpShards.get(0) + "=true", "UNORDERED/" + deadPumpShards.get(0) + "=false");
        } finally {
            slow.released.countDown();
            runtime.stop();
        }
    }

    /**
     * A pump that outlives a stop's wait and leaves its loop after the runtime has started again exits in order. Each run
     * hands its pumps a running flag of its own; with the runtime's shared one, set again by the restart, the old pump read
     * it as set, took its exit for a death, logged one and counted its shards in {@code shardsOnDeadPumps}, where nothing
     * would ever uncount them: the restart had dropped the pump, so no later stop reached it.
     */
    @Test
    void a_pump_that_outlives_a_stop_is_not_taken_for_dead_after_the_runtime_restarts() {
        var slowMetrics = new ShardOwnerMetrics();
        var runtime     = new ShardRuntime(listenableDataSource(), pumps(1), new ShardOwnerMetrics());
        var slow        = new StubOwner((short) 7, 1, slowMetrics, Behaviour.BLOCKS);
        try {
            runtime.register((short) 7, "unordered", slow.shard(), slow);
            await().atMost(Duration.ofSeconds(10)).until(() -> slow.pumped.get() > 0);
            var slowPump = runtime.pumpFor((short) 7, "unordered", slow.shard());

            runtime.shutdownStarting(() -> Duration.ofMillis(100));
            runtime.stop();
            assertThat(slow.returned.get()).as("premise: the slow pump outlived the stop's wait").isZero();
            runtime.start();
            // The stub re-sets the interrupt flag the stop's shutdownNow() delivered, and that is what ends the old pump's loop.
            slow.released.countDown();
            await().atMost(Duration.ofSeconds(10)).until(slowPump::hasExited);

            assertThat(slow.returned.get()).as("premise: the slow pump left its pass after the restart").isPositive();
            assertThat(slowPump.isDead()).isFalse();
            assertThat(slowMetrics.shardsOnDeadPumps.get()).isZero();
            assertThat(loggedEvents().stream().map(ILoggingEvent::getFormattedMessage)).noneMatch(message -> message.contains("died"));
        } finally {
            slow.released.countDown();
            runtime.stop();
        }
    }

    /**
     * What the pump has logged so far, copied under the appender's own lock. {@code ListAppender.list} is a plain
     * {@code ArrayList} the pump thread appends to while the test reads it, so streaming it directly can throw
     * {@code ConcurrentModificationException}. {@code AppenderBase.doAppend} is {@code synchronized} on the appender, not
     * on the list, so the appender is the lock to take.
     */
    private List<ILoggingEvent> loggedEvents() {
        synchronized (logged) {
            return List.copyOf(logged.list);
        }
    }

    private void awaitDeath(String pumpName) {
        await().atMost(Duration.ofSeconds(10))
               .until(() -> loggedEvents().stream().anyMatch(event -> event.getLevel() == Level.ERROR
                       && event.getFormattedMessage().contains(pumpName + " died")));
    }

    /**
     * Shards of queue 7's unordered lane that {@code ShardRuntime} routes to pump {@code index} of {@code pumpCount} while
     * every pump lives: the same spread {@code ShardRuntime.pumpFor} uses.
     */
    private static List<Integer> shardsRoutedTo(int index, int pumpCount, int howMany) {
        var shards = new ArrayList<Integer>();
        for (var shard = 0; shards.size() < howMany; shard++) {
            if (Math.floorMod(Objects.hash((short) 7, "unordered", shard), pumpCount) == index) {
                shards.add(shard);
            }
        }
        return shards;
    }

    private static ShardOwnerSettings pumps(int pumpThreads) {
        var d = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(d.readBatchSize(), d.ackBatchSize(), d.ackFlushInterval(), d.chaseDelay(), d.holeExpiry(),
                                      d.sweepInterval(), d.maxHolesPerChase(), d.keyConcurrency(), d.pollBackstop(),
                                      d.maxSweepInterval(), pumpThreads, d.shedGrace(), d.leaseTtl(), d.watermarkCap());
    }

    private static ShardPump pump(AtomicBoolean running) {
        return pump(running, new ShardOwnerMetrics());
    }

    private static ShardPump pump(AtomicBoolean running, ShardOwnerMetrics pumpMetrics) {
        return pump(new ShardOwnedStorage(healthyDataSource(), PUMP_QUEUE_ID), running, pumpMetrics, new AtomicBoolean(false));
    }

    private static ShardPump pump(ShardOwnedStorage storage, AtomicBoolean running, ShardOwnerMetrics pumpMetrics,
                                  AtomicBoolean flushOnExit) {
        return new ShardPump(storage, ShardOwnerSettings.defaults(), pumpMetrics, running, flushOnExit, "pump-0", Optional::empty);
    }

    private static QueueObserver observer(List<String> told) {
        return new QueueObserver() {
            @Override
            public void takeoverStalled(MessageId.Lane lane, int shard, boolean stalled) {
                told.add(lane + "/" + shard + "=" + stalled);
            }
        };
    }

    /**
     * Hands out connections that are valid and do nothing: the stub owners never issue a statement.
     */
    private static DataSource healthyDataSource() {
        return stubDataSource(true);
    }

    /**
     * Like {@link #healthyDataSource()}, except that its connection reports itself broken when asked: what the pump asks
     * once a statement fails, to tell a failing statement from a lost connection.
     */
    private static DataSource invalidDataSource() {
        return stubDataSource(false);
    }

    private static DataSource stubDataSource(boolean valid) {
        var connection = (Connection) Proxy.newProxyInstance(ShardPumpExitTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                                                             (proxy, method, args) -> switch (method.getName()) {
                                                                 case "isValid" -> valid;
                                                                 case "close" -> null;
                                                                 case "isClosed" -> false;
                                                                 case "hashCode" -> System.identityHashCode(proxy);
                                                                 case "equals" -> proxy == args[0];
                                                                 case "toString" -> "stub connection";
                                                                 default -> throw new UnsupportedOperationException(method.getName());
                                                             });
        return (DataSource) Proxy.newProxyInstance(ShardPumpExitTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
                                                   (proxy, method, args) -> switch (method.getName()) {
                                                       case "getConnection" -> connection;
                                                       case "hashCode" -> System.identityHashCode(proxy);
                                                       case "equals" -> proxy == args[0];
                                                       case "toString" -> "stub data source";
                                                       default -> throw new UnsupportedOperationException(method.getName());
                                                   });
    }

    /**
     * Like {@link #healthyDataSource()}, except that its first {@code failures} connections are refused with a
     * non-critical {@code Error} ({@link StubOwner#PASS_FAILURE}), recording when ({@link System#nanoTime()}) in
     * {@code failedAt}: a pass that fails outside every per-shard guard.
     */
    private static DataSource failingDataSource(int failures, List<Long> failedAt) {
        var healthy      = healthyDataSource();
        var failuresLeft = new AtomicInteger(failures);
        return (DataSource) Proxy.newProxyInstance(ShardPumpExitTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
                                                   (proxy, method, args) -> switch (method.getName()) {
                                                       case "getConnection" -> {
                                                           if (failuresLeft.getAndDecrement() > 0) {
                                                               failedAt.add(System.nanoTime());
                                                               throw StubOwner.PASS_FAILURE;
                                                           }
                                                           yield healthy.getConnection();
                                                       }
                                                       case "hashCode" -> System.identityHashCode(proxy);
                                                       case "equals" -> proxy == args[0];
                                                       case "toString" -> "stub failing data source";
                                                       default -> throw new UnsupportedOperationException(method.getName());
                                                   });
    }

    /**
     * Like {@link #healthyDataSource()}, and also lets a {@link ShardRuntime}'s wake-up listener {@code LISTEN} and wait
     * for notifications that never come, so it neither fails nor logs a reconnect every 200 ms.
     */
    private static DataSource listenableDataSource() {
        var loader    = ShardPumpExitTest.class.getClassLoader();
        var statement = (Statement) Proxy.newProxyInstance(loader, new Class<?>[]{Statement.class},
                                                           (proxy, method, args) -> switch (method.getName()) {
                                                               case "execute" -> false;
                                                               case "close" -> null;
                                                               default -> throw new UnsupportedOperationException(method.getName());
                                                           });
        var notifications = (PGConnection) Proxy.newProxyInstance(loader, new Class<?>[]{PGConnection.class},
                                                                  (proxy, method, args) -> {
                                                                      if (!"getNotifications".equals(method.getName())) {
                                                                          throw new UnsupportedOperationException(method.getName());
                                                                      }
                                                                      try {
                                                                          Thread.sleep(args == null ? 200 : (Integer) args[0]);
                                                                      } catch (InterruptedException e) {
                                                                          Thread.currentThread().interrupt();
                                                                      }
                                                                      return null;
                                                                  });
        var connection = (Connection) Proxy.newProxyInstance(loader, new Class<?>[]{Connection.class},
                                                             (proxy, method, args) -> switch (method.getName()) {
                                                                 case "isValid" -> true;
                                                                 case "close" -> null;
                                                                 case "isClosed" -> false;
                                                                 case "createStatement" -> statement;
                                                                 case "unwrap" -> notifications;
                                                                 case "hashCode" -> System.identityHashCode(proxy);
                                                                 case "equals" -> proxy == args[0];
                                                                 case "toString" -> "stub listenable connection";
                                                                 default -> throw new UnsupportedOperationException(method.getName());
                                                             });
        return (DataSource) Proxy.newProxyInstance(loader, new Class<?>[]{DataSource.class},
                                                   (proxy, method, args) -> switch (method.getName()) {
                                                       case "getConnection" -> connection;
                                                       case "hashCode" -> System.identityHashCode(proxy);
                                                       case "equals" -> proxy == args[0];
                                                       case "toString" -> "stub listenable data source";
                                                       default -> throw new UnsupportedOperationException(method.getName());
                                                   });
    }

    private enum Behaviour {
        /**
         * Its takeover throws every time: a stalled shard.
         */
        TAKEOVER_FAILS,
        /**
         * Taken over, then throws a critical {@code Error} ({@link LinkageError}) from {@code needsAttention}, which the pump
         * does not survive.
         */
        DIES,
        /**
         * Taken over, then throws a non-critical {@code Error} from {@code leaseHeld} every time.
         */
        LEASE_CHECK_ERRORS,
        /**
         * Taken over, then throws a non-critical {@code Error} from {@code deliveryPermitted} every time.
         */
        PERMIT_ERRORS,
        /**
         * Taken over, then throws a non-critical {@code Error} from {@code needsAttention} every time.
         */
        ATTENTION_ERRORS,
        /**
         * Served, and its {@code flushOnStop} throws a non-critical {@code Error}.
         */
        FLUSH_ERRORS,
        /**
         * Served, and its {@code flushOnStop} throws an {@code SQLException} ({@link StubOwner#STATEMENT_FAILURE}).
         */
        FLUSH_STATEMENT_FAILS,
        /**
         * Served, and its {@code parkDeadlineMillis} throws a non-critical {@code Error} ({@link StubOwner#PASS_FAILURE})
         * the first time it is asked.
         */
        PARK_ERRORS_ONCE,
        /**
         * Served, and its {@code parkDeadlineMillis} throws a non-critical {@code Error} ({@link StubOwner#PASS_FAILURE})
         * every time.
         */
        PARK_ERRORS,
        /**
         * Served, and its {@code parkDeadlineMillis} runs {@code beforeFailing} (which stops the pump) and then throws a
         * non-critical {@code Error} ({@link StubOwner#PASS_FAILURE}).
         */
        PARK_ERRORS_AS_IT_STOPS,
        /**
         * Taken over and attentive, and its {@code pumpOnce} runs {@code beforeFailing} (which stops the pump) and then
         * throws an {@code SQLException} ({@link StubOwner#STATEMENT_FAILURE}).
         */
        DELIVERY_STATEMENT_FAILS_AS_IT_STOPS,
        /**
         * Taken over and served: always attentive, delivering nothing.
         */
        SERVED,
        /**
         * Served, and its first pass blocks, ignoring interrupts, until {@code released}: a pump that outlives a stop's wait.
         */
        BLOCKS,
        /**
         * Taken over and attentive, and its {@code pumpOnce} throws a non-critical {@code Error} every time. Asks to be
         * pumped again in 20 ms, like an owner with a retry falling due, so the pump keeps making passes.
         */
        DELIVERY_ERRORS,
        /**
         * Its takeover throws a non-critical {@code Error} every time.
         */
        TAKEOVER_ERRORS
    }

    private static final class StubOwner implements LeasedOwner {
        static final LinkageError   DEATH        = new LinkageError("pump bug");
        static final AssertionError PASS_FAILURE = new AssertionError("pass bug");
        static final AssertionError OWNER_ERROR  = new AssertionError("owner bug");
        static final SQLException   STATEMENT_FAILURE = new SQLException("statement refused");

        private final short             queueId;
        private final int               shard;
        private final ShardOwnerMetrics ownerMetrics;
        private final Behaviour         behaviour;
        final         AtomicInteger     pumped    = new AtomicInteger();
        /**
         * Passes whose {@code pumpOnce} has returned: for {@link Behaviour#BLOCKS}, only once {@code released}.
         */
        final         AtomicInteger     returned  = new AtomicInteger();
        final         AtomicInteger     takeovers = new AtomicInteger();
        final         AtomicInteger     flushes   = new AtomicInteger();
        final         CountDownLatch    released  = new CountDownLatch(1);
        final         AtomicInteger     parks     = new AtomicInteger();
        /**
         * Run just before the {@code *_AS_IT_STOPS} behaviours throw.
         */
        volatile      Runnable          beforeFailing = () -> {};

        StubOwner(short queueId, int shard, ShardOwnerMetrics ownerMetrics, Behaviour behaviour) {
            this.queueId = queueId;
            this.shard = shard;
            this.ownerMetrics = ownerMetrics;
            this.behaviour = behaviour;
        }

        @Override
        public int pumpOnce(Connection connection) throws SQLException {
            pumped.incrementAndGet();
            if (behaviour == Behaviour.DELIVERY_ERRORS) {
                throw OWNER_ERROR;
            }
            if (behaviour == Behaviour.DELIVERY_STATEMENT_FAILS_AS_IT_STOPS) {
                beforeFailing.run();
                throw STATEMENT_FAILURE;
            }
            if (behaviour == Behaviour.BLOCKS) {
                var interrupted = false;
                while (released.getCount() > 0) {
                    try {
                        released.await();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            returned.incrementAndGet();
            return 0;
        }

        @Override
        public boolean needsAttention() {
            return switch (behaviour) {
                case DIES -> throw DEATH;
                case ATTENTION_ERRORS -> throw OWNER_ERROR;
                case SERVED, BLOCKS, DELIVERY_ERRORS, FLUSH_ERRORS, FLUSH_STATEMENT_FAILS, LEASE_CHECK_ERRORS, PERMIT_ERRORS,
                     PARK_ERRORS_ONCE, PARK_ERRORS, PARK_ERRORS_AS_IT_STOPS, DELIVERY_STATEMENT_FAILS_AS_IT_STOPS -> true;
                case TAKEOVER_FAILS, TAKEOVER_ERRORS -> false;
            };
        }

        @Override
        public void onTakeover(Connection connection) {
            takeovers.incrementAndGet();
            if (behaviour == Behaviour.TAKEOVER_FAILS) {
                throw new IllegalStateException("takeover refused");
            }
            if (behaviour == Behaviour.TAKEOVER_ERRORS) {
                throw OWNER_ERROR;
            }
        }

        @Override
        public void flushOnStop(Connection connection) throws SQLException {
            flushes.incrementAndGet();
            if (behaviour == Behaviour.FLUSH_ERRORS) {
                throw OWNER_ERROR;
            }
            if (behaviour == Behaviour.FLUSH_STATEMENT_FAILS) {
                throw STATEMENT_FAILURE;
            }
        }

        @Override
        public long parkDeadlineMillis() {
            var asked = parks.incrementAndGet();
            return switch (behaviour) {
                case DELIVERY_ERRORS -> 20L;
                case PARK_ERRORS -> throw PASS_FAILURE;
                case PARK_ERRORS_ONCE -> {
                    if (asked == 1) {
                        throw PASS_FAILURE;
                    }
                    yield Long.MAX_VALUE;
                }
                case PARK_ERRORS_AS_IT_STOPS -> {
                    beforeFailing.run();
                    throw PASS_FAILURE;
                }
                default -> Long.MAX_VALUE;
            };
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
            return ownerMetrics;
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
            if (behaviour == Behaviour.LEASE_CHECK_ERRORS) {
                throw OWNER_ERROR;
            }
            return true;
        }

        @Override
        public boolean deliveryPermitted() {
            if (behaviour == Behaviour.PERMIT_ERRORS) {
                throw OWNER_ERROR;
            }
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
