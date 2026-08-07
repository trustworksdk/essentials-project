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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.notify;

import dk.trustworks.essentials.reactive.LocalEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static dk.trustworks.essentials.components.foundation.postgresql.ListenNotify.SqlOperation.INSERT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural tests for {@link NotifyAwareEventStorePollingOptimizer}. Drives the
 * optimizer with a real {@link NotifyEpochSource} backed by a {@link LocalEventBus}
 * — the cheapest fixture that still exercises the
 * EventBus → epoch counter → optimizer wake-up chain end-to-end.
 */
class NotifyAwareEventStorePollingOptimizerTest {
    private static final String TABLE = "products_events";

    private final LocalEventBus     bus         = LocalEventBus.builder()
                                                               .busName("NotifyAwareOptimizerTest")
                                                               .parallelThreads(1)
                                                               .build();
    private final NotifyEpochSource epochSource = new NotifyEpochSource(bus);

    @AfterEach
    void tearDown() {
        epochSource.close();
    }

    private NotifyPollingSettings settings(long initialMs, long maxMs, double multiplier) {
        return new NotifyPollingSettings(true,
                                         Duration.ofMillis(initialMs),
                                         Duration.ofMillis(maxMs),
                                         multiplier);
    }

    @Test
    void initialDelayIsInitialDelaySetting() {
        var optimizer = new NotifyAwareEventStorePollingOptimizer(epochSource, TABLE, settings(50, 1_000, 2.0));
        assertThat(optimizer.currentDelayMs()).isEqualTo(50L);
    }

    @Test
    void rampsExponentiallyToCapOnNoEvents() {
        var optimizer = new NotifyAwareEventStorePollingOptimizer(epochSource, TABLE, settings(50, 800, 2.0));

        // 50 → 100 → 200 → 400 → 800 (cap) → 800 (clamped)
        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.currentDelayMs()).isEqualTo(100L);
        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.currentDelayMs()).isEqualTo(200L);
        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.currentDelayMs()).isEqualTo(400L);
        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.currentDelayMs()).isEqualTo(800L);
        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.currentDelayMs()).isEqualTo(800L);
    }

    @Test
    void resetsToInitialDelayOnEventsReturned() {
        var optimizer = new NotifyAwareEventStorePollingOptimizer(epochSource, TABLE, settings(50, 1_000, 2.0));
        optimizer.eventStorePollingReturnedNoEvents(); // 100
        optimizer.eventStorePollingReturnedNoEvents(); // 200
        assertThat(optimizer.currentDelayMs()).isEqualTo(200L);

        optimizer.eventStorePollingReturnedEvents();
        assertThat(optimizer.currentDelayMs()).isEqualTo(50L);
    }

    @Test
    void notifyForwardsToZeroDelayAndResetsRamp() throws InterruptedException {
        var optimizer = new NotifyAwareEventStorePollingOptimizer(epochSource, TABLE, settings(50, 1_000, 2.0));
        optimizer.eventStorePollingReturnedNoEvents(); // 100
        optimizer.eventStorePollingReturnedNoEvents(); // 200
        optimizer.eventStorePollingReturnedNoEvents(); // 400

        publishNotification(TABLE);
        awaitEpoch(TABLE, 1L);

        // currentDelayMs sees a fresh epoch → returns 0 and resets internal delay to initialDelay.
        assertThat(optimizer.currentDelayMs()).isZero();
        // Subsequent no-events poll should restart from initialDelay, not from the pre-NOTIFY 400ms.
        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.currentDelayMs()).isEqualTo(100L);
    }

    @Test
    void notifyOnUnrelatedTableDoesNotWakeOptimizer() throws InterruptedException {
        var optimizer = new NotifyAwareEventStorePollingOptimizer(epochSource, TABLE, settings(50, 1_000, 2.0));
        optimizer.eventStorePollingReturnedNoEvents(); // 100
        optimizer.eventStorePollingReturnedNoEvents(); // 200

        publishNotification("orders_events");
        awaitEpoch("orders_events", 1L);

        // No NOTIFY for our table → delay remains at the ramped value.
        assertThat(optimizer.currentDelayMs()).isEqualTo(200L);
    }

    @Test
    void afterWakeUpRampRestartsFromInitial() throws InterruptedException {
        var optimizer = new NotifyAwareEventStorePollingOptimizer(epochSource, TABLE, settings(50, 1_000, 2.0));
        optimizer.eventStorePollingReturnedNoEvents(); // 100
        optimizer.eventStorePollingReturnedNoEvents(); // 200
        optimizer.eventStorePollingReturnedNoEvents(); // 400
        publishNotification(TABLE);
        awaitEpoch(TABLE, 1L);

        // First call after notify → 0, second call still 0 only if epoch advances again.
        assertThat(optimizer.currentDelayMs()).isZero();

        // Now ramp again starting from initialDelay.
        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.currentDelayMs()).isEqualTo(100L);
        optimizer.eventStorePollingReturnedNoEvents();
        assertThat(optimizer.currentDelayMs()).isEqualTo(200L);
    }

    @Test
    void multipleNotifyInQuickSuccessionStillForwardsOnce() throws InterruptedException {
        var optimizer = new NotifyAwareEventStorePollingOptimizer(epochSource, TABLE, settings(50, 1_000, 2.0));
        optimizer.eventStorePollingReturnedNoEvents(); // 100
        publishNotification(TABLE);
        publishNotification(TABLE);
        publishNotification(TABLE);
        awaitEpoch(TABLE, 3L);

        // First read after multiple NOTIFYs → 0 (one wake-up — extra NOTIFYs collapse).
        assertThat(optimizer.currentDelayMs()).isZero();
        // Second read with no new NOTIFY since last read → back to ramped delay from initialDelay.
        // The NOTIFY-handler reset currentDelayMs to initialDelay (50 ms).
        assertThat(optimizer.currentDelayMs()).isEqualTo(50L);
    }

    @Test
    void constructorRejectsInvalidInputs() {
        var goodSettings = settings(50, 1_000, 2.0);
        assertThatThrownBy(() -> new NotifyAwareEventStorePollingOptimizer(null, TABLE, goodSettings))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotifyAwareEventStorePollingOptimizer(epochSource, "", goodSettings))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotifyAwareEventStorePollingOptimizer(epochSource, TABLE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void publishNotification(String tableName) {
        bus.publish(new EventStreamTableChangeNotification(tableName, INSERT));
    }

    /**
     * The bus delivers async-subscribers on a reactor scheduler; epoch increments are not
     * observable immediately after {@code publish()}. Spin briefly until the expected
     * epoch is reached or we time out — equivalent to Awaitility but without adding the
     * dependency for a 50 ms wait.
     */
    private void awaitEpoch(String tableName, long expected) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (epochSource.currentEpoch(tableName) < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(epochSource.currentEpoch(tableName))
                .as("epoch for table='%s' should reach %d", tableName, expected)
                .isGreaterThanOrEqualTo(expected);
    }
}
