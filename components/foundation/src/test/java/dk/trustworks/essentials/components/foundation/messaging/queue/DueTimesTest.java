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
package dk.trustworks.essentials.components.foundation.messaging.queue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The due-time tracking shared by both polling optimizers: the bound holds without walking the set, every removal
 * keeps the counter in step, and {@link DueTimes#hasDueBy(long)} only answers for a due time at or before the moment
 * asked about.
 */
class DueTimesTest {
    private static final long BASE = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    private static Instant at(long offsetMs) {
        return Instant.ofEpochMilli(BASE + offsetMs);
    }

    @Test
    void scheduling_past_the_bound_drops_the_latest_due_times() {
        var dueTimes = new DueTimes(3);
        dueTimes.schedule(at(40));
        dueTimes.schedule(at(10));
        dueTimes.schedule(at(30));
        dueTimes.schedule(at(20));

        assertThat(dueTimes.trackedCount()).isEqualTo(3);
        // 40 was the latest and is the one dropped
        dueTimes.forgetSeenBy(at(31));
        assertThat(dueTimes.trackedCount()).isZero();
        assertThat(dueTimes.hasDueBy(BASE + 1_000)).isFalse();
    }

    @Test
    void scheduling_the_same_due_time_twice_counts_it_once() {
        var dueTimes = new DueTimes(3);
        dueTimes.schedule(at(10));
        dueTimes.schedule(at(10));

        assertThat(dueTimes.trackedCount()).isEqualTo(1);
    }

    @Test
    void forgetting_decrements_the_counter_for_each_due_time_removed() {
        var dueTimes = new DueTimes(10);
        for (long i = 1; i <= 5; i++) {
            dueTimes.schedule(at(i * 10));
        }

        dueTimes.forgetSeenBy(at(30));

        // 10 and 20 are strictly before the claim; 30 is in the claim's millisecond and is kept
        assertThat(dueTimes.trackedCount()).isEqualTo(3);
        assertThat(dueTimes.hasDueBy(BASE + 29)).isFalse();
        assertThat(dueTimes.hasDueBy(BASE + 30)).isTrue();

        // Once forgotten, the slots are free again: the bound counts what is tracked, not what was ever scheduled
        for (long i = 6; i <= 12; i++) {
            dueTimes.schedule(at(i * 10));
        }
        assertThat(dueTimes.trackedCount()).isEqualTo(10);
    }

    @Test
    void forgetting_with_grace_keeps_due_times_newer_than_the_grace() {
        var dueTimes   = new DueTimes(10);
        var now        = Instant.now();
        dueTimes.schedule(now.minusMillis(DueTimes.DUE_TIME_GRACE_MS * 4));
        dueTimes.schedule(now.plusSeconds(60));

        dueTimes.forgetSeenByNowMinusGrace();

        assertThat(dueTimes.trackedCount()).isEqualTo(1);
        assertThat(dueTimes.hasDueBy(now.toEpochMilli())).isFalse();
        assertThat(dueTimes.hasDueBy(now.plusSeconds(60).toEpochMilli())).isTrue();
    }

    @Test
    void has_due_by_is_true_only_once_the_earliest_due_time_is_reached() {
        var dueTimes = new DueTimes(10);
        assertThat(dueTimes.hasDueBy(Long.MAX_VALUE)).isFalse();

        dueTimes.schedule(at(100));
        dueTimes.schedule(at(50));

        assertThat(dueTimes.hasDueBy(BASE + 49)).isFalse();
        assertThat(dueTimes.hasDueBy(BASE + 50)).isTrue();
        assertThat(dueTimes.hasDueBy(BASE + 75)).isTrue();
    }

    @Test
    void the_bound_holds_under_concurrent_scheduling_and_forgetting() throws Exception {
        int bound    = 50;
        int threads  = 8;
        var dueTimes = new DueTimes(bound);
        var executor = Executors.newFixedThreadPool(threads);
        var start    = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures.add(executor.submit(() -> {
                    start.await();
                    var random = ThreadLocalRandom.current();
                    for (int i = 0; i < 20_000; i++) {
                        dueTimes.schedule(at(thread * 1_000_000L + i));
                        if (random.nextInt(100) == 0) {
                            dueTimes.forgetSeenBy(at(random.nextLong(threads * 1_000_000L)));
                        }
                        // The counter may lag the set by the calls in flight, never by more
                        assertThat(dueTimes.trackedCount()).isLessThanOrEqualTo(bound + threads);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        // Quiescent: the counter matches the set exactly and is within the bound
        assertThat(dueTimes.trackedCount()).isBetween(0, bound);
        dueTimes.forgetSeenBy(Instant.ofEpochMilli(Long.MAX_VALUE));
        assertThat(dueTimes.trackedCount()).isZero();
        assertThat(dueTimes.hasDueBy(Long.MAX_VALUE)).isFalse();
    }
}
