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
 * {@link OwedMoveReleaseRefusals}: the first release that leaves an owner out is logged at WARN, later ones within the
 * minute at DEBUG, and the next WARN once the minute has passed counts every release that left the owner out since the
 * last one.
 */
class OwedMoveReleaseRefusalsTest {
    private static final long MINUTE = ErrorWindow.EVERY_NANOS;

    @Test
    void the_first_release_left_out_is_a_warning_of_one() {
        assertThat(new OwedMoveReleaseRefusals().leftOut(1_000L)).isEqualTo(1);
    }

    @Test
    void releases_left_out_within_the_minute_are_not_warnings() {
        var refusals = new OwedMoveReleaseRefusals();
        var first    = 1_000L;
        refusals.leftOut(first);

        assertThat(refusals.leftOut(first + 1)).isZero();
        assertThat(refusals.leftOut(first + MINUTE - 1)).isZero();
    }

    @Test
    void the_next_warning_once_the_minute_has_passed_counts_every_release_left_out_since_the_last_one() {
        var refusals = new OwedMoveReleaseRefusals();
        var first    = 1_000L;
        refusals.leftOut(first);
        refusals.leftOut(first + 10);
        refusals.leftOut(first + 20);

        var second = first + MINUTE;
        assertThat(refusals.leftOut(second)).as("two at DEBUG, and this one").isEqualTo(3);
        assertThat(refusals.leftOut(second + 1)).as("the minute runs from the second warning").isZero();
        assertThat(refusals.leftOut(second + MINUTE)).as("counted from the second warning").isEqualTo(2);
    }
}
