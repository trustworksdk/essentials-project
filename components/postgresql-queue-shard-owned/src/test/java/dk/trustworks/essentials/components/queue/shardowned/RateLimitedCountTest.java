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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RateLimitedCount}, which {@link InDoubtReleaseFailures} and {@link OwedMoveReleaseRefusals} count on: the first
 * event is a WARN, later ones within the minute are logged at DEBUG, and the next WARN once the minute has passed counts
 * every event since the last one.
 */
class RateLimitedCountTest {
    private static final long MINUTE = ErrorWindow.EVERY_NANOS;

    @Test
    void the_first_event_is_a_warning_of_one() {
        assertThat(new RateLimitedCount().record(1_000L)).isEqualTo(1);
    }

    @Test
    void events_within_the_minute_are_not_warnings() {
        var count = new RateLimitedCount();
        var first = 1_000L;
        count.record(first);

        assertThat(count.record(first + 1)).isZero();
        assertThat(count.record(first + MINUTE - 1)).isZero();
    }

    @Test
    void the_next_warning_once_the_minute_has_passed_counts_every_event_since_the_last_one() {
        var count = new RateLimitedCount();
        var first = 1_000L;
        count.record(first);
        count.record(first + 10);
        count.record(first + 20);

        var second = first + MINUTE;
        assertThat(count.record(second)).as("two at DEBUG, and this one").isEqualTo(3);
        assertThat(count.record(second + 1)).as("the minute runs from the second warning").isZero();
        assertThat(count.record(second + MINUTE)).as("counted from the second warning").isEqualTo(2);
    }
}
