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

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;

import static dk.trustworks.essentials.components.queue.shardowned.SingleRowQueries.*;
import static org.assertj.core.api.Assertions.*;

/**
 * {@link SingleRowQueries} is what every shard-owned IT reads a single value through, so the check it exists for - a
 * query that returns no row fails naming what was read, instead of reading a column when the cursor is on no row - is
 * pinned here, without a database: the JDBC objects are proxies holding a fixed result.
 */
class SingleRowQueriesTest {

    @Test
    void a_query_that_returns_no_row_fails_naming_what_was_being_read() {
        var database = new FakeDatabase(List.of());

        assertThatThrownBy(() -> queryForInt(database.dataSource(), "Counting the instance rows of queue 3",
                                             "SELECT count(*) FROM shard_queue_instance WHERE queue_id = ?", (short) 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Counting the instance rows of queue 3 returned no row");
        assertThat(database.closed).as("the statement and the connection are closed on the failure too")
                                   .containsExactly("result set", "statement", "connection");
    }

    @Test
    void a_query_returns_the_first_column_of_its_row_with_the_parameters_bound_in_order() throws SQLException {
        var database = new FakeDatabase(List.of(42L));

        var value = queryForLong(database.dataSource(), "Reading the fence of a lease row",
                                 "SELECT fence FROM shard_queue_lease WHERE queue_id = ? AND lane = ? AND shard = ?",
                                 (short) 1, "ordered", 7);

        assertThat(value).isEqualTo(42L);
        assertThat(database.sql).isEqualTo("SELECT fence FROM shard_queue_lease WHERE queue_id = ? AND lane = ? AND shard = ?");
        assertThat(database.bound).containsExactly(Map.entry(1, (short) 1), Map.entry(2, "ordered"), Map.entry(3, 7));
        assertThat(database.closed).containsExactly("result set", "statement", "connection");
    }

    @Test
    void a_query_on_a_connection_the_caller_owns_leaves_it_open() throws SQLException {
        var database = new FakeDatabase(List.of("instance-1"));

        var owner = queryForRow(database.connection(), "Reading the owner of a lease row", "SELECT owner FROM shard_queue_lease",
                                row -> row.getString(1));

        assertThat(owner).isEqualTo("instance-1");
        assertThat(database.closed).containsExactly("result set", "statement");
    }

    @Test
    void the_first_row_of_an_empty_result_set_is_refused() {
        var database = new FakeDatabase(List.of());

        assertThatThrownBy(() -> firstRow(database.resultSet(), "EXPLAIN ANALYZE of the claim"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("EXPLAIN ANALYZE of the claim returned no row");
    }

    /**
     * A data source whose every query returns {@code rows}, one value per row, recording the SQL it was given, the
     * parameters bound and what was closed.
     */
    private static final class FakeDatabase {
        private final List<Object>                     rows;
        private final List<Map.Entry<Integer, Object>> bound  = new ArrayList<>();
        private final List<String>                     closed = new ArrayList<>();
        private       String                           sql;
        private       int                              position;

        FakeDatabase(List<Object> rows) {
            this.rows = rows;
        }

        DataSource dataSource() {
            return proxy(DataSource.class, (method, args) -> switch (method) {
                case "getConnection" -> connection();
                default -> throw new UnsupportedOperationException(method);
            });
        }

        Connection connection() {
            return proxy(Connection.class, (method, args) -> switch (method) {
                case "prepareStatement" -> {
                    sql = (String) args[0];
                    yield statement();
                }
                case "close" -> closed.add("connection");
                default -> throw new UnsupportedOperationException(method);
            });
        }

        private PreparedStatement statement() {
            return proxy(PreparedStatement.class, (method, args) -> switch (method) {
                case "setObject" -> bound.add(Map.entry((Integer) args[0], args[1]));
                case "executeQuery" -> resultSet();
                case "close" -> closed.add("statement");
                default -> throw new UnsupportedOperationException(method);
            });
        }

        ResultSet resultSet() {
            return proxy(ResultSet.class, (method, args) -> switch (method) {
                case "next" -> next();
                case "getInt", "getLong", "getString" -> rows.get(position - 1);
                case "close" -> closed.add("result set");
                default -> throw new UnsupportedOperationException(method);
            });
        }

        private boolean next() {
            position++;
            return position <= rows.size();
        }

        private interface Answer {
            Object answer(String method, Object... args);
        }

        private static <T> T proxy(Class<T> type, Answer answer) {
            return type.cast(Proxy.newProxyInstance(SingleRowQueriesTest.class.getClassLoader(), new Class<?>[]{type},
                                                    (proxy, method, args) -> {
                                                        var result = answer.answer(method.getName(), args);
                                                        return method.getReturnType() == void.class ? null : result;
                                                    }));
        }
    }
}
