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

import java.lang.reflect.*;
import java.sql.Connection;
import java.util.Set;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The view of a page transaction's connection that a {@link PageHandler} gets (design phase 4 §3.1): the
 * engine owns the transaction, so {@code commit}, {@code rollback()}, {@code setAutoCommit}, {@code close} and
 * {@code abort} throw {@link IllegalStateException}. A handler that committed would split its output from the resume
 * point, which I12 forbids. Savepoints ({@code rollback(Savepoint)} included) and everything else pass through.
 */
final class GuardedConnection {
    private static final Set<String> REFUSED = Set.of("commit", "setAutoCommit", "close", "abort");

    private GuardedConnection() {
    }

    /**
     * @return a proxy of {@code connection} that refuses to end or change the engine's transaction
     */
    static Connection of(Connection connection) {
        requireNonNull(connection, "No connection provided");
        InvocationHandler handler = (proxy, method, args) -> {
            var name = method.getName();
            if (REFUSED.contains(name) || name.equals("rollback") && method.getParameterCount() == 0) {
                throw new IllegalStateException("The event log owns the page transaction: a page handler must not call Connection." + name +
                                                        " (it commits the page together with the resume point)");
            }
            if (name.equals("unwrap") && args != null && args[0] == Connection.class) {
                return proxy;
            }
            try {
                return method.invoke(connection, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(GuardedConnection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }
}
