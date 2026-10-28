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
 * {@link OwedMoveReleaseRefusals} counts the releases that left each owner out on a {@link RateLimitedCount} of its own
 * (the counting itself: {@link RateLimitedCountTest}).
 */
class OwedMoveReleaseRefusalsTest {
    @Test
    void each_owners_releases_left_out_are_counted_on_a_count_of_its_own() {
        var refusals = new OwedMoveReleaseRefusals();
        var first    = 1_000L;

        assertThat(refusals.leftOut(first)).as("the first release left out is a warning of one").isEqualTo(1);
        assertThat(refusals.leftOut(first + 1)).as("within the minute").isZero();
        assertThat(new OwedMoveReleaseRefusals().leftOut(first + 1)).as("another owner's first release left out").isEqualTo(1);
    }
}
