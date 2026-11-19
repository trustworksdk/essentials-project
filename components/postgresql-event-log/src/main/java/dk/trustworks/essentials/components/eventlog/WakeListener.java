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
package dk.trustworks.essentials.components.eventlog;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlNotificationDrain;
import org.postgresql.*;
import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.*;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Tier 1's receiving half: one per {@link EventLog}, running only while the reader loop has an
 * active type and {@link EventLogSettings#crossJvmWakeUp()} is on. One platform daemon thread {@code eventlog-listener}
 * and one held connection ({@code application_name = eventlog-listener}) with {@code LISTEN el_wake}.
 * <ul>
 *     <li><b>Drain</b> (P0.17 (a)): foundation's {@link PostgresqlNotificationDrain} - pgjdbc's
 *     {@code getNotifications(timeout)} with its socket peek's cool-down pinned by reflection ({@code PINNED_PEEK}), so a
 *     dense stream does not hold notifications inside the driver; if the reflection fails, {@code QUERY_POLL}: a trivial
 *     statement whose response carries the queued notifications, polled every millisecond and backing off to 50 ms while
 *     nothing arrives. {@link WatermarkMutant#STOCK_DRAIN} calls pgjdbc's {@code getNotifications(timeout)} unpinned.</li>
 *     <li><b>No work</b>: a notification is parsed, dropped if it is this event log's own (tier 2 acted on it), and
 *     sets the wake flag of its aggregate type if that type is active. It never reads events or reader state.</li>
 *     <li><b>Restart</b>: any failure closes the connection, waits {@code min(backstopInterval, 1 s)}, reconnects, re-runs
 *     {@code LISTEN} and then wakes every active type once, since a notification may have been missed meanwhile.</li>
 *     <li><b>Stop</b>: with the last active type; {@code UNLISTEN *}, the peek pin undone and {@code application_name}
 *     reset before the connection goes back to the pool. The stop is noticed at once through the notifier's kick, at the
 *     latest after one drain timeout.</li>
 * </ul>
 */
final class WakeListener {
    private static final Logger log = LoggerFactory.getLogger(WakeListener.class);

    static final String APPLICATION_NAME = "eventlog-listener";
    /** How long one drain call blocks for its first notification; also the liveness check of a silent connection */
    static final int    DRAIN_TIMEOUT_MILLIS = 1_000;

    private final DataSource          dataSource;
    private final EventLogSettings    settings;
    private final String              instance;
    private final ReaderLoop          loop;
    private final WakeNotifier        notifier;
    private final Supplier<TestSeams> seams;

    /** Guarded by {@code this} */
    private Thread                    thread;
    private volatile boolean          wanted;
    /** The backend pid of the current connection, 0 while it has none; for tests */
    private volatile int              backendPid;
    /** How many times {@code LISTEN} was (or, under a mutant, would have been) set up; for tests */
    final AtomicLong                  connects      = new AtomicLong();
    final AtomicLong                  restarts      = new AtomicLong();
    final AtomicLong                  notifications = new AtomicLong();

    // ---- test knobs ----
    /** The wait before a reconnect instead of {@code min(backstopInterval, 1 s)} */
    volatile Duration                 reconnectDelayForTests;
    /** {@code true}: every notification is received and dropped (the I7 arm "listener lost") */
    volatile boolean                  deafForTests;
    /** {@code true}: drain with {@code QUERY_POLL} even if the peek can be pinned */
    volatile boolean                  queryPollForTests;

    WakeListener(DataSource dataSource, EventLogSettings settings, String instance, ReaderLoop loop, WakeNotifier notifier, Supplier<TestSeams> seams) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.instance = requireNonNull(instance, "No instance provided");
        this.loop = requireNonNull(loop, "No loop provided");
        this.notifier = requireNonNull(notifier, "No notifier provided");
        this.seams = requireNonNull(seams, "No seams provided");
    }

    /** The reader loop got its first active type. Never blocks */
    void start() {
        synchronized (this) {
            wanted = true;
            if (thread == null) {
                thread = Thread.ofPlatform().name("eventlog-listener").daemon().start(this::run);
            }
        }
    }

    /** The reader loop lost its last active type. Never blocks; the thread ends promptly (the notifier's kick) */
    void stop() {
        Thread t;
        synchronized (this) {
            wanted = false;
            t = thread;
        }
        if (t != null) {
            LockSupport.unpark(t);
            notifier.kick();
        }
    }

    boolean isRunning() {
        synchronized (this) {
            return thread != null;
        }
    }

    /** @return {@code true} if the thread ended within {@code timeout} */
    boolean awaitStopped(Duration timeout) throws InterruptedException {
        Thread t;
        synchronized (this) {
            t = thread;
        }
        return t == null || t.join(timeout);
    }

    /** @return the backend pid of the listener's connection, 0 while it has none; for tests */
    int backendPid() {
        return backendPid;
    }

    /**
     * @return the aggregate type {@code payload} asks to wake, or {@code null}: malformed, no type (a kick), or this
     * instance's own when {@code dropOwn}
     */
    static String typeToWake(String payload, String instance, boolean dropOwn) {
        if (payload == null) {
            return null;
        }
        var colon = payload.indexOf(':');
        if (colon <= 0 || colon == payload.length() - 1) {
            return null;
        }
        if (dropOwn && payload.regionMatches(0, instance, 0, colon) && colon == instance.length()) {
            return null;
        }
        return payload.substring(colon + 1);
    }

    private void run() {
        log.debug("Wake listener starts");
        var restarted = false;
        while (true) {
            synchronized (this) {
                if (!wanted) {
                    thread = null;
                    log.debug("Wake listener ends: no active aggregate type");
                    return;
                }
            }
            Connection                  connection   = null;
            PostgresqlNotificationDrain drain        = null;
            String                      previousName = null;
            try {
                if (restarted) {
                    seams.get().hooks().beforeListenerReconnect();
                }
                connection = dataSource.getConnection();
                if (!connection.getAutoCommit()) {
                    connection.setAutoCommit(true);
                }
                previousName = connection.getClientInfo("ApplicationName");
                connection.setClientInfo("ApplicationName", APPLICATION_NAME);
                var pg = connection.unwrap(PGConnection.class);
                if (!(restarted && seams.get().has(RECONNECT_WITHOUT_LISTEN))) {
                    try (var statement = connection.createStatement()) {
                        statement.execute("LISTEN " + WakeNotifier.CHANNEL);
                    }
                }
                if (!seams.get().has(STOCK_DRAIN)) {
                    drain = queryPollForTests ? PostgresqlNotificationDrain.openQueryPoll(connection) : PostgresqlNotificationDrain.open(connection);
                }
                backendPid = pg.getBackendPID();
                connects.incrementAndGet();
                if (restarted) {
                    restarts.incrementAndGet();
                    if (seams.get().has(BACKSTOP_REARMED_ON_LISTENER_RESTART)) {
                        loop.rearmBackstops();
                    } else if (!seams.get().has(NO_WAKE_ON_LISTENER_RESTART)) {
                        loop.wakeAll();
                    }
                }
                while (wanted) {
                    var received = drain != null ? drain.next(DRAIN_TIMEOUT_MILLIS) : pg.getNotifications(DRAIN_TIMEOUT_MILLIS);
                    if (received == null) {
                        continue;
                    }
                    for (var notification : received) {
                        if (WakeNotifier.CHANNEL.equals(notification.getName())) {
                            handle(notification.getParameter());
                        }
                    }
                }
                release(connection, drain, previousName, true);
                restarted = false;
            } catch (Exception e) {
                if (wanted) {
                    log.debug("The wake listener failed; reconnecting", e);
                }
                release(connection, drain, previousName, false);
                restarted = true;
                pauseBeforeReconnect();
            }
        }
    }

    private void handle(String payload) throws Exception {
        notifications.incrementAndGet();
        seams.get().hooks().onNotification(payload);
        if (deafForTests) {
            return;
        }
        var type = typeToWake(payload, instance, !seams.get().has(NO_OWN_INSTANCE_FILTER));
        if (type != null) {
            loop.wake(type);
        }
    }

    private void pauseBeforeReconnect() {
        var delay    = reconnectDelayForTests;
        var backstop = settings.backstopInterval();
        if (delay == null) {
            delay = backstop.compareTo(Duration.ofSeconds(1)) < 0 ? backstop : Duration.ofSeconds(1);
        }
        var deadline = System.nanoTime() + delay.toNanos();
        long left;
        while (wanted && (left = deadline - System.nanoTime()) > 0) {
            LockSupport.parkNanos(this, left);
        }
    }

    /** Undo everything a pooled session must not carry; a clean stop also runs {@code UNLISTEN *} */
    private void release(Connection connection, PostgresqlNotificationDrain drain, String previousName, boolean clean) {
        backendPid = 0;
        if (drain != null) {
            drain.close();
        }
        if (connection == null) {
            return;
        }
        try {
            if (!connection.isClosed() && (clean || connection.isValid(1))) {
                try (var statement = connection.createStatement()) {
                    statement.execute("UNLISTEN *");
                }
                connection.setClientInfo("ApplicationName", previousName == null ? "" : previousName);
            }
        } catch (SQLException e) {
            log.debug("Resetting the wake listener's connection failed", e);
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Closing the wake listener's connection failed", e);
        }
    }
}
