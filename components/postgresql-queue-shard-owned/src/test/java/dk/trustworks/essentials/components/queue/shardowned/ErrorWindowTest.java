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

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link ErrorWindow}'s once-a-minute log window: open at first, whatever the clock reads, then
 * closed for {@link ErrorWindow#EVERY_NANOS} from the time it was taken, however often it is asked.
 */
class ErrorWindowTest {
    private static final long MINUTE = ErrorWindow.EVERY_NANOS;

    @Test
    void a_window_never_taken_is_taken_at_once_whatever_the_clock_reads() {
        assertThat(ErrorWindow.take(new AtomicLong(ErrorWindow.NEVER_TAKEN), 1_000L)).isTrue();
        assertThat(ErrorWindow.take(new AtomicLong(ErrorWindow.NEVER_TAKEN), Long.MIN_VALUE + 5)).isTrue();
    }

    @Test
    void a_window_taken_stays_closed_for_a_minute() {
        var window = new AtomicLong(ErrorWindow.NEVER_TAKEN);
        var first  = 5_000L;
        ErrorWindow.take(window, first);

        assertThat(ErrorWindow.take(window, first + 1)).isFalse();
        assertThat(ErrorWindow.take(window, first + MINUTE - 1)).isFalse();
    }

    @Test
    void a_window_is_taken_again_once_the_minute_has_passed_and_closes_for_a_minute_from_then() {
        var window = new AtomicLong(ErrorWindow.NEVER_TAKEN);
        var first  = 5_000L;
        ErrorWindow.take(window, first);

        var second = first + MINUTE;
        assertThat(ErrorWindow.take(window, second)).isTrue();
        assertThat(ErrorWindow.take(window, second + MINUTE - 1)).as("the minute runs from the second take").isFalse();
        assertThat(ErrorWindow.take(window, second + MINUTE)).isTrue();
    }

    @Test
    void a_nano_time_that_wraps_around_is_compared_by_difference() {
        var window = new AtomicLong(ErrorWindow.NEVER_TAKEN);
        var first  = Long.MAX_VALUE - 10;
        ErrorWindow.take(window, first);

        assertThat(ErrorWindow.take(window, first + 20)).as("wrapped past Long.MAX_VALUE, still inside the minute").isFalse();
        assertThat(ErrorWindow.take(window, first + MINUTE)).isTrue();
    }

    @Test
    void of_many_callers_taking_an_open_window_at_once_only_one_gets_it() throws Exception {
        var window  = new AtomicLong(ErrorWindow.NEVER_TAKEN);
        var callers = 16;
        var start   = new CountDownLatch(1);
        var granted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(callers)) {
            var futures = new ArrayList<Future<?>>();
            for (var caller = 0; caller < callers; caller++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    if (ErrorWindow.take(window, 42L)) {
                        granted.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(granted).hasValue(1);
    }

    @Test
    void reopening_leaves_a_window_taken_since_it_was_read_closed() {
        var window = new AtomicLong(ErrorWindow.NEVER_TAKEN);
        var seen   = window.get();
        ErrorWindow.take(window, 5_000L);

        ErrorWindow.reopen(window, seen);

        assertThat(ErrorWindow.take(window, 5_001L)).isFalse();
    }

    @Test
    void reopening_a_window_not_taken_since_it_was_read_opens_it() {
        var window = new AtomicLong(ErrorWindow.NEVER_TAKEN);
        ErrorWindow.take(window, 5_000L);
        var seen = window.get();

        ErrorWindow.reopen(window, seen);

        assertThat(ErrorWindow.take(window, 5_001L)).isTrue();
    }
}
