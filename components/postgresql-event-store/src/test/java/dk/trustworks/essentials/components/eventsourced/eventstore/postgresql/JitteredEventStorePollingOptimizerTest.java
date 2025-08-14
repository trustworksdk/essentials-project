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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

public class JitteredEventStorePollingOptimizerTest {

    @Test
    void increasesBackoffAndRespectsCap() {
        var optimizer = new JitteredEventStorePollingOptimizer("test", 100, 60, 220, 0.2);

        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.toString()).contains("backoffMs=60");

        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.toString()).contains("backoffMs=120");

        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.toString()).contains("backoffMs=180");

        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.toString()).contains("backoffMs=220");

        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.toString()).contains("backoffMs=220");
    }

    @Test
    void resetsBackoffOnEvents() {
        var optimizer = new JitteredEventStorePollingOptimizer("test", 100, 100, 500, 0.2);
        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.toString()).contains("backoffMs=100");

        optimizer.eventStorePollingReturnedEvents();
        assertThat(optimizer.toString()).contains("backoffMs=0");
    }

    @RepeatedTest(5)
    void currentDelayRespectsJitterBounds() {
        long   pollingInterval = 100;
        long   increment       = 50;
        long   maxBackoff      = 300;
        double jitter          = 0.20;

        var optimizer = new JitteredEventStorePollingOptimizer("test", pollingInterval, increment, maxBackoff, jitter);

        optimizer.eventStorePollingReturnedNoEvents(); // 50
        optimizer.eventStorePollingReturnedNoEvents(); // 100

        long base = Math.max(pollingInterval, 100);
        long min  = Math.round(base * (1.0 - jitter));
        long max  = Math.round(base * (1.0 + jitter));

        long delay = optimizer.currentDelayMs();
        assertThat(delay).isBetween(min, max);
    }

    @Test
    void jitterZeroMeansExactBase() {
        var optimizer = new JitteredEventStorePollingOptimizer("test", 120, 60, 300, 0.0);
        optimizer.eventStorePollingReturnedNoEvents(); // backoff=60

        long delay = optimizer.currentDelayMs();
        assertThat(delay).isEqualTo(120);
    }

    @Test
    void constructorValidation() {
        assertThatThrownBy(() -> new JitteredEventStorePollingOptimizer("test", 0, 10, 100, 0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JitteredEventStorePollingOptimizer("test", 10, 0, 100, 0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JitteredEventStorePollingOptimizer("test", 50, 10, 40, 0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JitteredEventStorePollingOptimizer("test", 50, 10, 100, -0.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JitteredEventStorePollingOptimizer("test", 50, 10, 100, 0.51))
                .isInstanceOf(IllegalArgumentException.class);

    }
}

