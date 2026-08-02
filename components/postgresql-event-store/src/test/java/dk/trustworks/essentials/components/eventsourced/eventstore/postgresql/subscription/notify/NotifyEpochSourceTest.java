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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static dk.trustworks.essentials.components.foundation.postgresql.ListenNotify.SqlOperation.INSERT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that {@link NotifyEpochSource} bridges {@code TableChangeNotification} events from
 * an {@link LocalEventBus} into per-table epoch counters correctly, including isolation
 * between tables, indifference to unrelated bus events, and clean unsubscribe on close.
 */
class NotifyEpochSourceTest {
    private LocalEventBus     bus;
    private NotifyEpochSource source;

    @BeforeEach
    void setUp() {
        bus = LocalEventBus.builder()
                           .busName("NotifyEpochSourceTest")
                           .parallelThreads(1)
                           .build();
        source = new NotifyEpochSource(bus);
    }

    @AfterEach
    void tearDown() {
        if (source != null) source.close();
    }

    @Test
    void unseenTableReturnsZero() {
        assertThat(source.currentEpoch("never_observed_table")).isZero();
    }

    @Test
    void singleNotificationAdvancesEpochOnce() throws InterruptedException {
        bus.publish(new EventStreamTableChangeNotification("orders_events", INSERT));
        awaitEpochAtLeast("orders_events", 1L);
        assertThat(source.currentEpoch("orders_events")).isEqualTo(1L);
    }

    @Test
    void multipleNotificationsForSameTableAccumulate() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            bus.publish(new EventStreamTableChangeNotification("orders_events", INSERT));
        }
        awaitEpochAtLeast("orders_events", 5L);
        assertThat(source.currentEpoch("orders_events")).isEqualTo(5L);
    }

    @Test
    void notificationsForDifferentTablesAreIsolated() throws InterruptedException {
        bus.publish(new EventStreamTableChangeNotification("orders_events", INSERT));
        bus.publish(new EventStreamTableChangeNotification("orders_events", INSERT));
        bus.publish(new EventStreamTableChangeNotification("products_events", INSERT));

        awaitEpochAtLeast("orders_events", 2L);
        awaitEpochAtLeast("products_events", 1L);

        assertThat(source.currentEpoch("orders_events")).isEqualTo(2L);
        assertThat(source.currentEpoch("products_events")).isEqualTo(1L);
        assertThat(source.currentEpoch("customers_events")).isZero();
    }

    @Test
    void nonTableChangeNotificationEventsAreIgnored() throws InterruptedException {
        bus.publish("some unrelated string event");
        bus.publish(42);
        bus.publish(new EventStreamTableChangeNotification("orders_events", INSERT));

        awaitEpochAtLeast("orders_events", 1L);
        assertThat(source.currentEpoch("orders_events")).isEqualTo(1L);
    }

    @Test
    void closeUnsubscribesFromBus() throws InterruptedException {
        bus.publish(new EventStreamTableChangeNotification("orders_events", INSERT));
        awaitEpochAtLeast("orders_events", 1L);
        assertThat(source.currentEpoch("orders_events")).isEqualTo(1L);

        source.close();

        // Post-close notifications must NOT advance the epoch. Give the bus a tick to
        // confirm — if the handler were still attached, the counter would tick up.
        bus.publish(new EventStreamTableChangeNotification("orders_events", INSERT));
        bus.publish(new EventStreamTableChangeNotification("orders_events", INSERT));
        Thread.sleep(100);
        assertThat(source.currentEpoch("orders_events")).isEqualTo(1L);

        // Mark source null so @AfterEach doesn't try to close it twice.
        source = null;
    }

    @Test
    void constructorRejectsNullBus() {
        assertThatThrownBy(() -> new NotifyEpochSource(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void awaitEpochAtLeast(String tableName, long expected) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (source.currentEpoch(tableName) < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(source.currentEpoch(tableName))
                .as("epoch for table='%s' should reach %d", tableName, expected)
                .isGreaterThanOrEqualTo(expected);
    }
}
