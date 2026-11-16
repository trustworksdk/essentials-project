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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 5 §7.2, gate row I15: catch-up and bridge statements of every subscription on one database, across two
 * {@link EventLog}s with a {@code DataSource} each, are capped by one set of {@code catchUpPermits}. Deterministic: every
 * tested subscription reads through the scan-hold view, so each statement in flight is a backend blocked on the hold
 * and can be counted exactly. A permit is held for one statement only: subscriptions whose handlers block hold none.
 */
class EventLogCatchUpCapIT extends AbstractSubscriptionIT {
    private static final int              PERMITS   = 4;
    private static final int              PER_LOG   = 6;
    private static final int              EVENTS    = 500;
    private static final EventLogSettings SETTINGS  = FAST.withCatchUpPageSize(100).withCatchUpPermits(PERMITS);

    private EventLog second;

    @AfterEach
    void stopSecond() {
        if (second != null) {
            second.stop();
        }
    }

    @Test
    void catch_up_statements_across_two_event_logs_never_exceed_the_permits() throws Exception {
        var outcome = capped();
        assertThat(outcome.blockedInDatabase()).isEqualTo(PERMITS);
        assertThat(outcome.maxPermitsHeld()).isLessThanOrEqualTo(PERMITS);
        assertThat(outcome.allDelivered()).isTrue();
    }

    @Test
    void must_fail_reads_without_a_permit_exceed_the_cap() throws Exception {
        assertThat(capped(WatermarkMutant.NO_CATCH_UP_PERMIT).blockedInDatabase()).isGreaterThan(PERMITS);
    }

    @Test
    void must_fail_permits_per_event_log_exceed_the_cap() throws Exception {
        assertThat(capped(WatermarkMutant.PERMIT_PER_EVENT_LOG).blockedInDatabase()).isGreaterThan(PERMITS);
    }

    record Outcome(int blockedInDatabase, int maxPermitsHeld, boolean allDelivered) {
    }

