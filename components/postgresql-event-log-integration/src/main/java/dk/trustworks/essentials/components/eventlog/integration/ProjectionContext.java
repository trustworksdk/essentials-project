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

import dk.trustworks.essentials.components.eventlog.PageTransaction;
import org.jdbi.v3.core.Handle;

import java.sql.Connection;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What a {@link ViewProjection} handler writes through (design phase 8 §3.3): the page transaction's resources. Every
 * write commits with the page's progress update, or not at all; none of them can end the transaction (§2.4).
 */
public final class ProjectionContext {
    private final PageTransaction transaction;

    ProjectionContext(PageTransaction transaction) {
        this.transaction = requireNonNull(transaction, "No transaction provided");
    }

    /** @return the page connection: commit, rollback, close and autocommit are refused; savepoints work */
    public Connection connection() {
        return transaction.connection();
    }

    /**
     * @return the JDBI handle of the page's unit of work ({@link UnitOfWorkPageTransactions}); its commit and rollback
     * are refused while the page is open
     * @throws IllegalArgumentException if the page source binds no JDBI handle
     */
    public Handle handle() {
        return transaction.resource(Handle.class);
    }

    /** @return what the page source bound as {@code type}; see {@link PageTransaction#resource(Class)} */
    public <T> T resource(Class<T> type) {
        return transaction.resource(type);
    }

    /** @return the engine's page transaction */
    public PageTransaction transaction() {
        return transaction;
    }
}
