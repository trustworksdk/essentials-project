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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultEventStoreSubscriptionManagerTest {
    @Test
    void test_advanced_resume_points_are_checked_ten_times_per_snapshot_interval() {
        assertThat(DefaultEventStoreSubscriptionManager.advancedResumePointsCheckInterval(Duration.ofSeconds(1))).isEqualTo(Duration.ofMillis(100));
        assertThat(DefaultEventStoreSubscriptionManager.advancedResumePointsCheckInterval(Duration.ofSeconds(5))).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void test_advanced_resume_points_are_checked_at_least_every_second_however_long_the_snapshot_interval() {
        assertThat(DefaultEventStoreSubscriptionManager.advancedResumePointsCheckInterval(Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(1));
        assertThat(DefaultEventStoreSubscriptionManager.advancedResumePointsCheckInterval(Duration.ofHours(1))).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void test_advanced_resume_points_are_checked_no_more_often_than_every_50_ms() {
        assertThat(DefaultEventStoreSubscriptionManager.advancedResumePointsCheckInterval(Duration.ofMillis(200))).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void test_advanced_resume_points_are_checked_no_less_often_than_the_periodic_save() {
        assertThat(DefaultEventStoreSubscriptionManager.advancedResumePointsCheckInterval(Duration.ofMillis(30))).isEqualTo(Duration.ofMillis(30));
    }
}
