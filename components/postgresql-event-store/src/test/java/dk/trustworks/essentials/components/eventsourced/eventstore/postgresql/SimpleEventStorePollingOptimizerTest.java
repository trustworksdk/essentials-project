/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

public class SimpleEventStorePollingOptimizerTest {

    @Test
    void increasesDelayAndCapsAtMax() {
        var optimizer = new SimpleEventStorePollingOptimizer("test", 100, 40, 150);

        assertThat(optimizer.currentDelayMs()).isZero();

        optimizer.eventStorePollingReturnedNoEvents(); // 40
        assertThat(optimizer.currentDelayMs()).isEqualTo(40);

        optimizer.eventStorePollingReturnedNoEvents(); // 80
        assertThat(optimizer.currentDelayMs()).isEqualTo(80);

        optimizer.eventStorePollingReturnedNoEvents(); // 120
        assertThat(optimizer.currentDelayMs()).isEqualTo(120);

        optimizer.eventStorePollingReturnedNoEvents(); // 150 (cap)
        assertThat(optimizer.currentDelayMs()).isEqualTo(150);

        optimizer.eventStorePollingReturnedNoEvents(); // stays capped
        assertThat(optimizer.currentDelayMs()).isEqualTo(150);
    }

    @Test
    void resetsDelayAndCountersOnEvents() {
        var optimizer = new SimpleEventStorePollingOptimizer("test", 100, 50, 500);

        optimizer.eventStorePollingReturnedNoEvents(); // delay=50
        assertThat(optimizer.currentDelayMs()).isEqualTo(50);

        // Call shouldSkip once to bump the internal counter
        assertThat(optimizer.shouldSkipPolling()).isFalse(); // 100*1 <= 50 → false

        optimizer.eventStorePollingReturnedEvents();
        assertThat(optimizer.currentDelayMs()).isZero();
        assertThat(optimizer.shouldSkipPolling()).isFalse(); // delay=0 → never skip
    }

    @Test
    void shouldSkipTrueExactlyFloorDelayOverIntervalTimes_thenFalse() {
        var opt = new SimpleEventStorePollingOptimizer("test", 100, 250, 1000);

        // Single no-events → delay = 250
        opt.eventStorePollingReturnedNoEvents();
        assertThat(opt.currentDelayMs()).isEqualTo(250);

        // floor(250/100) = 2 true-skips, then false
        assertThat(opt.shouldSkipPolling()).isTrue();   // count=1 → 100 <= 250
        assertThat(opt.shouldSkipPolling()).isTrue();   // count=2 → 200 <= 250
        assertThat(opt.shouldSkipPolling()).isFalse();  // count=3 → 300 <= 250 ? no
    }

    @Test
    void noEventsDoesNotResetSkipCounter_monotonicUntilDelayCatchesUpAgain() {
        var opt = new SimpleEventStorePollingOptimizer("test", 100, 50, 1000);

        // Build up delay to 250
        opt.eventStorePollingReturnedNoEvents(); // 50
        opt.eventStorePollingReturnedNoEvents(); // 100
        opt.eventStorePollingReturnedNoEvents(); // 150
        opt.eventStorePollingReturnedNoEvents(); // 200
        opt.eventStorePollingReturnedNoEvents(); // 250
        assertThat(opt.currentDelayMs()).isEqualTo(250);

        // Two true skips, then false (count=3)
        assertThat(opt.shouldSkipPolling()).isTrue();   // count=1
        assertThat(opt.shouldSkipPolling()).isTrue();   // count=2
        assertThat(opt.shouldSkipPolling()).isFalse();  // count=3

        // Increase delay again, but skip counter is NOT reset
        opt.eventStorePollingReturnedNoEvents(); // delay=300, count still 3 so next call becomes count=4
        assertThat(opt.shouldSkipPolling()).isFalse();  // 100*4=400 <= 300 ? no

        opt.eventStorePollingReturnedNoEvents(); // delay=350
        assertThat(opt.shouldSkipPolling()).isFalse();  // 100*5=500 <= 350 ? no
    }

    @Test
    void constructorValidation() {
        assertThatThrownBy(() -> new SimpleEventStorePollingOptimizer("test", 0, 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SimpleEventStorePollingOptimizer("test", 10, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SimpleEventStorePollingOptimizer("test", 20, 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
