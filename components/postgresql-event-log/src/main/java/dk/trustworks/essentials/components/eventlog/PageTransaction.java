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

import java.sql.Connection;

/**
 * The transaction a {@link PageHandler} applies a page in
 */
public interface PageTransaction {
    /**
     * @return the page connection. It refuses {@code commit}, {@code rollback()}, {@code setAutoCommit}, {@code close}
     * and {@code abort}; savepoints work
     */
    Connection connection();

    /**
     * @return what the {@link PageTransactionSource} bound to this transaction as {@code type} (for example a JDBI
     * {@code Handle} or a unit of work)
     * @throws IllegalArgumentException if the source bound nothing of that type
     */
    <T> T resource(Class<T> type);

    /**
     * {@code open} as a page handler sees it: the same transaction and resources, with a connection that refuses
     * {@code commit}, {@code rollback()}, {@code setAutoCommit}, {@code close} and {@code abort}. For code that hands an open
     * page to application code outside a delivered page - a processor's rebuild hook - so that code cannot end the
     * transaction its caller still has to finish
     *
     * @param open the open page; its owner still commits or rolls it back
     * @return the guarded view of {@code open}
     */
    static PageTransaction guarded(PageTransactionSource.OpenPage open) {
        java.util.Objects.requireNonNull(open, "No open page provided");
        return new PageTransaction() {
            private Connection guarded;

            @Override
            public Connection connection() {
                // Wrapped on first use: a hook that only uses a bound resource never pays for it
                if (guarded == null) {
                    guarded = GuardedConnection.of(open.connection());
                }
                return guarded;
            }

            @Override
            public <T> T resource(Class<T> type) {
                return open.resource(type);
            }
        };
    }
}
