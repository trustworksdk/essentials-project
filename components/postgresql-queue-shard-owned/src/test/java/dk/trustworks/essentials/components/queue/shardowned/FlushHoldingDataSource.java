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

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * A {@link DataSource} that holds one thread of a {@code ShardOwnedQueue} whose name starts with a given prefix - the
 * budget thread of a stop ({@link #STOP_BUDGET_THREAD}), or the thread a kept unit is handed back on as its key leaves
 * the handler ({@link #KEPT_UNIT_HAND_BACK_THREAD}), or a thread the test starts under a name of its own - at two
 * points while told to: as it asks for a connection
 * ({@link #holdConnections}), and, on that connection, as it prepares a delete from the ordered lane - an owner's
 * acknowledgement flush, its batch already taken - until {@link #letAcknowledge}. Only the first such thread that asks
 * for a connection once told to hold is held, so a later one goes through. Every other thread, and the held one once
 * let through, get the delegate's connections as they are. Shared by {@code ShardOwnedLifecycleIT} and
 * {@code ShardOwnedUnsubscribeIT}.
 */
final class FlushHoldingDataSource implements DataSource {
    static final  String                  STOP_BUDGET_THREAD         = "shard-queue-stop-";
    static final  String                  KEPT_UNIT_HAND_BACK_THREAD = "shard-queue-kept-unit-";
    private final DataSource              delegate;
    private final String                  threadNamePrefix;
    private final CountDownLatch          connecting                 = new CountDownLatch(1);
    private final CountDownLatch          connectionHeld             = new CountDownLatch(1);
    private final CountDownLatch          acknowledging              = new CountDownLatch(1);
    private final CountDownLatch          acknowledgeHeld            = new CountDownLatch(1);
    private final AtomicBoolean           holdConnections            = new AtomicBoolean();
    private final AtomicReference<Thread> heldThread                 = new AtomicReference<>();

    FlushHoldingDataSource(DataSource delegate, String threadNamePrefix) {
        this.delegate = delegate;
        this.threadNamePrefix = threadNamePrefix;
    }

    /**
     * Hold the first thread named with the prefix as it asks for a connection, until {@link #letConnect}.
     */
    void holdConnections() {
        holdConnections.set(true);
    }

    void letConnect() {
        holdConnections.set(false);
        connecting.countDown();
    }

    void letAcknowledge() {
        acknowledging.countDown();
    }

    /**
     * Whether the held thread has asked for a connection, and is held there, within {@code timeout}.
     */
    boolean awaitConnectionHeld(Duration timeout) throws InterruptedException {
        return connectionHeld.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Whether the held thread has reached an ordered acknowledgement, and is held there, within {@code timeout}.
     */
    boolean awaitAcknowledgementHeld(Duration timeout) throws InterruptedException {
        return acknowledgeHeld.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Whether this is the thread held here: the first one named with the prefix to ask for a connection once told to
     * hold.
     */
    private boolean onTheHeldThread() {
        var current = Thread.currentThread();
        if (!current.getName().startsWith(threadNamePrefix)) {
            return false;
        }
        if (holdConnections.get()) {
            heldThread.compareAndSet(null, current);
        }
        // Thread does not override equals(): this is the identity check, written the way ShardOwnedLifecycleIT compares threads.
        return current.equals(heldThread.get());
    }

    // Connection's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees
    // its interface, which the context loader need not.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private Connection holding(Connection connection) {
        if (!onTheHeldThread()) {
            return connection;
        }
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName()) && args != null && args[0] instanceof String sql
                            && sql.startsWith("DELETE FROM " + ShardOwnedSchema.ORDERED_TABLE)) {
                        acknowledgeHeld.countDown();
                        awaitQuietly(acknowledging);
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (holdConnections.get() && onTheHeldThread()) {
            connectionHeld.countDown();
            awaitQuietly(connecting);
        }
        return holding(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        if (holdConnections.get() && onTheHeldThread()) {
            connectionHeld.countDown();
            awaitQuietly(connecting);
        }
        return holding(delegate.getConnection(username, password));
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
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
