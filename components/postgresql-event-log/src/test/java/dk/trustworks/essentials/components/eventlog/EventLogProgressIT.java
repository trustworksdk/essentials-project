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
 * interval with every wake-up lost</i>. Unit A's arm: one {@code EventLog}, {@code afterCommit} never called (tier 2
 * lost); the cross-JVM arms join with tier 1. Subscriptions in both modes, each event measured from its commit's
 * return to the handler. Must-fail: {@link WatermarkMutant#PARK_UNTIL_WOKEN}.
 */
class EventLogProgressIT extends AbstractSubscriptionIT {
    static final Duration BACKSTOP = Duration.ofMillis(500);
    static final Duration SLACK    = Duration.ofMillis(250);
    static final int      EVENTS   = 4;

    @Test
    void with_every_wake_lost_each_event_is_delivered_within_the_backstop() throws Exception {
        assertThat(lateDeliveries(null)).isEmpty();
    }

    @Test
    void must_fail_the_loop_parks_until_woken() throws Exception {
        assertThat(lateDeliveries(WatermarkMutant.PARK_UNTIL_WOKEN)).isNotEmpty();
    }

    /**
     * @return a line per event a subscription did not receive within {@code BACKSTOP + SLACK} of its commit
     */
    private List<String> lateDeliveries(WatermarkMutant mutant) throws Exception {
        restartEventLog(dataSource, FAST.withBackstopInterval(BACKSTOP));
        var seams      = mutant == null ? TestSeams.NONE : new TestSeams(Set.of(mutant), TestSeams.Hooks.NONE, null);
        var fresh      = new ConcurrentHashMap<Long, Long>();
        var contiguous = new ConcurrentHashMap<Long, Long>();
        subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key("fresh", TYPE, "node-1"), 1, event -> fresh.putIfAbsent(event.globalOrder(), System.nanoTime()), seams));
        subscribe(Subscription.SubscriptionSpec.inTransaction(new ProgressStore.Key("contiguous", TYPE, "node-1"), 1, Subscription.ConsistencyMode.CONTIGUOUS,
                                                              (page, connection) -> page.forEach(event -> contiguous.putIfAbsent(event.globalOrder(), System.nanoTime())),
                                                              TestSeams.NONE));
        awaitTrue(() -> reads.attaches.get() >= 2);

        var bound = BACKSTOP.plus(SLACK).toNanos();
        var late  = new ArrayList<String>();
        for (var i = 0; i < EVENTS; i++) {
            var globalOrder = commitAppend("a" + i, 1);
            var committed   = System.nanoTime();
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
