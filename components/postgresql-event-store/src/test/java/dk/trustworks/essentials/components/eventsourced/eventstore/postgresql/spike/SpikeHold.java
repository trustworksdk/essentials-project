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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;

/**
 * A deterministic hold inside a statement, after the queue's {@code StatementHold}: {@code spike_hold()} waits for an
 * advisory lock that {@link #arm} takes on a connection of its own, and {@link #release} lets every held call through
 * by closing that connection. Unarmed, {@code spike_hold()} returns at once.
 * <p>
 * Unlike the queue's version there is no {@code BEGIN ... EXCEPTION} block in the function: that opens a
 * subtransaction, and nothing in a spike about xid assignment should do anything that might touch the transaction
 * state. The backstop is the held connection's {@code statement_timeout} instead.
 */
final class SpikeHold implements AutoCloseable {
    static final int LOCK_KEY = 731_802;

    private final Connection holder;

    private SpikeHold(Connection holder) {
        this.holder = holder;
    }

    /** Create (or replace) {@code spike_hold() RETURNS boolean}; always true once it returns. */
    static void install(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                              CREATE OR REPLACE FUNCTION spike_hold() RETURNS boolean LANGUAGE plpgsql VOLATILE AS $$
                              BEGIN
                                  PERFORM pg_advisory_lock_shared(%1$d);
                                  PERFORM pg_advisory_unlock_shared(%1$d);
                                  RETURN true;
                              END $$""".formatted(LOCK_KEY));
        }
    }

    static SpikeHold arm(PostgreSQLContainer postgres) throws SQLException {
        var holder = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (var statement = holder.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + LOCK_KEY + ")");
        } catch (SQLException | RuntimeException e) {
            holder.close();
            throw e;
        }
        return new SpikeHold(holder);
    }

    void release() throws SQLException {
        holder.close();
    }

    @Override
    public void close() throws SQLException {
        release();
    }

    /** How many backends wait in {@code spike_hold()} now. */
    static int held(Connection observer) throws SQLException {
        return (int) SpikePostgres.queryLong(observer, "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND classid = 0 AND objid = " + LOCK_KEY);
    }
}
