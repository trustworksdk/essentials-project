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

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.reactive.*;
import org.jdbi.v3.core.*;
import org.junit.jupiter.api.*;
import org.postgresql.*;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Notify-polling wired as the Spring starter wires it - {@code EventStoreNotifyPollingBootstrap} registering the event-stream
 * tables with the {@link MultiTableChangeListener}, and a {@link NotifyEpochSource} on the listener's {@link EventBus} - with
 * an application's asynchronous subscriber on the same bus that is stuck (held on a latch for the whole test).
 * <p>
 * A subscription's wake-up must not wait for that subscriber: both tests give the epoch a fixed bound, and the stuck
 * subscriber never moves, so the outcome does not depend on the speed of the machine. Before the fix the listener's
 * poll thread published every notification of a burst to the shared bus and parked once the bus's buffer was full
 * (pinned by the first test; fixed by {@link EventStreamTableNotificationDuplicationFilter}), and the epoch source was
 * itself an asynchronous subscriber queued behind the stuck one (pinned by the second test; fixed by
 * {@link NotifyEpochSource} subscribing synchronously), so both tests waited forever.
 */
class NotifyPollingWakeUpWithSlowBusSubscriberTest {
    private static final String   ORDERS_TABLE   = "orders_events";
    private static final String   PRODUCTS_TABLE = "products_events";
    private static final Duration WAKE_UP_BOUND  = Duration.ofSeconds(2);

    private final Queue<PGNotification[]>                    pendingPolls       = new ConcurrentLinkedQueue<>();
    private final CountDownLatch                             releaseSlowHandler = new CountDownLatch(1);
    private final AtomicInteger                              slowHandlerCalls   = new AtomicInteger();
    private       LocalEventBus                              bus;
    private       MultiTableChangeListener<TableChangeNotification> listener;
    private       NotifyEpochSource                          epochSource;
    private       Thread                                     applicationPublisher;

    @BeforeEach
    void setUp() throws Exception {
        // Spring: EventStoreConfiguration.eventBus(...) builds the shared bus with LocalEventBus's default buffer
        bus = LocalEventBus.builder().busName("NotifyPollingWakeUpWithSlowBusSubscriberTest").build();
        bus.addAsyncSubscriber(event -> {
            slowHandlerCalls.incrementAndGet();
            try {
                releaseSlowHandler.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        var pgConnection = mock(PGConnection.class);
        when(pgConnection.getNotifications()).thenAnswer(invocation -> {
            var batch = pendingPolls.poll();
            return batch == null ? new PGNotification[0] : batch;
        });
        var connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
        var handle = mock(Handle.class);
        when(handle.getConnection()).thenReturn(connection);
        var jdbi = mock(Jdbi.class);
        when(jdbi.open()).thenReturn(handle);

        // Spring: EssentialsComponentsConfiguration.multiTableChangeListener(...) - duplicate filtering is on by default
        listener = MultiTableChangeListener.builder()
                                           .setJdbi(jdbi)
                                           .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                           .setEventBus(bus)
                                           .setPollingInterval(Duration.ofMillis(10))
                                           .build();
        listener.start();

        // Spring: EventStoreNotifyPollingBootstrap hands every event-stream table to the listener
        var eventStreamTables = EventStreamTableNotificationDuplicationFilter.registerWith(listener);
        eventStreamTables.listenTo(ORDERS_TABLE);
        eventStreamTables.listenTo(PRODUCTS_TABLE);
        epochSource = new NotifyEpochSource(listener.getEventBus());
    }

    @AfterEach
    void tearDown() throws Exception {
        releaseSlowHandler.countDown();
        if (applicationPublisher != null) {
            applicationPublisher.join(Duration.ofSeconds(30));
        }
        if (epochSource != null) epochSource.close();
        if (listener != null) listener.close();
        if (bus != null) bus.stop();
    }

    /**
     * Before the fix: 5 000 notifications became 5 000 bus events; the poll thread parked on the full buffer and never
     * read the {@code products_events} notification
     */
    @Test
    void a_burst_of_notifications_does_not_hold_up_the_next_wake_up_while_a_bus_subscriber_is_stuck() {
        var ordersInsert = notification(ORDERS_TABLE);
        for (int poll = 0; poll < 10; poll++) {
            var batch = new PGNotification[500];
            Arrays.fill(batch, ordersInsert);
            pendingPolls.add(batch);
        }
        pendingPolls.add(new PGNotification[]{notification(PRODUCTS_TABLE)});

        assertEpochAdvancesWithinBound(PRODUCTS_TABLE);
        assertThat(epochSource.currentEpoch(ORDERS_TABLE)).as("the burst woke the orders subscriptions").isPositive();
        assertThat(releaseSlowHandler.getCount()).as("the slow subscriber was still stuck").isEqualTo(1);
    }

    /**
     * The bus is already backed up behind the stuck subscriber by the application's own events (in Spring the
     * {@code EventStoreEventBus} also carries every {@code PersistedEvents}). Before the fix the epoch source received
     * the notification only after the stuck subscriber had taken every event ahead of it
     */
    @Test
    void a_wake_up_is_not_queued_behind_a_stuck_bus_subscriber() throws Exception {
        var published = new AtomicInteger();
        applicationPublisher = Thread.ofPlatform().name("application-publisher").start(() -> {
            for (int i = 0; i < 2_000; i++) {
                bus.publish("application event " + i);
                published.incrementAndGet();
            }
        });
        // Every event past the slow subscriber's prefetch (256) is out of the epoch source's reach while it is stuck
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (published.get() < 300 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(published.get()).as("the application filled the bus ahead of the notification").isGreaterThanOrEqualTo(300);

        pendingPolls.add(new PGNotification[]{notification(ORDERS_TABLE)});

        assertEpochAdvancesWithinBound(ORDERS_TABLE);
        assertThat(releaseSlowHandler.getCount()).as("the slow subscriber was still stuck").isEqualTo(1);
    }

    private void assertEpochAdvancesWithinBound(String tableName) {
        var deadline = System.nanoTime() + WAKE_UP_BOUND.toNanos();
        while (epochSource.currentEpoch(tableName) == 0 && System.nanoTime() < deadline) {
            LockSupportSleep.sleepMillis(5);
        }
        assertThat(epochSource.currentEpoch(tableName))
                .as("the notify epoch of '%s' advanced within %s while a subscriber on the same bus was stuck", tableName, WAKE_UP_BOUND)
                .isPositive();
    }

    private static PGNotification notification(String tableName) {
        var notification = mock(PGNotification.class);
        when(notification.getName()).thenReturn(ListenNotify.resolveTableChangeChannelName(tableName));
        when(notification.getParameter()).thenReturn("{\"table_name\":\"" + tableName + "\",\"sql_operation\":\"INSERT\"}");
        return notification;
    }

    private static final class LockSupportSleep {
        static void sleepMillis(long millis) {
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(millis).toNanos());
        }
    }
}
