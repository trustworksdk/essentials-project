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

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class AutoCommitDataSourceTest {

    /** A connection that records its auto-commit changes and its close */
    static final class FakeConnection {
        boolean            autoCommit;
        boolean            closed;
        final List<String> calls = new ArrayList<>();
        final Connection   connection;

        FakeConnection(boolean autoCommit) {
            this.autoCommit = autoCommit;
            this.connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getAutoCommit" -> {
                        return this.autoCommit;
                    }
                    case "setAutoCommit" -> {
                        this.autoCommit = (Boolean) args[0];
                        calls.add("setAutoCommit(" + args[0] + ")");
                        return null;
                    }
                    case "isClosed" -> {
                        return closed;
                    }
                    case "close" -> {
                        closed = true;
                        calls.add("close");
                        return null;
                    }
                    default -> {
                        calls.add(method.getName());
                        return null;
                    }
                }
            });
        }
    }

    static DataSource handingOut(FakeConnection fake) {
        return new DataSource() {
            public Connection getConnection() {
                return fake.connection;
            }

            public Connection getConnection(String username, String password) {
                return fake.connection;
            }

            public PrintWriter getLogWriter() {
                return null;
            }

            public void setLogWriter(PrintWriter out) {
            }

            public void setLoginTimeout(int seconds) {
            }

            public int getLoginTimeout() {
                return 0;
            }

            public Logger getParentLogger() {
                return null;
            }

            public <T> T unwrap(Class<T> type) {
                return null;
            }

            public boolean isWrapperFor(Class<?> type) {
                return false;
            }
        };
    }

    @Test
    void a_connection_already_in_auto_commit_is_handed_out_as_is() throws SQLException {
        var fake = new FakeConnection(true);
        assertThat(AutoCommitDataSource.wrap(handingOut(fake)).getConnection()).isSameAs(fake.connection);
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void a_connection_handed_out_with_auto_commit_off_is_switched_on_and_handed_back_off() throws SQLException {
        // The pool's next borrower expects the auto-commit it configured (the wake listener left it on: c3fdf0/F-084)
        var fake = new FakeConnection(false);
        var connection = AutoCommitDataSource.wrap(handingOut(fake)).getConnection();
        assertThat(fake.autoCommit).isTrue();

        connection.prepareStatement("SELECT 1");
        connection.close();

        assertThat(fake.calls).containsExactly("setAutoCommit(true)", "prepareStatement", "setAutoCommit(false)", "close");
        assertThat(connection).isEqualTo(connection).isNotEqualTo(fake.connection);
    }

    @Test
    void a_connection_the_engine_left_in_a_transaction_is_handed_back_as_it_is() throws SQLException {
        var fake = new FakeConnection(false);
        var connection = AutoCommitDataSource.wrap(handingOut(fake)).getConnection();
        connection.setAutoCommit(false);
        connection.close();
        assertThat(fake.calls).containsExactly("setAutoCommit(true)", "setAutoCommit(false)", "close");
    }
}
