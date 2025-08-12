/*
 *  Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ReactivePollingBackoffStrategyTest {

    @Test
    @DisplayName("Backoff increases on consecutive empty polls and resets on activity")
    void test_backoff_increase_and_reset() {
        ReactivePollingBackoffStrategy strategy = new ReactivePollingBackoffStrategy(
                Duration.ofMillis(50),
                Duration.ofSeconds(2),
                2.0,
                Duration.ofMinutes(10)
        );

        // Starts at minimum
        assertThat(strategy.getCurrentInterval()).isEqualTo(Duration.ofMillis(50));

        // Simulate empty polls; only every 3rd increases interval
        strategy.recordEmptyPoll(1);
        assertThat(strategy.getCurrentInterval()).isEqualTo(Duration.ofMillis(50));

        strategy.recordEmptyPoll(2);
        assertThat(strategy.getCurrentInterval()).isEqualTo(Duration.ofMillis(50));

        strategy.recordEmptyPoll(3);
        assertThat(strategy.getCurrentInterval()).isEqualTo(Duration.ofMillis(100));

        strategy.recordEmptyPoll(4);
        strategy.recordEmptyPoll(5);
        strategy.recordEmptyPoll(6);
        assertThat(strategy.getCurrentInterval()).isEqualTo(Duration.ofMillis(200));

        // Activity resets to minimum
        strategy.recordActivity(1);
        assertThat(strategy.getCurrentInterval()).isEqualTo(Duration.ofMillis(50));

        // Notification also resets and keeps minimum
        strategy.recordEmptyPoll(1);
        strategy.recordEmptyPoll(2);
        strategy.recordNotificationActivity();
        assertThat(strategy.getCurrentInterval()).isEqualTo(Duration.ofMillis(50));
    }
}
