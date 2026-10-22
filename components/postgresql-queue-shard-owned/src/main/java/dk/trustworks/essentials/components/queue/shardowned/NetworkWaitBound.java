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

import org.slf4j.*;

import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * A bound on how long one thread waits for the database to answer, set on every connection {@link ShardOwnedStorage}
 * hands that thread while it runs the work given to {@link #during}: {@link Connection#setNetworkTimeout} (JDBC 4.1),
 * the connection's own value read first and set back as the connection is closed, so a pooled connection goes back to
 * its pool as it came out. Set back here rather than left to the pool: the engine knows no pool, and not every pool
 * resets it. Only ever tightened: a connection whose own network timeout is already no longer than the bound - the
 * {@code socketTimeout} a deployer set on the {@code DataSource}, which the driver reports as it - is handed over
 * untouched.
 * <p>
 * For the database work a stop leaves running on a thread it stops waiting for ({@code ShardOwnedQueue}'s
 * {@code withinShutdownBudget}). On a connection that hangs - a half-open socket, with no {@code socketTimeout} on the
 * {@code DataSource} - a statement there otherwise never returns, and that thread holds its pool connection for the life
 * of the process. Also for the hand-back of an ordered unit a stop kept ({@code ShardOwnedQueue}'s {@code handBackKeptUnit}),
 * on whichever thread runs it: hung there, it held its claim of the unit, and the instance's membership row, for as long.
 * Past the bound the driver stops waiting and throws, as on a socket timeout. The bound applies to each
 * wait for an answer, not to the work as a whole.
 * <p>
 * A connection on which the bound cannot be read or set - a driver without it ({@link SQLFeatureNotSupportedException}),
 * or any other failure - is handed over as it is, with no bound, and its work waits as long as it did before.
 */
final class NetworkWaitBound {
    private static final Logger                log   = LoggerFactory.getLogger(NetworkWaitBound.class);
    private static final ScopedValue<Duration> BOUND = ScopedValue.newInstance();

    private NetworkWaitBound() {
    }

    /**
     * Run {@code work} on the calling thread, every connection {@link ShardOwnedStorage} hands it meanwhile bounded by
     * {@code bound}. Not inherited by a thread {@code work} starts.
     */
    static void during(Duration bound, Runnable work) {
        requireNonNull(bound, "No bound provided");
        requireNonNull(work, "No work provided");
        requireTrue(bound.isPositive(), "The bound must be positive");
        ScopedValue.where(BOUND, bound).run(work);
    }

    /**
     * {@code connection} as it is outside {@link #during}, and inside it when its own network timeout is already no
     * longer than the bound; otherwise (none, or a longer one) with its network timeout set to the bound, and set back to
     * what it was when it is closed.
     * <p>
     * When it is bounded (the network timeout set here), only a close of the connection returned here sets the timeout
     * back. {@link Connection#unwrap} to {@link Connection} (or {@link Wrapper}) then returns that same connection, but one
     * reached any other way is a connection under it, whose close does not: one
     * unwrapped to a driver's own type ({@code PGConnection}) or from a {@link Statement#getConnection()} of a statement
     * it made (statements are not wrapped). The caller closes the connection it was handed, never one of those: with a
     * pool that does not reset the network timeout itself, the bound may otherwise stay on the connection when it goes
     * back to its pool.
     */
    // This class's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees its
    // interface, which the context loader of the thread asking need not.
    @SuppressWarnings("PMD.UseProperClassLoader")
    static Connection bound(Connection connection) {
        if (!BOUND.isBound()) {
            return connection;
        }
        var bound = (int) Math.min(Integer.MAX_VALUE, Math.max(1, BOUND.get().toMillis()));
        int previous;
        try {
            previous = connection.getNetworkTimeout();
            if (previous > 0 && previous <= bound) {
                // Already as tight or tighter (a socketTimeout on the DataSource, say): only ever tighten.
                return connection;
            }
            connection.setNetworkTimeout(Runnable::run, bound);
        } catch (SQLException | RuntimeException e) {
            log.debug("Could not bound the network waits of a connection to {} ms; it is used without that bound", bound, e);
            return connection;
        }
        return (Connection) Proxy.newProxyInstance(NetworkWaitBound.class.getClassLoader(), new Class<?>[]{Connection.class},
                                                   (proxy, method, args) -> invoke(connection, previous, proxy, method, args));
    }

    // Identity is what equals() means for the proxy: proxy.equals(...) would come back here.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static Object invoke(Connection connection, int previous, Object proxy, Method method, Object... args) throws Throwable {
        switch (method.getName()) {
            case "equals" -> {
                if (method.getParameterCount() == 1) {
                    return proxy == args[0];
                }
            }
            case "hashCode" -> {
                if (method.getParameterCount() == 0) {
                    return System.identityHashCode(proxy);
                }
            }
            case "close" -> {
                if (method.getParameterCount() == 0) {
                    restore(connection, previous);
                }
            }
            // Unwrapped to a type the proxy is (Connection, Wrapper, AutoCloseable), the caller gets the proxy, whose close
            // sets the timeout back; the connection's own unwrap would hand out itself or a connection under it, and a close
            // of either would not. A driver's own type (pgjdbc's PGConnection) still goes to the connection.
            case "unwrap" -> {
                if (method.getParameterCount() == 1 && args[0] instanceof Class<?> iface && iface.isInstance(proxy)) {
                    return proxy;
                }
            }
            case "isWrapperFor" -> {
                if (method.getParameterCount() == 1 && args[0] instanceof Class<?> iface && iface.isInstance(proxy)) {
                    return true;
                }
            }
            default -> {
                // Every other method goes to the connection as it is.
            }
        }
        try {
            return method.invoke(connection, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * Set the connection's network timeout back to what it was before {@link #bound}. Not on a connection already closed -
     * the driver may close one whose wait ran past the bound - nor fatal when it fails: the connection is closed next
     * either way.
     */
    private static void restore(Connection connection, int previous) {
        try {
            if (!connection.isClosed()) {
                connection.setNetworkTimeout(Runnable::run, previous);
            }
        } catch (SQLException | RuntimeException e) {
            log.debug("Could not set the network timeout of a connection back to {} ms before closing it", previous, e);
        }
    }
}
