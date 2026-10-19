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

import javax.sql.DataSource;
import java.sql.*;

/**
 * The one-row reads the shard-owned tests make against the database - a count, a sequence's value, one column of one
 * lease row - each with the check that the row is there. A {@code resultSet.next()} that returns {@code false} on an
 * empty result leaves the cursor past the end, on no row; when that answer goes unchecked, the column read after it
 * fails with a driver error that names neither the query nor what the test was reading. Every read here throws an {@link IllegalStateException} reading
 * {@code "<description> returned no row"} instead, so a test reads a single value through this class rather than
 * walking a {@link ResultSet} itself.
 * <p>
 * {@code description} says what the test was reading, in the words of the test - {@code "Counting the instance rows of
 * queue 3"}. {@code parameters} are bound in order with {@link PreparedStatement#setObject(int, Object)}, so a
 * {@code short} queue id binds as a {@code smallint}, an {@code int} unit as an {@code integer}.
 */
final class SingleRowQueries {

    /** Reads what the test wants from the row the result set is on. */
    @FunctionalInterface
    interface RowReader<T> {
        T read(ResultSet row) throws SQLException;
    }

    private SingleRowQueries() {
    }

    /** The first column of the query's first row, as an {@code int}. */
    static int queryForInt(DataSource dataSource, String description, String sql, Object... parameters) throws SQLException {
        return queryForRow(dataSource, description, sql, row -> row.getInt(1), parameters);
    }

    /** The first column of the query's first row, as a {@code long}. */
    static long queryForLong(DataSource dataSource, String description, String sql, Object... parameters) throws SQLException {
        return queryForRow(dataSource, description, sql, row -> row.getLong(1), parameters);
    }

    /** The first column of the query's first row, as a {@code String}. */
    static String queryForString(DataSource dataSource, String description, String sql, Object... parameters) throws SQLException {
        return queryForRow(dataSource, description, sql, row -> row.getString(1), parameters);
    }

    /** What {@code reader} reads from the query's first row, on a connection of its own from {@code dataSource}. */
    static <T> T queryForRow(DataSource dataSource, String description, String sql, RowReader<T> reader,
                             Object... parameters) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return queryForRow(connection, description, sql, reader, parameters);
        }
    }

    /** What {@code reader} reads from the query's first row, on {@code connection}, which stays open. */
    static <T> T queryForRow(Connection connection, String description, String sql, RowReader<T> reader,
                             Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (var resultSet = statement.executeQuery()) {
                return reader.read(firstRow(resultSet, description));
            }
        }
    }

    /**
     * Moves {@code resultSet} onto its first row and returns it, or throws when there is none - for a test that has to
     * run the statement itself (a transaction it rolls back, parameters it binds by hand).
     */
    static ResultSet firstRow(ResultSet resultSet, String description) throws SQLException {
        if (!resultSet.next()) {
            throw new IllegalStateException(description + " returned no row");
        }
        return resultSet;
    }
}
