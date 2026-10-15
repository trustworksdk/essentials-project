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
     * Anything a retry could cure no longer ends the pump. An {@code AssertionError} from {@code needsAttention} is outside
     * every per-shard guard; it used to end the thread and leave every shard on the pump leased and served by nobody.
     */
    @Test
    void a_pump_whose_pass_throws_a_non_critical_error_reconnects_and_keeps_serving_its_shards() throws InterruptedException {
        var metrics     = new ShardOwnerMetrics();
        var pumpMetrics = new ShardOwnerMetrics();
        var running     = new AtomicBoolean(true);
        var pump        = pump(running, pumpMetrics);
        var failing     = new StubOwner((short) 7, 1, metrics, Behaviour.FAILS);
        var beside      = new StubOwner((short) 7, 2, metrics, Behaviour.SERVED);
        pump.add(failing);
        pump.add(beside);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> beside.pumped.get() > 0 && failing.pumped.get() > 0);

            assertThat(thread.isAlive()).as("the pump is still running").isTrue();
            assertThat(pump.isDead()).isFalse();
            assertThat(metrics.pumpLoopFailures.sum()).as("counted on the queue's metrics").isEqualTo(1);
            assertThat(pumpMetrics.pumpLoopFailures.sum()).as("and on the runtime's").isEqualTo(1);
            assertThat(failing.takeovers.get()).as("the reconnect ran the takeover again").isEqualTo(2);
            assertThat(metrics.shardsOnDeadPumps.get()).isZero();
            var errors = loggedEvents().stream().filter(event -> event.getLevel() == Level.ERROR).toList();
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().getFormattedMessage()).contains("pump-0").contains("pumpLoopFailures").doesNotContain("died");
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
        var metrics  = new ShardOwnerMetrics();
        var running  = new AtomicBoolean(true);
        var pump     = pump(running);
        var failing  = new StubOwner((short) 7, 1, metrics, Behaviour.FAILS, failures);
        pump.add(failing);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            // The INFO follows the pass that completed, so it is what to wait for.
            await().atMost(Duration.ofSeconds(10))
                   .until(() -> loggedEvents().stream().anyMatch(event -> event.getLevel() == Level.INFO));

            assertThat(failing.pumped.get()).as("served once the failure cleared").isPositive();
            assertThat(metrics.pumpLoopFailures.sum()).isEqualTo(failures);
            assertThat(failing.failedAt).hasSize(failures);
            assertThat(Duration.ofNanos(failing.failedAt.getLast() - failing.failedAt.getFirst()))
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
        return new ShardPump(new ShardOwnedStorage(healthyDataSource(), PUMP_QUEUE_ID), ShardOwnerSettings.defaults(),
                             pumpMetrics, running, new AtomicBoolean(false), "pump-0", Optional::empty);
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
        var connection = (Connection) Proxy.newProxyInstance(ShardPumpExitTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                                                             (proxy, method, args) -> switch (method.getName()) {
                                                                 case "isValid" -> true;
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
         * Taken over, then throws a non-critical {@code Error} from {@code needsAttention} on its first {@code failures}
         * calls, outside every per-shard guard; served from then on.
         */
        FAILS,
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

        private final short             queueId;
        private final int               shard;
        private final ShardOwnerMetrics ownerMetrics;
        private final Behaviour         behaviour;
        private final AtomicInteger     failuresLeft;
        final         AtomicInteger     pumped    = new AtomicInteger();
        /**
         * Passes whose {@code pumpOnce} has returned: for {@link Behaviour#BLOCKS}, only once {@code released}.
         */
        final         AtomicInteger     returned  = new AtomicInteger();
        final         AtomicInteger     takeovers = new AtomicInteger();
        /**
         * When each {@link Behaviour#FAILS} failure was thrown ({@link System#nanoTime()}).
         */
        final         List<Long>        failedAt  = new CopyOnWriteArrayList<>();
        final         CountDownLatch    released  = new CountDownLatch(1);

        StubOwner(short queueId, int shard, ShardOwnerMetrics ownerMetrics, Behaviour behaviour) {
            this(queueId, shard, ownerMetrics, behaviour, 1);
        }

        StubOwner(short queueId, int shard, ShardOwnerMetrics ownerMetrics, Behaviour behaviour, int failures) {
            this.queueId = queueId;
            this.shard = shard;
            this.ownerMetrics = ownerMetrics;
            this.behaviour = behaviour;
            this.failuresLeft = new AtomicInteger(failures);
        }

        @Override
        public int pumpOnce(Connection connection) {
            pumped.incrementAndGet();
            if (behaviour == Behaviour.DELIVERY_ERRORS) {
                throw OWNER_ERROR;
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
                case FAILS -> {
                    if (failuresLeft.getAndDecrement() > 0) {
                        failedAt.add(System.nanoTime());
                        throw PASS_FAILURE;
                    }
                    yield true;
                }
                case SERVED, BLOCKS, DELIVERY_ERRORS -> true;
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
        public void flushOnStop(Connection connection) {
        }

        @Override
        public long parkDeadlineMillis() {
            return behaviour == Behaviour.DELIVERY_ERRORS ? 20L : Long.MAX_VALUE;
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
