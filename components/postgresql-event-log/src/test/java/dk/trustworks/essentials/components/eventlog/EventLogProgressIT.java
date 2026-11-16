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

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * I7, plan §16, design phase 6 §6.1: <i>with no running writers, a committed event is delivered within the backstop
 * interval with every wake-up lost</i>. Three arms, subscriptions in both modes, each event measured from its commit's
 * return to the handler:
 * <ul>
 *     <li>tier 2 lost: one {@code EventLog}, {@code afterCommit} never called;</li>
 *     <li>listener killed: the writer is a second {@code EventLog} that never calls {@code afterCommit}; the subscribing
 *     one's listener drops every notification and its backend is terminated every 150 ms (reconnecting after 50 ms);</li>
 *     <li>{@code NOTIFY} lost: the writer calls {@code afterCommit}, but its notifier sends on a channel nobody listens on.</li>
 * </ul>
 * Must-fail: {@link WatermarkMutant#PARK_UNTIL_WOKEN} (tier 2 lost, {@code NOTIFY} lost) and
 * {@link WatermarkMutant#BACKSTOP_REARMED_ON_LISTENER_RESTART} (listener killed: restarts more frequent than the backstop
 * starve the reader).
 */
class EventLogProgressIT extends AbstractSubscriptionIT {
    static final Duration BACKSTOP = Duration.ofMillis(500);
    static final Duration SLACK    = Duration.ofMillis(250);
    static final int      EVENTS   = 3;

    /** Which wake-up is lost */
    enum Arm {
        TIER_2_LOST,
        LISTENER_KILLED,
        NOTIFY_LOST
    }

    private EventLog writer;

    @org.junit.jupiter.api.AfterEach
    void stopWriter() {
        if (writer != null) {
            writer.stop();
        }
    }

    @Test
    void with_every_wake_lost_each_event_is_delivered_within_the_backstop() throws Exception {
        assertThat(lateDeliveries(Arm.TIER_2_LOST, null)).isEmpty();
    }

    @Test
    void must_fail_the_loop_parks_until_woken() throws Exception {
        assertThat(lateDeliveries(Arm.TIER_2_LOST, WatermarkMutant.PARK_UNTIL_WOKEN)).isNotEmpty();
    }

    @Test
    void with_the_listener_killed_each_event_is_delivered_within_the_backstop() throws Exception {
        assertThat(lateDeliveries(Arm.LISTENER_KILLED, null)).isEmpty();
    }

    @Test
    void must_fail_each_listener_restart_rearms_the_backstop() throws Exception {
        assertThat(lateDeliveries(Arm.LISTENER_KILLED, WatermarkMutant.BACKSTOP_REARMED_ON_LISTENER_RESTART)).isNotEmpty();
    }

    @Test
    void with_notify_lost_each_event_is_delivered_within_the_backstop() throws Exception {
        assertThat(lateDeliveries(Arm.NOTIFY_LOST, null)).isEmpty();
    }

    @Test
    void must_fail_the_loop_parks_until_woken_with_notify_lost() throws Exception {
        assertThat(lateDeliveries(Arm.NOTIFY_LOST, WatermarkMutant.PARK_UNTIL_WOKEN)).isNotEmpty();
    }

    /**
     * @return a line per event a subscription did not receive within {@code BACKSTOP + SLACK} of its commit
     */
    private List<String> lateDeliveries(Arm arm, WatermarkMutant mutant) throws Exception {
        restartEventLog(dataSource, FAST.withBackstopInterval(BACKSTOP));
        var loopMutant = mutant == WatermarkMutant.PARK_UNTIL_WOKEN;
        var seams      = loopMutant ? new TestSeams(Set.of(mutant), TestSeams.Hooks.NONE, null) : TestSeams.NONE;
        if (mutant != null && !loopMutant) {
            eventLog.wakeSeams(new TestSeams(Set.of(mutant), TestSeams.Hooks.NONE, null));
        }
        var fresh      = new ConcurrentHashMap<Long, Long>();
        var contiguous = new ConcurrentHashMap<Long, Long>();
        subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key("fresh", TYPE, "node-1"), 1, event -> fresh.putIfAbsent(event.globalOrder(), System.nanoTime()), seams));
        subscribe(Subscription.SubscriptionSpec.inTransaction(new ProgressStore.Key("contiguous", TYPE, "node-1"), 1, Subscription.ConsistencyMode.CONTIGUOUS,
                                                              (page, connection) -> page.forEach(event -> contiguous.putIfAbsent(event.globalOrder(), System.nanoTime())),
                                                              TestSeams.NONE));
        awaitTrue(() -> reads.attaches.get() >= 2);
        if (arm != Arm.TIER_2_LOST) {
            writer = EventLog.builder().setDataSource(dataSource("")).setSettings(FAST.withBackstopInterval(BACKSTOP)).build();
            writer.start();
            writer.registerAggregateType(TYPE);
            writer.notifier().channelForTests = "el_wake_lost";
        }
        var killer = arm == Arm.LISTENER_KILLED ? killListenerRepeatedly() : null;
        try {
            return measure(arm, fresh, contiguous);
        } finally {
            if (killer != null) {
                killer.interrupt();
                killer.join();
            }
        }
    }

    /** The listener drops every notification; its backend is terminated every 150 ms, and it reconnects after 50 ms */
    private Thread killListenerRepeatedly() {
        var listener = eventLog.listener();
        listener.deafForTests = true;
        listener.reconnectDelayForTests = Duration.ofMillis(50);
        return Thread.ofPlatform().name("listener-killer").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    var pid = listener.backendPid();
                    if (pid != 0) {
                        execute("SELECT pg_terminate_backend(" + pid + ")");
                    }
                    Thread.sleep(150);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private List<String> measure(Arm arm, Map<Long, Long> fresh, Map<Long, Long> contiguous) throws Exception {
        var bound = BACKSTOP.plus(SLACK).toNanos();
        var late  = new ArrayList<String>();
        for (var i = 0; i < EVENTS; i++) {
            long globalOrder;
            if (arm == Arm.TIER_2_LOST) {
                globalOrder = commitAppend("a" + i, 1);
            } else {
                globalOrder = appendCommitted(writer, AppendRequest.withoutExpectedOrder(TYPE, "a" + i, events("E", 1))).getFirst().events().getFirst().globalOrder();
            }
            var committed = System.nanoTime();
            if (arm == Arm.NOTIFY_LOST) {
                writer.afterCommit(List.of(TYPE));
            }
            try {
                await().atMost(BACKSTOP.plus(SLACK).plusMillis(100)).until(() -> fresh.containsKey(globalOrder) && contiguous.containsKey(globalOrder));
            } catch (ConditionTimeoutException e) {
                // recorded below
            }
            for (var entry : Map.of("FRESH", fresh, "CONTIGUOUS", contiguous).entrySet()) {
                var received = entry.getValue().get(globalOrder);
                if (received == null || received - committed > bound) {
                    late.add(entry.getKey() + " " + globalOrder + (received == null ? " not delivered" : " after " + Duration.ofNanos(received - committed)));
                }
            }
        }
        return late;
    }
}
