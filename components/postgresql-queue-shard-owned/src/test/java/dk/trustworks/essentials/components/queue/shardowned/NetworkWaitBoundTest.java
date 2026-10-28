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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link NetworkWaitBound} on a fake {@link Connection} that records what is asked of it: no bound outside
 * {@link NetworkWaitBound#during}; inside it the bound is set on a connection with no network timeout or a longer one, and
 * set back as the connection is closed, before the connection's own close - what a pool that does not reset the timeout
 * itself relies on, which the ITs' Hikari pool (it does reset it) cannot show. A connection whose own timeout is already
 * no longer than the bound (a {@code socketTimeout} on the {@code DataSource}) is handed over untouched: the bound only
 * ever tightens. Unwrapped to {@link Connection} (or {@link Wrapper}), the bound connection gives itself, so a close of
 * what came back still sets the timeout back; unwrapped to a driver's own type it reaches the driver, as before.
 */
// The connections the tests take are fakes holding nothing to close; a test that closes one does so to assert what the
// close does. Identity is the correct equals() for a proxy.
@SuppressWarnings({"PMD.CloseResource", "PMD.CompareObjectsWithEquals"})
class NetworkWaitBoundTest {

    private static final Duration BOUND        = Duration.ofSeconds(30);
    private static final int      BOUND_MILLIS = 30_000;

    @Test
    void outside_during_the_connection_is_handed_over_as_it_is() throws SQLException {
        var fake = new FakeConnection(0);

        var connection = NetworkWaitBound.bound(fake.connection);

        assertThat(connection).isSameAs(fake.connection);
        connection.close();
        assertThat(fake.calls).containsExactly("close");
    }

    @Test
    void inside_during_a_connection_with_no_network_timeout_gets_the_bound_and_has_it_set_back_before_it_is_closed() throws SQLException {
        var fake = new FakeConnection(0);

        var connection = boundDuring(fake);

        assertThat(connection).isNotSameAs(fake.connection);
        assertThat(fake.networkTimeout).isEqualTo(BOUND_MILLIS);
        assertThat(connection.getNetworkTimeout()).isEqualTo(BOUND_MILLIS);
        connection.close();
        assertThat(fake.calls).containsExactly("getNetworkTimeout", "setNetworkTimeout(30000)", "getNetworkTimeout",
                                               "isClosed", "setNetworkTimeout(0)", "close");
        assertThat(fake.networkTimeout).isZero();
    }

    @Test
    void inside_during_a_connection_with_a_longer_network_timeout_gets_the_bound_and_its_own_back_as_it_is_closed() throws SQLException {
        var fake = new FakeConnection(60_000);

        var connection = boundDuring(fake);

        assertThat(fake.networkTimeout).isEqualTo(BOUND_MILLIS);
        connection.close();
        assertThat(fake.calls).containsExactly("getNetworkTimeout", "setNetworkTimeout(30000)",
                                               "isClosed", "setNetworkTimeout(60000)", "close");
        assertThat(fake.networkTimeout).isEqualTo(60_000);
    }

    @Test
    void inside_during_a_connection_whose_own_network_timeout_is_tighter_than_the_bound_keeps_it_untouched() throws SQLException {
        var fake = new FakeConnection(3_000);

        var connection = boundDuring(fake);

        assertThat(connection).isSameAs(fake.connection);
        assertThat(fake.networkTimeout).isEqualTo(3_000);
        connection.close();
        assertThat(fake.calls).containsExactly("getNetworkTimeout", "close");
    }

    @Test
    void inside_during_a_connection_whose_own_network_timeout_equals_the_bound_keeps_it_untouched() {
        var fake = new FakeConnection(BOUND_MILLIS);

        var connection = boundDuring(fake);

        assertThat(connection).isSameAs(fake.connection);
        assertThat(fake.calls).containsExactly("getNetworkTimeout");
    }

    @Test
    void a_connection_already_closed_by_the_driver_is_not_set_back() throws SQLException {
        var fake = new FakeConnection(0);
        var connection = boundDuring(fake);

        fake.closed = true;
        connection.close();

        assertThat(fake.calls).containsExactly("getNetworkTimeout", "setNetworkTimeout(30000)", "isClosed", "close");
    }

    @Test
    void a_driver_without_network_timeouts_hands_the_connection_over_as_it_is() throws SQLException {
        var fake = new FakeConnection(0);
        fake.getNetworkTimeoutFailure = new SQLFeatureNotSupportedException("no network timeout");

        var connection = boundDuring(fake);

        assertThat(connection).isSameAs(fake.connection);
        connection.close();
        assertThat(fake.calls).containsExactly("getNetworkTimeout", "close");
    }

    @Test
    void the_bound_connection_equals_only_itself_and_hashes_by_identity() {
        var fake       = new FakeConnection(0);
        var connection = boundDuring(fake);

        assertThat(connection.equals(connection)).isTrue();
        assertThat(connection.equals(fake.connection)).isFalse();
        assertThat(connection.hashCode()).isEqualTo(System.identityHashCode(connection));
    }

    @Test
    void unwrapping_the_bound_connection_to_connection_gives_the_bound_connection_whose_close_sets_the_timeout_back() throws SQLException {
        var fake       = new FakeConnection(0);
        var connection = boundDuring(fake);

        var unwrapped = connection.unwrap(Connection.class);

        assertThat(unwrapped).isSameAs(connection);
        assertThat(connection.unwrap(Wrapper.class)).isSameAs(connection);
        assertThat(connection.unwrap(AutoCloseable.class)).isSameAs(connection);
        assertThat(connection.isWrapperFor(Connection.class)).isTrue();
        unwrapped.close();
        assertThat(fake.calls).containsExactly("getNetworkTimeout", "setNetworkTimeout(30000)",
                                               "isClosed", "setNetworkTimeout(0)", "close");
        assertThat(fake.networkTimeout).isZero();
    }

    @Test
    void unwrapping_the_bound_connection_to_a_driver_type_still_reaches_the_driver() throws SQLException {
        var fake       = new FakeConnection(0);
        var connection = boundDuring(fake);

        assertThat(connection.isWrapperFor(DriverConnection.class)).isTrue();
        assertThat(connection.unwrap(DriverConnection.class)).isSameAs(fake.connection);
        assertThat(connection.isWrapperFor(Runnable.class)).isFalse();
        assertThat(fake.calls).containsExactly("getNetworkTimeout", "setNetworkTimeout(30000)",
                                               "isWrapperFor(DriverConnection)", "unwrap(DriverConnection)", "isWrapperFor(Runnable)");
    }

    @Test
    void inside_call_a_connection_gets_the_bound_and_the_work_returns_its_value() throws SQLException {
        var fake = new FakeConnection(0);

        var connection = NetworkWaitBound.call(BOUND, () -> NetworkWaitBound.bound(fake.connection));

        assertThat(connection).isNotSameAs(fake.connection);
        assertThat(fake.networkTimeout).isEqualTo(BOUND_MILLIS);
        connection.close();
        assertThat(fake.networkTimeout).isZero();
        assertThat(NetworkWaitBound.bound(fake.connection)).as("no bound once call returned").isSameAs(fake.connection);
    }

    @Test
    void call_throws_the_works_checked_exception_as_it_is_and_leaves_no_bound_behind() {
        var fake    = new FakeConnection(0);
        var failure = new SQLException("the database did not answer");

        assertThatThrownBy(() -> NetworkWaitBound.call(BOUND, () -> {
            NetworkWaitBound.bound(fake.connection);
            throw failure;
        })).isSameAs(failure);
        assertThat(fake.networkTimeout).as("bound inside the work").isEqualTo(BOUND_MILLIS);
        assertThat(NetworkWaitBound.bound(fake.connection)).as("no bound once call threw").isSameAs(fake.connection);
    }

    @Test
    void call_refuses_a_bound_that_is_not_positive() {
        assertThatThrownBy(() -> NetworkWaitBound.call(Duration.ZERO, () -> "work")).isInstanceOf(IllegalArgumentException.class);
    }

    private static Connection boundDuring(FakeConnection fake) {
        var bound = new AtomicReference<Connection>();
        NetworkWaitBound.during(BOUND, () -> bound.set(NetworkWaitBound.bound(fake.connection)));
        return bound.get();
    }

    /**
     * A driver's own connection type, which a caller reaches with {@link Connection#unwrap} (pgjdbc's {@code PGConnection}).
     */
    interface DriverConnection {
    }

    /**
     * A {@link Connection} (and a {@link DriverConnection}) that only knows its network timeout, whether it is closed and
     * what it wraps - itself - and records every call made to it other than {@code equals}, {@code hashCode} and
     * {@code toString}.
     */
    private static final class FakeConnection {
        final List<String> calls = new ArrayList<>();
        final Connection   connection;
        int                networkTimeout;
        boolean            closed;
        SQLException       getNetworkTimeoutFailure;

        FakeConnection(int networkTimeout) {
            this.networkTimeout = networkTimeout;
            this.connection = (Connection) Proxy.newProxyInstance(NetworkWaitBoundTest.class.getClassLoader(),
                                                                  new Class<?>[]{Connection.class, DriverConnection.class},
                                                                  (proxy, method, args) -> switch (method.getName()) {
                                                                      case "equals" -> proxy == args[0];
                                                                      case "hashCode" -> System.identityHashCode(proxy);
                                                                      case "toString" -> "FakeConnection";
                                                                      default -> answer(proxy, method.getName(), args);
                                                                  });
        }

        private Object answer(Object proxy, String method, Object... args) throws SQLException {
            switch (method) {
                case "unwrap" -> {
                    var iface = (Class<?>) args[0];
                    calls.add(method + "(" + iface.getSimpleName() + ")");
                    if (!iface.isInstance(proxy)) {
                        throw new SQLException("Not a wrapper for " + iface.getName());
                    }
                    return proxy;
                }
                case "isWrapperFor" -> {
                    var iface = (Class<?>) args[0];
                    calls.add(method + "(" + iface.getSimpleName() + ")");
                    return iface.isInstance(proxy);
                }
                case "getNetworkTimeout" -> {
                    calls.add(method);
                    if (getNetworkTimeoutFailure != null) {
                        throw getNetworkTimeoutFailure;
                    }
                    return networkTimeout;
                }
                case "setNetworkTimeout" -> {
                    calls.add(method + "(" + args[1] + ")");
                    networkTimeout = (Integer) args[1];
                    return null;
                }
                case "isClosed" -> {
                    calls.add(method);
                    return closed;
                }
                case "close" -> {
                    calls.add(method);
                    closed = true;
                    return null;
                }
                default -> throw new UnsupportedOperationException(method);
            }
        }
    }
}
