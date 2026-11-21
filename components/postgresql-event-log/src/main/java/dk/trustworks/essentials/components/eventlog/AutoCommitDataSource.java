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

import org.slf4j.*;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.logging.Logger;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The engine's view of the application's {@link DataSource}: every borrowed connection starts in auto-commit, as every
 * statement the engine runs outside its own transactions assumes. A pool configured to hand out connections with
 * auto-commit off (for example {@code spring.datasource.hikari.auto-commit=false}, a common Hibernate setting) would
 * otherwise leave those writes uncommitted, and the pool rolls them back on return: a parked event's row vanished while
 * the event counted as handled.
 * <p>
 * A connection that already is in auto-commit - the usual case - is returned as is, at the cost of one
 * {@code getAutoCommit()}. Only a connection switched on here is wrapped, so that closing it hands it back to the pool
 * with auto-commit off again, as the next borrower expects.
 */
final class AutoCommitDataSource implements DataSource {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(AutoCommitDataSource.class);

    private final DataSource delegate;

    private AutoCommitDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    /** @return {@code dataSource} wrapped, or as is when it already is one */
    static DataSource wrap(DataSource dataSource) {
        requireNonNull(dataSource, "No dataSource provided");
        return dataSource instanceof AutoCommitDataSource ? dataSource : new AutoCommitDataSource(dataSource);
    }

    /** @return the application's data source this one wraps */
    DataSource delegate() {
        return delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return inAutoCommit(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return inAutoCommit(delegate.getConnection(username, password));
    }

    private static Connection inAutoCommit(Connection connection) throws SQLException {
        try {
            if (connection.getAutoCommit()) {
                return connection;
            }
            connection.setAutoCommit(true);
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException | RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, new RestoreOnClose(connection));
    }

    /** Closing restores auto-commit off first; everything else goes to the connection */
    private static final class RestoreOnClose implements InvocationHandler {
        private final Connection connection;

        RestoreOnClose(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "close" -> {
                    if (!connection.isClosed()) {
                        try {
                            if (connection.getAutoCommit()) {
                                connection.setAutoCommit(false);
                            }
                        } catch (SQLException | RuntimeException e) {
                            // A broken connection: the pool discards it anyway
                            log.debug("Restoring auto-commit off before returning a connection failed", e);
                        }
                    }
                    connection.close();
                    return null;
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return connection.toString();
                }
                default -> {
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || delegate.isWrapperFor(type);
    }
}
