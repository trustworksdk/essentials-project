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

package dk.trustworks.essentials.components.foundation.postgresql;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.lifecycle.ShutdownContext;
import dk.trustworks.essentials.reactive.EventBus;
import org.jdbi.v3.core.*;
import org.junit.jupiter.api.Test;
import org.postgresql.*;
import org.slf4j.LoggerFactory;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultiTableChangeListenerTest {

    @Test
    void listenToNotificationsFor_with_invalid_column_name() {
        try (var listener = new MultiTableChangeListener<MultiTableChangeListenerIT.TestTableNotification>(
                mock(Jdbi.class),
                Duration.ofSeconds(30), // Very long poll length to avoid errors from the polling mechanism
                mock(JSONSerializer.class),
                mock(EventBus.class),
                false
        )) {
            assertThatThrownBy(() -> listener.listenToNotificationsFor("where", MultiTableChangeListenerIT.TestTableNotification.class))
                    .isInstanceOf(InvalidTableOrColumnNameException.class);
            assertThatThrownBy(() -> listener.listenToNotificationsFor("1=1", MultiTableChangeListenerIT.TestTableNotification.class))
                    .isInstanceOf(InvalidTableOrColumnNameException.class);
            assertThatThrownBy(() -> listener.listenToNotificationsFor("1; DROP TABLE", MultiTableChangeListenerIT.TestTableNotification.class))
                    .isInstanceOf(InvalidTableOrColumnNameException.class);
        }
    }

    @Test
    void unlistenToNotificationsFor_with_invalid_column_name() {
        try (var listener = new MultiTableChangeListener<MultiTableChangeListenerIT.TestTableNotification>(
                mock(Jdbi.class),
                Duration.ofSeconds(30), // Very long poll length to avoid errors from the polling mechanism
                mock(JSONSerializer.class),
                mock(EventBus.class),
                false
        )) {
            assertThatThrownBy(() -> listener.unlistenToNotificationsFor("where"))
                    .isInstanceOf(InvalidTableOrColumnNameException.class);
            assertThatThrownBy(() -> listener.unlistenToNotificationsFor("1=1"))
                    .isInstanceOf(InvalidTableOrColumnNameException.class);
            assertThatThrownBy(() -> listener.unlistenToNotificationsFor("1; DROP TABLE"))
                    .isInstanceOf(InvalidTableOrColumnNameException.class);
        }
    }

    /**
     * LN-2: a lost connection used to be logged at DEBUG only. It is now reported at WARN - but a database that stays
     * unreachable fails every poll, so the report is rate-limited rather than repeated every 50 ms.
     */
    @Test
    void a_connection_that_cannot_be_opened_is_reported_at_warn_once_per_interval() {
        var jdbi = mock(Jdbi.class);
        when(jdbi.open()).thenThrow(new ConnectionException(new SQLException("Connection refused", "08001")));
        var logger   = (Logger) LoggerFactory.getLogger(MultiTableChangeListener.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try (var listener = new MultiTableChangeListener<MultiTableChangeListenerIT.TestTableNotification>(jdbi,
                                                                                                         Duration.ofMillis(10),
                                                                                                         mock(JSONSerializer.class),
                                                                                                         mock(EventBus.class),
                                                                                                         false)) {
            listener.listenToNotificationsFor("table1", MultiTableChangeListenerIT.Table1Notification.class);

            // Many polls, each of which fails to open the connection
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> verify(jdbi, atLeast(20)).open());

            assertThat(appender.list.stream().filter(event -> event.getLevel() == Level.WARN))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.getFormattedMessage()).contains("lost its LISTEN connection", "[table1]");
                        assertThat(event.getThrowableProxy()).isNotNull();
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }
    /**
     * Review F-034: {@code stop()} used to close whatever LISTEN connection was current once it had waited for the poll
     * thread, outside the monitor. A {@code start()} that ran in that window found the old connection still open, kept
     * it - and then lost it to the finishing {@code stop()}, so notifications sent before the next poll reopened it were
     * gone, although {@code start()} promises that every registered table is LISTENed to when it returns.<br>
     * Driven deterministically: the old poll thread is held inside {@link EventBus#publish(Object)} (outside the
     * connection lock, ignoring the interrupt), so {@code stop()} sits in its wait for that thread while {@code start()}
     * runs.
     */
    @Test
    void a_start_racing_a_stop_keeps_the_connection_it_opened_and_keeps_polling() throws Exception {
        var channel        = ListenNotify.resolveTableChangeChannelName("table1");
        var openedHandles  = new CopyOnWriteArrayList<Handle>();
        var pendingNotices = new ConcurrentLinkedQueue<PGNotification>(List.of(notificationOn(channel)));
        var jdbi           = mock(Jdbi.class);
        when(jdbi.open()).thenAnswer(invocation -> {
            var handle = listenHandleDelivering(pendingNotices);
            openedHandles.add(handle);
            return handle;
        });
        var jsonSerializer = mock(JSONSerializer.class);
        when(jsonSerializer.deserialize(anyString(), eq(MultiTableChangeListenerIT.Table1Notification.class)))
                .thenAnswer(invocation -> new MultiTableChangeListenerIT.Table1Notification());

        var firstPublishStarted = new CountDownLatch(1);
        var releaseFirstPublish = new CountDownLatch(1);
        var firstPublish        = new AtomicBoolean(true);
        var published           = new LinkedBlockingQueue<>();
        var eventBus            = mock(EventBus.class);
        doAnswer(invocation -> {
            if (firstPublish.getAndSet(false)) {
                firstPublishStarted.countDown();
                awaitIgnoringInterrupts(releaseFirstPublish);
            }
            published.add(invocation.getArgument(0));
            return null;
        }).when(eventBus).publish(any());

        var listener = new MultiTableChangeListener<MultiTableChangeListenerIT.TestTableNotification>(jdbi, Duration.ofMillis(10), jsonSerializer, eventBus, false);
        listener.start();
        listener.listenToNotificationsFor("table1", MultiTableChangeListenerIT.Table1Notification.class);
        assertThat(firstPublishStarted.await(5, TimeUnit.SECONDS)).as("the first poll reached publish").isTrue();
        var connectionOfTheStoppedListener = openedHandles.getFirst();

        var stopper = Executors.newSingleThreadExecutor();
        try {
            var stopping = stopper.submit(listener::stop);
            Awaitility.waitAtMost(Duration.ofSeconds(5)).until(() -> !listener.isStarted());

            listener.start();
            assertThat(openedHandles).as("start() opened a connection of its own").hasSize(2);
            var connectionOfTheStartedListener = openedHandles.getLast();

            releaseFirstPublish.countDown();
            stopping.get(10, TimeUnit.SECONDS);

            verify(connectionOfTheStoppedListener).execute("UNLISTEN *");
            verify(connectionOfTheStoppedListener).close();
            verify(connectionOfTheStartedListener, never()).execute("UNLISTEN *");
            verify(connectionOfTheStartedListener, never()).close();
            assertThat(listener.isStarted()).isTrue();

            // ... and the restarted listener polls the connection it opened
            pendingNotices.add(notificationOn(channel));
            assertThat(published.poll(5, TimeUnit.SECONDS)).as("the notification held back in publish").isNotNull();
            assertThat(published.poll(5, TimeUnit.SECONDS)).as("a notification sent after start() returned").isNotNull();
            assertThat(openedHandles).as("nothing had to reopen the connection").hasSize(2);
        } finally {
            releaseFirstPublish.countDown();
            stopper.shutdownNow();
            listener.stop();
        }
    }

    /**
     * Review F-050: {@code stop()} used to wait for the connection lock while holding the listener's monitor. A
     * {@code listenToNotificationsFor} holds that lock while it borrows a connection from the pool - up to the pool's
     * connection timeout - so the stop, and with it every other {@code synchronized} section, the shutdown signal included,
     * waited for the borrow. Now the stop waits for it outside the monitor, and still closes the connection the borrow
     * produced, which belongs to the run it ended.
     */
    @Test
    void a_stop_waiting_for_a_connection_borrow_does_not_hold_up_the_shutdown_signal() throws Exception {
        var borrowStarted = new CountDownLatch(1);
        var releaseBorrow = new CountDownLatch(1);
        var openedHandles = new CopyOnWriteArrayList<Handle>();
        var jdbi          = mock(Jdbi.class);
        when(jdbi.open()).thenAnswer(invocation -> {
            borrowStarted.countDown();
            awaitIgnoringInterrupts(releaseBorrow);
            var handle = listenHandleDelivering(new ConcurrentLinkedQueue<>());
            openedHandles.add(handle);
            return handle;
        });
        // Polls far apart, so the only borrow is the one listenToNotificationsFor makes
        var listener = new MultiTableChangeListener<MultiTableChangeListenerIT.TestTableNotification>(jdbi, Duration.ofSeconds(30), mock(JSONSerializer.class), mock(EventBus.class), false);
        listener.start();

        var threads = Executors.newFixedThreadPool(2);
        try {
            var listening = threads.submit(() -> listener.listenToNotificationsFor("table1", MultiTableChangeListenerIT.Table1Notification.class));
            assertThat(borrowStarted.await(5, TimeUnit.SECONDS)).as("listenToNotificationsFor is borrowing a connection").isTrue();
            var stopping = threads.submit(listener::stop);
            Awaitility.waitAtMost(Duration.ofSeconds(5)).until(() -> !listener.isStarted());

            var signalling = CompletableFuture.runAsync(() -> listener.shutdownStarting(ShutdownContext.startingNow(Duration.ofSeconds(10))));
            assertThatCode(() -> signalling.get(5, TimeUnit.SECONDS)).as("the shutdown signal is not held up behind the borrow").doesNotThrowAnyException();
            assertThat(stopping.isDone()).as("stop() waits for the borrow").isFalse();

            releaseBorrow.countDown();
            listening.get(10, TimeUnit.SECONDS);
            stopping.get(10, TimeUnit.SECONDS);
            assertThat(openedHandles).hasSize(1);
            verify(openedHandles.getFirst()).close();
        } finally {
            releaseBorrow.countDown();
            threads.shutdownNow();
        }
    }

    /**
     * A mocked LISTEN connection whose {@code getNotifications()} hands out the queued notifications, one per poll
     */
    private static Handle listenHandleDelivering(Queue<PGNotification> pendingNotices) throws SQLException {
        var pgConnection = mock(PGConnection.class);
        when(pgConnection.getNotifications()).thenAnswer(invocation -> {
            var notification = pendingNotices.poll();
            return notification == null ? new PGNotification[0] : new PGNotification[]{notification};
        });
        var connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
        var handle = mock(Handle.class);
        when(handle.getConnection()).thenReturn(connection);
        return handle;
    }

    private static PGNotification notificationOn(String channel) {
        var notification = mock(PGNotification.class);
        when(notification.getName()).thenReturn(channel);
        when(notification.getParameter()).thenReturn("{}");
        return notification;
    }

    /**
     * Holds the poll thread past {@code stop()}'s interrupt, as a publish to a subscriber that does not react to
     * interrupts would
     */
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        var interrupted = false;
        while (true) {
            try {
                if (latch.await(10, TimeUnit.SECONDS)) {
                    break;
                }
                throw new IllegalStateException("The test never released the poll thread");
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
