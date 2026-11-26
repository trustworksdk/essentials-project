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

import java.sql.SQLException;

/**
 * Where an in-transaction subscription's page transactions come from. The engine runs handler,
 * progress update and {@link OpenPage#commit()} itself, in that order, on the page's connection - the progress update is
 * always the last statement before the commit, whatever the source - and calls exactly one of {@link OpenPage#commit()}
 * and {@link OpenPage#rollback()}. A source only says how to obtain the connection and how to end the transaction; it
 * must give a connection to the event log's own database, which {@link EventLog#subscribe} checks.
 */
public interface PageTransactionSource {
    /**
     * @return the default source: a connection of the event log's own {@code DataSource} with autocommit off, which
     * binds no resources
     */
    static PageTransactionSource engine() {
        return EnginePageTransactions.UNBOUND;
    }

    /**
     * Begin a transaction on a connection to the event log's database
     */
    OpenPage begin() throws SQLException;

    /**
     * A begun page transaction. {@link #connection()} is the raw connection: the engine hands the handler a guarded view
     * of it. Both {@link #commit()} and {@link #rollback()} end the transaction and release the connection; when
     * {@link #commit()} throws, the transaction is over too (rolled back or of unknown outcome) and the connection released
     */
    interface OpenPage extends PageTransaction {
        void commit() throws SQLException;

        /** Roll back and release; an unchecked exception tells the engine the connection is unusable (an I/O failure) */
        void rollback();

        /**
         * @return whether the handler marked this transaction rollback-only through a bound resource (a unit of work, a
         * Spring status). The engine asks right after the handler returns and then fails the page as the handler's
         * failure - retried, then parked - instead of committing (which would roll back silently) or failing the commit
         * (which would count as the engine's own I/O and retry forever)
         */
        default boolean rollbackOnly() {
            return false;
        }
    }
}
