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

package dk.trustworks.essentials.components.eventlog.migration;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The online copy's throttle, on a fake clock
 */
class ThrottleTest {
    private long             now;
    private final List<Long> sleeps = new ArrayList<>();

    private Throttle throttle(long maxEventsPerSecond) {
        var throttle = new Throttle(maxEventsPerSecond, () -> now, nanos -> {
            sleeps.add(nanos);
            now += nanos;
        });
        throttle.start();
        return throttle;
    }

    @Test
    void unthrottled_never_sleeps() {
        var throttle = throttle(0);
        throttle.afterBatch(1_000_000);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void a_batch_faster_than_the_rate_sleeps_for_the_rest_of_its_share() {
        var throttle = throttle(10_000);
        now += 100_000_000; // the 10 000-row batch took 0.1 s, its share is 1 s
        throttle.afterBatch(10_000);
        assertThat(sleeps).containsExactly(900_000_000L);
        now += 2_000_000_000L; // the next batch was slower than its share: no sleep
        throttle.afterBatch(10_000);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void the_rate_is_held_over_the_whole_epoch() {
        var throttle = throttle(20_000);
        for (var i = 0; i < 10; i++) {
            now += 50_000_000; // each 10 000-row batch takes 0.05 s
            throttle.afterBatch(10_000);
        }
        assertThat(now).isEqualTo(5_000_000_000L); // 100 000 rows at 20 000/s
    }
}
