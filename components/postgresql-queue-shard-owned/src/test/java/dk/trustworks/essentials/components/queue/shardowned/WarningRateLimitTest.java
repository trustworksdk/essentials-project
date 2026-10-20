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

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * The rate limit of the WARN {@code ShardOwnedQueue} logs for an ordered unit a stop kept while its handler runs on: once at
 * first, then at most once per interval, however often the heartbeat asks.
 */
class WarningRateLimitTest {
    private static final Duration MINUTE = Duration.ofMinutes(1);

    @Test
    void the_first_warning_is_let_through_at_once() {
        var limit = new WarningRateLimit(MINUTE);

        assertThat(limit.due(1_000L)).isTrue();
    }

    @Test
    void a_warning_inside_the_interval_after_the_last_one_is_held_back() {
        var limit = new WarningRateLimit(MINUTE);
        var first = 5_000L;
        limit.due(first);

        assertThat(limit.due(first + 1)).isFalse();
        assertThat(limit.due(first + MINUTE.toNanos() - 1)).isFalse();
    }

    @Test
    void the_next_warning_is_let_through_once_the_interval_has_passed_and_starts_a_new_interval() {
        var limit = new WarningRateLimit(MINUTE);
        var first = 5_000L;
        limit.due(first);

        var second = first + MINUTE.toNanos();
        assertThat(limit.due(second)).isTrue();
        assertThat(limit.due(second + MINUTE.toNanos() - 1)).as("the interval runs from the second warning").isFalse();
        assertThat(limit.due(second + MINUTE.toNanos())).isTrue();
    }

    @Test
    void a_nano_time_that_wraps_around_is_compared_by_difference() {
        var limit = new WarningRateLimit(MINUTE);
        var first = Long.MAX_VALUE - 10;
        limit.due(first);

        assertThat(limit.due(first + 20)).as("wrapped past Long.MAX_VALUE, still inside the interval").isFalse();
        assertThat(limit.due(first + MINUTE.toNanos())).isTrue();
    }

    @Test
    void of_many_callers_asking_at_once_only_one_gets_the_warning() throws Exception {
        var limit   = new WarningRateLimit(MINUTE);
        var callers = 16;
        var start   = new CountDownLatch(1);
        var granted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(callers)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (var caller = 0; caller < callers; caller++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    if (limit.due(42L)) {
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
    void the_interval_must_be_positive() {
        assertThatThrownBy(() -> new WarningRateLimit(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
