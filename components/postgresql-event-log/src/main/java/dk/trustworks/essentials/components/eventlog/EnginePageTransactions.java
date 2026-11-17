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

import javax.sql.DataSource;
import java.sql.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@link PageTransactionSource#engine()}: a connection of the event log's {@code DataSource}, autocommit off (phase 4
 * §3.1). {@link #UNBOUND} stands for it until a subscription binds it to its event log's {@code DataSource}
 */
final class EnginePageTransactions implements PageTransactionSource {
    static final EnginePageTransactions UNBOUND = new EnginePageTransactions(null);

    private final DataSource dataSource;

    private EnginePageTransactions(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** @return the engine source on {@code dataSource} */
    static EnginePageTransactions on(DataSource dataSource) {
        return new EnginePageTransactions(requireNonNull(dataSource, "No dataSource provided"));
    }

    /** @return whether {@code source} is the engine's own (absent, or {@link PageTransactionSource#engine()}) */
    static boolean isEngine(PageTransactionSource source) {
        return source == null || source instanceof EnginePageTransactions;
    }

    @Override
    public OpenPage begin() throws SQLException {
        if (dataSource == null) {
            throw new IllegalStateException("PageTransactionSource.engine() is bound to an event log by EventLog.subscribe");
        }
        var connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
        } catch (SQLException | RuntimeException e) {
            close(connection);
            throw e;
        }
        return new Open(connection);
    }

    private static void close(Connection connection) {
        try {
            if (!connection.isClosed()) {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            // closed below anyway
        }
        try {
            connection.close();
        } catch (SQLException e) {
            // nothing more to do
        }
    }

    private record Open(Connection connection) implements OpenPage {
        @Override
        public <T> T resource(Class<T> type) {
            throw new IllegalArgumentException("The engine's page transactions bind no " + type.getName());
        }

        @Override
        public void commit() throws SQLException {
            try {
                connection.commit();
            } finally {
                close(connection);
            }
        }

        @Override
        public void rollback() {
            try {
                connection.rollback();
            } catch (SQLException e) {
                throw new IllegalStateException("Rolling back a page transaction failed", e);
            } finally {
                close(connection);
            }
        }
    }
}
