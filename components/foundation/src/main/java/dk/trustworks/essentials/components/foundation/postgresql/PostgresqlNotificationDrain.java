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

import org.postgresql.*;
import org.postgresql.core.*;
import org.slf4j.*;

import java.lang.reflect.Field;
import java.sql.*;
import java.util.concurrent.locks.LockSupport;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Takes LISTEN notifications off a dedicated pgjdbc connection without the driver holding them back while the
 * notification stream is dense.
 * <p>
 * <b>Why:</b> pgjdbc's {@link PGConnection#getNotifications(int)} ({@code QueryExecutorImpl.processNotifies(int)},
 * checked up to 42.7.x) applies its timeout to the first message only. After that it keeps reading for as long as
 * {@code PGStream.hasMessagePending()} reports another message, and that method peeks the socket with a 1 ms timeout.
 * Nothing it has read is handed back until a peek comes up empty, so a stream whose notifications arrive less than
 * about a millisecond apart is held inside the driver for as long as it stays that dense: a listener built on the stock
 * call wakes its consumers late, at sustained rates as late as no listener at all. See
 * {@code docs/plans/pgjdbc-notification-drain-options.md} for the measurements.
 * <p>
 * Two ways of draining, chosen by {@link #open(Connection)}:
 * <ul>
 *     <li>{@link Mode#PINNED_PEEK} (the default): one reflective write sets {@code PGStream.nextStreamAvailableCheckTime}
 *     to {@code Long.MAX_VALUE}. {@code hasMessagePending()} skips its socket peek while {@code now} is below that value,
 *     so it answers from what is already buffered alone, and {@code getNotifications(timeout)} returns what has arrived
 *     instead of looping while traffic stays dense. The first message is still a blocking read with the timeout.
 *     {@link #close()} writes the field back to 0, so a pooled connection goes back unchanged.</li>
 *     <li>{@link Mode#QUERY_POLL} (the fallback when the reflection fails - logged once per JVM at WARN - or chosen with
 *     {@link #openQueryPoll(Connection)}): never enters {@code processNotifies}. A prepared {@code SELECT 1} is executed;
 *     its response is read up to ReadyForQuery, every notification in front of it lands in the executor's list, and
 *     {@code QueryExecutor.getNotifications()} hands them over without touching the socket. Polled every millisecond,
 *     doubling to at most 50 ms while nothing arrives. It costs a round trip per poll, so it is the fallback only.</li>
 * </ul>
 * Use: open one drain per LISTEN connection after {@code LISTEN}, call {@link #next(int)} in the listen loop, and
 * {@link #close()} it before the connection is released. Not thread-safe: one listener thread owns it. The connection
 * must be in auto-commit mode (the poll statement would otherwise open a transaction).
 * <p>
 * The PostgreSQL JDBC driver is an optional dependency of foundation; a user of this class needs it on the classpath.
 */
public final class PostgresqlNotificationDrain implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlNotificationDrain.class);

    private static final long MIN_POLL_NANOS = 1_000_000L;
    private static final long MAX_POLL_NANOS = 50_000_000L;

    /** Logged once per JVM, not once per reconnect */
    private static volatile boolean warned;

    /** How a drain takes notifications off its connection */
    public enum Mode {
        /** {@code getNotifications(timeout)} with pgjdbc's socket peek pinned off */
        PINNED_PEEK,
        /** A trivial statement whose response carries the queued notifications */
        QUERY_POLL
    }

    private final Mode              mode;
    private final PGConnection      pg;
    private final BaseConnection    base;
    /** {@link Mode#PINNED_PEEK} only */
    private final PGStream          stream;
    /** {@link Mode#QUERY_POLL} only */
    private final PreparedStatement statement;
    private       long              pollNanos = MIN_POLL_NANOS;
    private       boolean           closed;

    private PostgresqlNotificationDrain(Mode mode, PGConnection pg, BaseConnection base, PGStream stream, PreparedStatement statement) {
        this.mode = mode;
        this.pg = pg;
        this.base = base;
        this.stream = stream;
        this.statement = statement;
    }

    /**
     * Pins pgjdbc's notification peek on {@code connection}, or falls back to {@link Mode#QUERY_POLL} (with a one-time
     * WARN) if pgjdbc's internals do not have the expected shape.
     *
     * @param connection the dedicated LISTEN connection (a pool's wrapper is fine: it is unwrapped)
     * @return the drain; {@link #close()} it before the connection is released
     * @throws SQLException if the connection cannot be unwrapped to pgjdbc's, or the poll statement cannot be prepared
     */
    public static PostgresqlNotificationDrain open(Connection connection) throws SQLException {
        requireNonNull(connection, "No connection provided");
        var pg   = connection.unwrap(PGConnection.class);
        var base = connection.unwrap(BaseConnection.class);
        try {
            var stream = stream(base);
            nextCheckField().setLong(stream, Long.MAX_VALUE);
            return new PostgresqlNotificationDrain(Mode.PINNED_PEEK, pg, base, stream, null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                log.warn("Cannot pin pgjdbc's notification peek ({}); LISTEN connections poll with a query instead", e.toString());
            }
            return queryPoll(connection, pg, base);
        }
    }

    /**
     * {@link Mode#QUERY_POLL} even where the peek can be pinned; for tests and for comparing the two.
     *
     * @param connection the dedicated LISTEN connection, in auto-commit mode
     * @return the drain; {@link #close()} it before the connection is released
     * @throws SQLException if the connection cannot be unwrapped to pgjdbc's, or the poll statement cannot be prepared
     */
    public static PostgresqlNotificationDrain openQueryPoll(Connection connection) throws SQLException {
        requireNonNull(connection, "No connection provided");
        return queryPoll(connection, connection.unwrap(PGConnection.class), connection.unwrap(BaseConnection.class));
    }

    private static PostgresqlNotificationDrain queryPoll(Connection connection, PGConnection pg, BaseConnection base) throws SQLException {
        return new PostgresqlNotificationDrain(Mode.QUERY_POLL, pg, base, null, connection.prepareStatement("SELECT 1"));
    }

    /** @return how this drain takes notifications off its connection */
    public Mode mode() {
        return mode;
    }

    /**
     * Blocks for up to {@code timeoutMillis} for the first notification, then returns it with every other one that has
     * already arrived.
     *
     * @param timeoutMillis how long to wait for the first notification; must be positive
     * @return the notifications received, possibly empty or {@code null} (pgjdbc's convention) when none arrived in time
     * @throws SQLException if the connection failed
     */
    public PGNotification[] next(int timeoutMillis) throws SQLException {
        if (mode == Mode.PINNED_PEEK) {
            return pg.getNotifications(timeoutMillis);
        }
        var deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (true) {
            statement.execute();
            var notifications = base.getQueryExecutor().getNotifications();
            if (notifications.length > 0) {
                pollNanos = MIN_POLL_NANOS;
                return notifications;
            }
            if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted()) {
                return notifications;
            }
            LockSupport.parkNanos(this, pollNanos);
            pollNanos = Math.min(MAX_POLL_NANOS, pollNanos * 2);
        }
    }

    /** Unpins the peek, or closes the poll statement. Idempotent, never throws */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (mode == Mode.PINNED_PEEK) {
            try {
                nextCheckField().setLong(stream, 0);
            } catch (ReflectiveOperationException | RuntimeException e) {
                log.debug("Unpinning pgjdbc's notification peek failed", e);
            }
        } else {
            try {
                statement.close();
            } catch (SQLException e) {
                log.debug("Closing the notification poll statement failed", e);
            }
        }
    }

    static PGStream stream(BaseConnection base) throws ReflectiveOperationException {
        var executor    = base.getQueryExecutor();
        var streamField = QueryExecutorBase.class.getDeclaredField("pgStream");
        streamField.setAccessible(true);
        return (PGStream) streamField.get(executor);
    }

    static Field nextCheckField() throws ReflectiveOperationException {
        var next = PGStream.class.getDeclaredField("nextStreamAvailableCheckTime");
        next.setAccessible(true);
        return next;
    }
}
