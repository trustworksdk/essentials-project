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

package dk.trustworks.essentials.examples.perflab.harness;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A {@link DataSource} that counts the statements executed through its connections, for code that talks plain JDBC and
 * so cannot be counted with a JDBI {@code SqlLogger} (the new event store engine).
 * <p>
 * Counting is off until {@link #startCounting()}. Two counters: every statement execution, and the executions of a
 * {@code SELECT} whose SQL names {@code table} - the same rule as the baseline scenario's JDBI counter, so the two
 * {@code eventStoreSelects*} figures mean the same thing. A batch counts once. Everything else, {@code unwrap} included,
 * goes to the wrapped objects unchanged.
 */
public final class StatementCountingDataSource implements DataSource {
    private final DataSource delegate;
    private final String     tableNameLower;
    private final AtomicLong statements  = new AtomicLong();
    private final AtomicLong tableSelects = new AtomicLong();
    private volatile boolean counting;

    public StatementCountingDataSource(DataSource delegate, String tableName) {
        this.delegate = requireNonNull(delegate, "No delegate provided");
        this.tableNameLower = requireNonNull(tableName, "No tableName provided").toLowerCase(Locale.ROOT);
    }

    /** Reset both counters and start counting */
    public void startCounting() {
        statements.set(0);
        tableSelects.set(0);
        counting = true;
    }

    /** Stop counting; the counters keep their values */
    public void stopCounting() {
        counting = false;
    }

    public long statements() {
        return statements.get();
    }

    public long tableSelects() {
        return tableSelects.get();
    }

    private void record(String sql) {
        if (!counting) {
            return;
        }
        statements.incrementAndGet();
        if (sql != null) {
            var lower = sql.toLowerCase(Locale.ROOT);
            if (lower.contains(tableNameLower) && lower.stripLeading().startsWith("select")) {
                tableSelects.incrementAndGet();
            }
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrapConnection(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrapConnection(delegate.getConnection(username, password));
    }

    private Connection wrapConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(StatementCountingDataSource.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            var result = invoke(connection, method, args);
            return switch (method.getName()) {
                case "createStatement" -> wrapStatement(Statement.class, (Statement) result, null);
                case "prepareStatement" -> wrapStatement(PreparedStatement.class, (Statement) result, (String) args[0]);
                case "prepareCall" -> wrapStatement(CallableStatement.class, (Statement) result, (String) args[0]);
                default -> result;
            };
        });
    }

    private Object wrapStatement(Class<? extends Statement> type, Statement statement, String preparedSql) {
        return Proxy.newProxyInstance(StatementCountingDataSource.class.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            var name = method.getName();
            if (name.startsWith("execute")) {
                var sql = args != null && args.length > 0 && args[0] instanceof String text ? text : preparedSql;
                record(sql);
            }
            return invoke(statement, method, args);
        });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
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
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return iface.isInstance(this) ? iface.cast(this) : delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
