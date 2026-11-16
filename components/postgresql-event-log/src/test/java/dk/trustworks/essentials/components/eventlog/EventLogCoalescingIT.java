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

import org.junit.jupiter.api.*;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Wake coalescing, design phase 6 §10.8: a wake inside {@link EventLogSettings#wokenReadMin()} of the type's last read
 * defers the read to the interval's end, and a type committed inside {@link EventLogSettings#notifySendMin()} of the
 * notifier's last send goes out with the next send. Neither is ever dropped, and each costs at most its interval of
 * latency. Backstop 30 s, so only a wake can be fast. The intervals are 300 ms, far above a read or a send, so the
 * deferral is visible. Must-fail variants: {@link WatermarkMutant#WOKEN_READ_DROPPED_INSIDE_INTERVAL},
 * {@link WatermarkMutant#NOTIFY_DROPPED_INSIDE_INTERVAL}.
 */
class EventLogCoalescingIT extends AbstractSubscriptionIT {
    static final Duration INTERVAL = Duration.ofMillis(300);
    /** Delivery bound past the interval: one read, one handler call, scheduling */
    static final Duration MARGIN   = Duration.ofMillis(500);
    static final int      BURST    = 5;

    private EventLog other;

    @AfterEach
    void stopOther() {
        if (other != null) {
            other.stop();
        }
    }

    // ---- woken-read coalescing (tier 2, the reader loop) ----

    @Test
    void wakes_inside_the_read_interval_are_served_by_one_deferred_read() throws Exception {
        var result = burstOfWakes(null);
        assertThat(result.delivered()).as("every commit of the burst delivered within the interval and a margin").isTrue();
        assertThat(result.reads()).as("one deferred read serves the burst (at most one more if a read had just started)").isLessThanOrEqualTo(2);
    }

    @Test
    void must_fail_a_wake_inside_the_read_interval_is_dropped() throws Exception {
        assertThat(burstOfWakes(WatermarkMutant.WOKEN_READ_DROPPED_INSIDE_INTERVAL).delivered()).isFalse();
    }

    record Burst(boolean delivered, long reads) {
    }

    /**
     * One event log, listener off; a first commit is read at once, then {@link #BURST} commits with {@code afterCommit}
     * follow back to back, all inside the interval of that read
     */
    private Burst burstOfWakes(WatermarkMutant mutant) throws Exception {
        restartEventLog(dataSource, FAST.withBackstopInterval(Duration.ofSeconds(30)).withCrossJvmWakeUp(false).withWokenReadMin(INTERVAL));
        var at = new ConcurrentHashMap<Long, Long>();
        subscribe("sub", event -> at.putIfAbsent(event.globalOrder(), System.nanoTime()), new TestSeams(mutant == null ? Set.of() : Set.of(mutant), TestSeams.Hooks.NONE, null), 1);
        awaitTrue(() -> reads.attaches.get() >= 1);
        Thread.sleep(INTERVAL.toMillis());
        var first = commitAppend("first", 1);
        eventLog.afterCommit(List.of(TYPE));
        awaitTrue(() -> at.containsKey(first));

        var before    = reads.reads.get();
        var orders    = new ArrayList<Long>();
        var committed = new HashMap<Long, Long>();
        for (var i = 0; i < BURST; i++) {
            var order = commitAppend("burst" + i, 1);
            committed.put(order, System.nanoTime());
            orders.add(order);
            eventLog.afterCommit(List.of(TYPE));
        }
        try {
            await().atMost(INTERVAL.plus(MARGIN)).until(() -> orders.stream().allMatch(at::containsKey));
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            return new Burst(false, reads.reads.get() - before);
        }
        var bounded = orders.stream().allMatch(order -> at.get(order) - committed.get(order) <= INTERVAL.plus(MARGIN).toNanos());
        return new Burst(bounded, reads.reads.get() - before);
    }

    // ---- send coalescing (tier 1, the notifier) ----

    @Test
    void commits_inside_the_send_interval_go_out_in_one_later_send() throws Exception {
        var result = burstOfSends(null);
        assertThat(result.delivered()).as("every commit of the burst delivered to the other event log within the interval and a margin").isTrue();
        assertThat(result.reads()).as("sends for the burst: one, after the interval").isEqualTo(1);
    }

    @Test
    void must_fail_a_commit_inside_the_send_interval_is_not_sent() throws Exception {
        assertThat(burstOfSends(WatermarkMutant.NOTIFY_DROPPED_INSIDE_INTERVAL).delivered()).isFalse();
    }

    /**
     * The subscription runs on {@code eventLog} (listener on, no read coalescing); {@code other} appends with
     * {@code afterCommit} and a 300 ms send interval: one commit is sent at once, then {@link #BURST} commits follow
     * inside the interval of that send. {@link Burst#reads()} counts the notifier's sends for the burst
     */
    private Burst burstOfSends(WatermarkMutant mutant) throws Exception {
        var settings = FAST.withBackstopInterval(Duration.ofSeconds(30)).withWokenReadMin(Duration.ZERO);
        restartEventLog(dataSource, settings);
        var recorder = new Recorder();
        subscribe("sub", recorder, TestSeams.NONE);
        awaitTrue(() -> reads.attaches.get() >= 1 && eventLog.listener().connects.get() >= 1);
        other = EventLog.builder().setDataSource(dataSource("")).setSettings(settings.withNotifySendMin(INTERVAL)).build();
        other.start();
        other.registerAggregateType(TYPE);
        other.wakeSeams(new TestSeams(mutant == null ? Set.of() : Set.of(mutant), TestSeams.Hooks.NONE, null));

        var first = appendOnOther("first");
        await().atMost(INTERVAL.plus(MARGIN)).until(() -> recorder.deliveredOrder(first));
        var sent   = other.notifier().sends.sum();
        var orders = new ArrayList<Long>();
        var start  = System.nanoTime();
        for (var i = 0; i < BURST; i++) {
            orders.add(appendOnOther("burst" + i));
        }
        try {
            await().atMost(INTERVAL.plus(MARGIN)).until(() -> orders.stream().allMatch(recorder::deliveredOrder));
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            return new Burst(false, other.notifier().sends.sum() - sent);
        }
        var took = System.nanoTime() - start;
        return new Burst(took <= INTERVAL.plus(MARGIN).toNanos(), other.notifier().sends.sum() - sent);
    }

    private long appendOnOther(String aggregateId) throws SQLException {
        var order = appendCommitted(other, AppendRequest.withoutExpectedOrder(TYPE, aggregateId, events("E", 1))).getFirst().events().getFirst().globalOrder();
        other.afterCommit(List.of(TYPE));
        return order;
    }
}
