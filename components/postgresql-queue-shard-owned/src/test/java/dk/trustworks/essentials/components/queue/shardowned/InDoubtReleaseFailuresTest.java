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
 * {@link InDoubtReleaseFailures} counts each owner's failed releases on a {@link RateLimitedCount} of its own (the
 * counting itself: {@link RateLimitedCountTest}).
 */
class InDoubtReleaseFailuresTest {
    @Test
    void each_owners_failures_are_counted_on_a_count_of_its_own() {
        var failures = new InDoubtReleaseFailures();
        var first    = 1_000L;

        assertThat(failures.failed(first)).as("the first failure is a warning of one attempt").isEqualTo(1);
        assertThat(failures.failed(first + 1)).as("within the minute").isZero();
        assertThat(new InDoubtReleaseFailures().failed(first + 1)).as("another owner's first failure").isEqualTo(1);
    }
}
