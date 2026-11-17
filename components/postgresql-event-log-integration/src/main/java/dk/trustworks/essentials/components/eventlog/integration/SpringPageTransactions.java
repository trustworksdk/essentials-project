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

package dk.trustworks.essentials.components.eventlog.integration;

import dk.trustworks.essentials.components.eventlog.PageTransactionSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.Set;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Page transactions from Spring's transaction management (design phase 8 §2.4): each page is a programmatic
 * {@code PROPAGATION_REQUIRES_NEW} transaction of {@code transactionManager}, its connection obtained with
 * {@link DataSourceUtils#getConnection(DataSource)}, so Spring-managed data access on the delivery thread (a
 * {@code JdbcTemplate}, a Spring-aware unit of work) joins the page. The engine runs the handler, then the progress
 * update on that connection, then {@link OpenPage#commit()}, which commits through the transaction manager.
 * {@code resource(TransactionStatus.class)} is the page's status, wrapped so that {@code setRollbackOnly()} is refused:
 * a handler fails the page by throwing.
 */
public final class SpringPageTransactions implements PageTransactionSource {
    private final PlatformTransactionManager transactionManager;
    private final DataSource                 dataSource;
    private final Set<IntegrationMutant>     mutants;
    private final DefaultTransactionDefinition definition = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    /**
     * @param dataSource the transaction manager's data source, on the event log's database
     */
    public SpringPageTransactions(PlatformTransactionManager transactionManager, DataSource dataSource) {
        this(transactionManager, dataSource, Set.of());
    }

    SpringPageTransactions(PlatformTransactionManager transactionManager, DataSource dataSource, Set<IntegrationMutant> mutants) {
        this.transactionManager = requireNonNull(transactionManager, "No transactionManager provided");
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.mutants = Set.copyOf(mutants);
    }

    @Override
    public OpenPage begin() throws SQLException {
        TransactionStatus status;
        try {
            status = transactionManager.getTransaction(definition);
        } catch (TransactionException e) {
            throw new SQLTransientConnectionException("Beginning a page transaction failed", "08001", e);
        }
        try {
            return new Open(status, DataSourceUtils.getConnection(dataSource));
        } catch (RuntimeException e) {
            transactionManager.rollback(status);
            throw new SQLTransientConnectionException("Getting a page connection failed", "08001", e);
        }
    }

    private final class Open implements OpenPage {
        private final TransactionStatus status;
        private final Connection        connection;

        Open(TransactionStatus status, Connection connection) {
            this.status = status;
            this.connection = connection;
        }

        @Override
        public Connection connection() {
            return connection;
        }

        @Override
        public <T> T resource(Class<T> type) {
            if (type == TransactionStatus.class) {
                return type.cast(mutants.contains(IntegrationMutant.HANDLER_COMMITS_EARLY) ? status : refusingRollbackOnly(status));
            }
            throw new IllegalArgumentException("A Spring page binds a TransactionStatus, not " + type.getName());
        }

        @Override
        public void commit() throws SQLException {
            try {
                transactionManager.commit(status);
            } catch (TransactionException e) {
                throw new SQLException("Committing a page transaction failed", e);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }

        @Override
        public void rollback() {
            try {
                transactionManager.rollback(status);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
    }

    private static TransactionStatus refusingRollbackOnly(TransactionStatus status) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("setRollbackOnly")) {
                throw new IllegalStateException("A page handler fails its page by throwing, not with setRollbackOnly()");
            }
            if (method.getName().equals("equals")) {
                return proxy == args[0];
            }
            if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            try {
                return method.invoke(status, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (TransactionStatus) Proxy.newProxyInstance(SpringPageTransactions.class.getClassLoader(), new Class<?>[]{TransactionStatus.class}, handler);
    }
}
