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
package dk.trustworks.essentials.components.eventlog;

import com.zaxxer.hikari.*;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.*;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Tier 1, design phase 6 §3, §6.3: the coalesced {@code NOTIFY} after commit and the per-event-log listener. "Another
 * JVM" is a second {@link EventLog} ({@code other}, the writer) on its own {@code DataSource}; the subscriptions run on
 * {@code eventLog}. Backstop 30 s, so only a wake can be fast. Must-fail variants:
 * {@link WatermarkMutant#RECONNECT_WITHOUT_LISTEN}, {@link WatermarkMutant#NO_WAKE_ON_LISTENER_RESTART},
 * {@link WatermarkMutant#STOCK_DRAIN}, {@link WatermarkMutant#NO_OWN_INSTANCE_FILTER}. Resource assertions: the listener
 * holds its connection only while a subscription exists, and gives it back clean.
 */
class EventLogWakeListenerIT extends AbstractSubscriptionIT {
    static final EventLogSettings LONG = FAST.withBackstopInterval(Duration.ofSeconds(30));
    /** Far below the backstop; a tier-1 wake takes about a millisecond */
    static final Duration         FAST_ENOUGH = Duration.ofSeconds(1);

    private EventLog other;

    @AfterEach
    void stopOther() {
        if (other != null) {
            other.stop();
        }
    }

    @Test
    void a_commit_in_another_event_log_wakes_this_one() throws Exception {
        var recorder = subscribed(TestSeams.NONE, false);
        var order    = appendOnOther("a");
        await().atMost(FAST_ENOUGH).until(() -> recorder.deliveredOrder(order));
    }

    @Test
    void the_query_poll_fallback_wakes_too() throws Exception {
        var recorder = subscribed(TestSeams.NONE, true);
        var order    = appendOnOther("a");
        await().atMost(FAST_ENOUGH).until(() -> recorder.deliveredOrder(order));
    }

    // ---- reconnect re-runs LISTEN ----

    @Test
    void a_reconnect_reruns_listen() throws Exception {
        assertThat(heardAfterReconnect(null)).isTrue();
    }

    @Test
    void must_fail_a_reconnect_does_not_rerun_listen() throws Exception {
        assertThat(heardAfterReconnect(WatermarkMutant.RECONNECT_WITHOUT_LISTEN)).isFalse();
    }

    /**
     * Terminates the listener's backend, waits for its reconnect, then appends five events with {@code afterCommit} in
     * the other event log.
     *
     * @return whether the listener heard at least one notification per send, and every event arrived within
     * {@link #FAST_ENOUGH}
     */
    private boolean heardAfterReconnect(WatermarkMutant mutant) throws Exception {
        var recorder = subscribed(seams(mutant, TestSeams.Hooks.NONE), false);
        var listener = eventLog.listener();
        listener.reconnectDelayForTests = Duration.ofMillis(50);
        terminateListenerBackend();
        await().atMost(WAIT).until(() -> listener.connects.get() >= 2);

        var heard  = listener.notifications.get();
        var sent   = other.notifier().sends.sum();
        var orders = new ArrayList<Long>();
        for (var i = 0; i < 5; i++) {
            orders.add(appendOnOther("e" + i));
        }
        try {
            await().atMost(FAST_ENOUGH).until(() -> orders.stream().allMatch(recorder::deliveredOrder));
        } catch (ConditionTimeoutException e) {
            return false;
        }
        var sends = other.notifier().sends.sum() - sent;
        return sends > 0 && listener.notifications.get() - heard >= sends;
    }

    // ---- a restart wakes every active type ----

    @Test
    void a_restart_wakes_every_active_type() throws Exception {
        assertThat(deliveredAfterRestart(null)).isTrue();
    }

    @Test
    void must_fail_a_restart_wakes_nothing() throws Exception {
        assertThat(deliveredAfterRestart(WatermarkMutant.NO_WAKE_ON_LISTENER_RESTART)).isFalse();
    }

    /**
     * Holds the listener before its reconnect (hook) while the other event log commits and sends: the notification is
     * lost. Then lets it reconnect.
     *
     * @return whether the commit arrived within {@link #FAST_ENOUGH} of the reconnect
     */
    private boolean deliveredAfterRestart(WatermarkMutant mutant) throws Exception {
        var held    = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var hooks = new TestSeams.Hooks() {
            @Override
            public void beforeListenerReconnect() throws Exception {
                held.countDown();
                release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
            }
        };
        var recorder = subscribed(seams(mutant, hooks), false);
        var listener = eventLog.listener();
        listener.reconnectDelayForTests = Duration.ofMillis(10);
        terminateListenerBackend();
        assertThat(held.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        var sent  = other.notifier().sends.sum();
        var order = appendOnOther("while-down");
        await().atMost(WAIT).until(() -> other.notifier().sends.sum() > sent);
        release.countDown();
        await().atMost(WAIT).until(() -> listener.connects.get() >= 2);
        try {
            await().atMost(FAST_ENOUGH).until(() -> recorder.deliveredOrder(order));
            return true;
        } catch (ConditionTimeoutException e) {
            return false;
        }
    }

    // ---- the drain under sustained traffic ----

    @Test
    void the_drain_keeps_up_with_sustained_traffic() throws Exception {
        assertThat(lateUnderTraffic(null)).isLessThanOrEqualTo(1);
    }

    @Test
    void must_fail_the_stock_drain_holds_wakes_inside_the_driver() throws Exception {
        assertThat(lateUnderTraffic(WatermarkMutant.STOCK_DRAIN)).isGreaterThan(1);
    }

    /**
     * P0.17's burst shape, shortened: noise threads keep the wake channel dense (notifications for no active type) while
     * the other event log commits a tagged event every 100 ms.
     *
     * @return how many tagged commits took more than {@link #TAGGED_BOUND} from commit to handler
     */
    private int lateUnderTraffic(WatermarkMutant mutant) throws Exception {
        var received = new ConcurrentHashMap<Long, Long>();
        restartEventLog(dataSource, LONG);
        eventLog.wakeSeams(seams(mutant, TestSeams.Hooks.NONE));
        subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key("tagged", TYPE, "node-1"), 1, event -> received.putIfAbsent(event.globalOrder(), System.nanoTime()),
                                                    TestSeams.NONE));
        awaitTrue(() -> reads.attaches.get() >= 1 && eventLog.listener().connects.get() >= 1);
        startOther();

        var stop  = new AtomicBoolean();
        var noise = new ArrayList<Thread>();
        for (var t = 0; t < NOISE_THREADS; t++) {
            var id = t;
            noise.add(Thread.ofPlatform().name("noise-" + t).start(() -> {
                try (var connection = dataSource.getConnection();
                     var statement = connection.prepareStatement("SELECT pg_notify('" + WakeNotifier.CHANNEL + "', 'noise000:n" + id + "-' || g) FROM generate_series(1, 10) g")) {
                    while (!stop.get()) {
                        statement.execute();
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        var committed = new LinkedHashMap<Long, Long>();
        try {
            Thread.sleep(200);
            for (var i = 0; i < TAGGED; i++) {
                var order = appendOnOther("tagged-" + i);
                committed.put(order, System.nanoTime());
                Thread.sleep(100);
            }
        } finally {
            stop.set(true);
            for (var thread : noise) {
                thread.join();
            }
        }
        try {
            await().atMost(FAST_ENOUGH).until(() -> received.keySet().containsAll(committed.keySet()));
        } catch (ConditionTimeoutException e) {
            // counted below
        }
        var late = 0;
        for (var entry : committed.entrySet()) {
            var at = received.get(entry.getKey());
            if (at == null || at - entry.getValue() > TAGGED_BOUND.toNanos()) {
                late++;
            }
        }
        return late;
    }

    static final int      NOISE_THREADS = 3;
    static final int      TAGGED        = 15;
    static final Duration TAGGED_BOUND  = Duration.ofMillis(100);

    // ---- own notifications dropped ----

    @Test
    void own_notifications_are_dropped() throws Exception {
        assertThat(readsPerCommit(null)).isLessThanOrEqualTo(1.25);
    }

    @Test
    void must_fail_own_notifications_wake_the_reader_again() throws Exception {
        assertThat(readsPerCommit(WatermarkMutant.NO_OWN_INSTANCE_FILTER)).isGreaterThan(1.25);
    }

    /**
     * One event log, tiers 1 and 2 on: each commit is followed by {@code afterCommit}, then waits for its delivery and
     * for the listener to hear its own notification.
     *
     * @return reads per commit once the loop is quiet
     */
    private double readsPerCommit(WatermarkMutant mutant) throws Exception {
        var recorder = subscribed(seams(mutant, TestSeams.Hooks.NONE), false);
        var listener = eventLog.listener();
        var commits  = 10;
        awaitQuiet();
        var before = reads.reads.get();
        for (var i = 0; i < commits; i++) {
            var heard = listener.notifications.get();
            var order = commitAppend("own-" + i, 1);
            eventLog.afterCommit(List.of(TYPE));
            await().atMost(WAIT).pollInterval(Duration.ofMillis(1)).until(() -> recorder.deliveredOrder(order) && listener.notifications.get() > heard);
        }
        awaitQuiet();
        return (reads.reads.get() - before) / (double) commits;
    }

    /** Waits until no read happened for 100 ms */
    private void awaitQuiet() throws InterruptedException {
        var last = -1L;
        while (reads.reads.get() != last) {
            last = reads.reads.get();
            Thread.sleep(100);
        }
    }

    // ---- resources ----

    @Test
    void the_listener_holds_a_connection_only_while_a_subscription_exists() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setPoolName("eventlog-listener-it");
        try (var pool = new HikariDataSource(config)) {
            restartEventLog(pool, LONG);
            assertThat(listenerBackends()).isZero();
            commitAppend("append-only", 1);
            eventLog.afterCommit(List.of(TYPE));
            await().atMost(WAIT).until(() -> eventLog.notifier().sends.sum() >= 1 && pool.getHikariPoolMXBean().getActiveConnections() == 0);
            assertThat(listenerBackends()).as("appending alone holds no listener connection").isZero();
            assertThat(eventLog.listener().isRunning()).isFalse();

            var subscription = subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key("sub", TYPE, "node-1"), 1, new Recorder(), TestSeams.NONE));
            await().atMost(WAIT).until(() -> listenerBackends() == 1);

            subscription.stop();
            assertThat(eventLog.readerLoop().awaitStopped(WAIT)).isTrue();
            assertThat(eventLog.listener().awaitStopped(Duration.ofSeconds(5))).as("the kick ends the listener at once").isTrue();
            await().atMost(WAIT).until(() -> pool.getHikariPoolMXBean().getActiveConnections() == 0);
            assertThat(listenerBackends()).isZero();

            // Every pooled session: no LISTEN left behind
            var borrowed = new ArrayList<java.sql.Connection>();
            try {
                var total = pool.getHikariPoolMXBean().getTotalConnections();
                for (var i = 0; i < total; i++) {
                    var connection = pool.getConnection();
                    borrowed.add(connection);
                    assertThat(queryLong(connection, "SELECT count(*) FROM pg_listening_channels()")).isZero();
                }
            } finally {
                for (var connection : borrowed) {
                    connection.close();
                }
            }
            eventLog.stop();
        }
    }

    private long listenerBackends() throws SQLException {
        return queryLong("SELECT count(*) FROM pg_stat_activity WHERE application_name = '" + WakeListener.APPLICATION_NAME + "'");
    }

    // ---- harness ----

    private static TestSeams seams(WatermarkMutant mutant, TestSeams.Hooks hooks) {
        return new TestSeams(mutant == null ? Set.of() : Set.of(mutant), hooks, null);
    }

    /** {@code eventLog} with the 30 s backstop and {@code seams} on its listener, one attached subscription; {@code other} started */
    private Recorder subscribed(TestSeams seams, boolean queryPoll) throws Exception {
        restartEventLog(dataSource, LONG);
        eventLog.wakeSeams(seams);
        eventLog.listener().queryPollForTests = queryPoll;
        var recorder = new Recorder();
        subscribe("sub", recorder, TestSeams.NONE);
        awaitTrue(() -> reads.attaches.get() >= 1 && eventLog.listener().connects.get() >= 1);
        startOther();
        return recorder;
    }

    private void startOther() throws SQLException {
        other = EventLog.builder().setDataSource(dataSource("")).setSettings(LONG).build();
        other.start();
        other.registerAggregateType(TYPE);
    }

    /** Appends one event in the other event log, commits, and calls its {@code afterCommit}; returns its global order */
    private long appendOnOther(String aggregateId) throws SQLException {
        var order = appendCommitted(other, AppendRequest.withoutExpectedOrder(TYPE, aggregateId, events("E", 1))).getFirst().events().getFirst().globalOrder();
        other.afterCommit(List.of(TYPE));
        return order;
    }

    private void terminateListenerBackend() throws SQLException {
        var pid = eventLog.listener().backendPid();
        assertThat(pid).isNotZero();
        execute("SELECT pg_terminate_backend(" + pid + ")");
    }
}
