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

package dk.trustworks.essentials.components.queue.shardowned;

import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;

import static dk.trustworks.essentials.components.queue.shardowned.SingleRowQueries.queryForInt;

/**
 * Holds an engine statement inside a test trigger until the test releases it, instead of for a fixed {@code pg_sleep}.
 * <p>
 * A trigger that sleeps a fixed time has to be sized for the slowest build, and every build then waits it out in full -
 * often twice, as {@code DROP TRIGGER} waits for the sleeping statement too. Here the trigger calls
 * {@code PERFORM test_statement_hold(<bound in seconds>)}, which waits for a PostgreSQL advisory lock that {@link #arm}
 * takes on a connection of its own, and {@link #release} lets every held statement through at once by closing that
 * connection. The bound stays as a backstop: a statement no test releases goes on after the bound, exactly as it did
 * after its {@code pg_sleep}, so a test that fails before its release still tears down. Release before dropping the
 * trigger, or the drop waits for the bound.
 * <p>
 * A statement held here waits on the advisory lock; {@link #aStatementIsHeld} finds it in {@code pg_locks}.
 */
final class StatementHold implements AutoCloseable {
    /** The advisory lock key the hold waits on. Small, so {@code pg_locks.classid} is 0 and {@code objid} is the key. */
    static final int LOCK_KEY = 731_801;

    private final Connection holder;

    private StatementHold(Connection holder) {
        this.holder = holder;
    }

    /**
     * Create (or replace) the {@code test_statement_hold(double precision)} function, take its lock on a connection to
     * {@code postgres} of its own, and return the hold. Arm it before the trigger that calls the function can fire: an
     * unarmed hold lets the statement through at once.
     */
    static StatementHold arm(PostgreSQLContainer postgres) throws SQLException {
        var holder = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (var statement = holder.createStatement()) {
            // Shared, and given back at once: every held statement goes on together when the test releases, and none of
            // them keeps the lock for the rest of its transaction, where a later arm would wait for it. The lock_timeout
            // is the bound, set for the wait alone and put back afterwards.
            statement.execute("""
                              CREATE OR REPLACE FUNCTION test_statement_hold(bound_seconds double precision) RETURNS void
                              LANGUAGE plpgsql AS $$
                              DECLARE
                                  previous text := current_setting('lock_timeout');
                              BEGIN
                                  PERFORM set_config('lock_timeout', ceil(bound_seconds * 1000)::bigint || 'ms', true);
                                  BEGIN
                                      PERFORM pg_advisory_lock_shared(%1$d);
                                      PERFORM pg_advisory_unlock_shared(%1$d);
                                  EXCEPTION WHEN lock_not_available THEN
                                      NULL;
                                  END;
                                  PERFORM set_config('lock_timeout', previous, true);
                              END $$""".formatted(LOCK_KEY));
            statement.execute("SELECT pg_advisory_lock(" + LOCK_KEY + ")");
        } catch (SQLException | RuntimeException e) {
            holder.close();
            throw e;
        }
        return new StatementHold(holder);
    }

    /** {@code PERFORM test_statement_hold(<bound>)}, for a trigger body. */
    static String performHold(Duration bound) {
        return "PERFORM test_statement_hold(" + bound.toMillis() / 1000.0 + ");";
    }

    /** {@code PERFORM test_statement_hold(<boundSeconds>)}, for a trigger body. */
    static String performHold(double boundSeconds) {
        return "PERFORM test_statement_hold(" + boundSeconds + ");";
    }

    /** Let every statement held now, and every one the trigger holds from now on, through. Idempotent. */
    void release() throws SQLException {
        holder.close();
    }

    @Override
    public void close() throws SQLException {
        release();
    }

    /** Whether a statement is waiting in {@code test_statement_hold} now. */
    static boolean aStatementIsHeld(DataSource dataSource) throws SQLException {
        return statementsHeld(dataSource) > 0;
    }

    /** How many statements are waiting in {@code test_statement_hold} now. */
    static int statementsHeld(DataSource dataSource) throws SQLException {
        return queryForInt(dataSource, "Counting the backends waiting in test_statement_hold",
                           "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND classid = 0 AND objid = ?",
                           LOCK_KEY);
    }
}