    /**
     * {@value #PER_LOG} subscriptions per event log - half {@code FRESH} periodic, half {@code CONTIGUOUS} in-transaction,
     * every other one filtered - start {@value #EVENTS} events behind while the scan hold is armed
     */
    private Outcome capped(WatermarkMutant... mutants) throws Exception {
        restartEventLog(dataSource, SETTINGS);
        second = EventLog.builder().setDataSource(dataSource("")).setSettings(SETTINGS).build();
        second.start();
        second.registerAggregateType(TYPE);
        assertThat(second.catchUpPermits()).as("one set per database").isSameAs(eventLog.catchUpPermits());
        for (var log : List.of(eventLog, second)) {
            track(log.subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key("warm", TYPE, "node-" + System.identityHashCode(log)), 1, event -> {
            }, TestSeams.NONE)));
        }
        var orders = new ArrayList<Long>();
        for (var i = 0; i < EVENTS / 10; i++) {
            var result = appendCommitted(eventLog, AppendRequest.withoutExpectedOrder(TYPE, "a-" + i % 7, events(i % 2 == 0 ? "X" : "Y", 10)));
            result.getFirst().events().forEach(e -> orders.add(e.globalOrder()));
        }
        var head = orders.getLast();
        awaitTrue(() -> eventLog.reader(TYPE) != null && eventLog.reader(TYPE).state().watermark() != null && eventLog.reader(TYPE).state().watermark() >= head);

        var held      = new AtomicInteger();
        var maxHeld   = new AtomicInteger();
        var reached   = new AtomicInteger();
        var hooks = new TestSeams.Hooks() {
            @Override
            public void onPermit(String subscriber, boolean acquired) {
                if (acquired) {
                    maxHeld.accumulateAndGet(held.incrementAndGet(), Math::max);
                } else {
                    held.decrementAndGet();
                }
            }

            @Override
            public void beforeDeliveryRead(String subscriber, boolean deferred) {
                reached.incrementAndGet();
            }

            @Override
            public void beforeFilteredRead(String subscriber, long lower, long ceiling, Set<Short> typeIds) {
            }
        };
        var holder     = armScanHold();
        var delivered  = new ConcurrentHashMap<String, Set<Long>>();
        var expected   = new HashMap<String, Set<Long>>();
        var xs         = new TreeSet<Long>();
        for (var i = 0; i < orders.size(); i++) {
            if ((i / 10) % 2 == 0) {
                xs.add(orders.get(i));
            }
        }
        var n = 0;
        for (var log : List.of(eventLog, second)) {
            for (var i = 0; i < PER_LOG; i++) {
                var name     = "sub-" + n++;
                var filtered = i % 2 == 1;
                var key      = new ProgressStore.Key(name, TYPE, "node-1");
                var seams    = new TestSeams(Set.of(mutants), hooks, HELD);
                var set      = ConcurrentHashMap.<Long>newKeySet();
                delivered.put(name, set);
                expected.put(name, filtered ? xs : new TreeSet<>(orders));
                var spec = i < PER_LOG / 2 ? Subscription.SubscriptionSpec.periodic(key, 1, Subscription.ConsistencyMode.FRESH, event -> set.add(event.globalOrder()), seams)
                                           : Subscription.SubscriptionSpec.inTransaction(key, 1, Subscription.ConsistencyMode.CONTIGUOUS,
                                                                                         (page, connection) -> page.forEach(event -> set.add(event.globalOrder())), seams);
                track(log.subscribe(filtered ? spec.withFilter(EventTypeFilter.of("X")) : spec));
            }
        }
        var total = 2 * PER_LOG;
        await().atMost(WAIT).until(() -> reached.get() >= total && blocked() >= Math.min(total, PERMITS) || blocked() > PERMITS);
        // Every subscription has asked for its first read: those without a permit wait for one, in the JVM
        var blocked = blocked();
        for (var sample = 0; sample < 5 && blocked <= PERMITS; sample++) {
            Thread.sleep(20);
            blocked = Math.max(blocked, blocked());
        }
        releaseScan(holder);
        var all = new AtomicBoolean();
        try {
            await().atMost(WAIT).until(() -> expected.entrySet().stream().allMatch(e -> delivered.get(e.getKey()).containsAll(e.getValue())));
            all.set(true);
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            all.set(false);
        }
        return new Outcome(blocked, maxHeld.get(), all.get());
    }

    private int blocked() throws SQLException {
        return (int) queryLong("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND objid = " + SCAN_KEY);
    }

    // ---- a permit is held for one statement, never while the rows are applied ----

    @Test
    void a_subscription_whose_handler_blocks_holds_no_permit() throws Exception {
        assertThat(lastSubscriptionDelivered()).isTrue();
    }

    @Test
    void must_fail_a_permit_held_through_the_page_starves_the_others() throws Exception {
        assertThat(lastSubscriptionDelivered(WatermarkMutant.PERMIT_HELD_THROUGH_PAGE)).isFalse();
    }

    /**
     * {@value #PERMITS} subscriptions block in their handlers on their first event; a further one must still read and
     * deliver everything
     */
    private boolean lastSubscriptionDelivered(WatermarkMutant... mutants) throws Exception {
        restartEventLog(dataSource, SETTINGS);
        subscribe("warm", event -> {
        }, TestSeams.NONE, 1);
        var head = 0L;
        for (var i = 0; i < 5; i++) {
            head = appendCommitted(eventLog, AppendRequest.withoutExpectedOrder(TYPE, "a", events("X", 10))).getFirst().events().getLast().globalOrder();
        }
        var last = head;
        awaitTrue(() -> eventLog.reader(TYPE).state().watermark() != null && eventLog.reader(TYPE).state().watermark() >= last);
        var release = new CountDownLatch(1);
        var blocked = new CountDownLatch(PERMITS);
        var seams   = new TestSeams(Set.of(mutants), TestSeams.Hooks.NONE, null);
        for (var i = 0; i < PERMITS; i++) {
            subscribe("blocked-" + i, event -> {
                blocked.countDown();
                release.await();
            }, seams, 1);
        }
        assertThat(blocked.await(30, TimeUnit.SECONDS)).isTrue();
        var recorder = new Recorder();
        subscribe("last", recorder, seams, 1);
        var delivered = deliveredWithin(recorder, last);
        release.countDown();
        return delivered;
    }
}
