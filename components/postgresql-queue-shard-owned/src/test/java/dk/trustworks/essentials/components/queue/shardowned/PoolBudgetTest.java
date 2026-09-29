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

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * The pool-size arithmetic that stops a {@link ShardRuntime} starting on a pool its pumps would
 * exhaust, and the live check that lets a pump say "pool exhausted" instead of "lost its connection".
 * <p>
 * No database, and that is part of what is being tested: the runtime refuses before it asks the
 * {@code DataSource} for anything. A check that needed a connection to run could itself be starved by
 * the condition it exists to catch.
 */
class PoolBudgetTest {

    @Test
    void held_connections_are_one_per_pump_plus_the_listener() {
        assertThat(PoolBudget.heldConnections(2)).isEqualTo(3);
        // pumpThreads is clamped to one pump by the runtime, and the arithmetic must agree with it.
        assertThat(PoolBudget.heldConnections(0)).isEqualTo(2);
    }

    @Test
    void a_pool_that_only_fits_the_held_connections_is_refused() {
        // Exactly the held connections is the trap: the pumps and the listener start, and lease
        // renewal then has nothing to borrow.
        var dataSource = untouchable(new AtomicInteger());
        assertThatThrownBy(() -> PoolBudget.reserve(dataSource, ConnectionPoolMetadata.ofMaximumSize(3), 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at most 3 connections")
                .hasMessageContaining("holds 3 of them permanently");
        // A refused runtime is not counted.
        assertThat(PoolBudget.heldAgainst(dataSource)).isZero();
    }

    @Test
    void a_pool_with_room_to_spare_is_accepted() {
        var dataSource = untouchable(new AtomicInteger());
        // A default Hikari pool against the default two pumps, then a tight one — more than half held
        // is a warning, not a refusal.
        for (var size : new int[]{10, 4}) {
            var reservation = PoolBudget.reserve(dataSource, ConnectionPoolMetadata.ofMaximumSize(size), 2);
            PoolBudget.release(reservation);
        }
        assertThat(PoolBudget.heldAgainst(dataSource)).isZero();
    }

    @Test
    void a_pool_whose_size_is_unknown_or_unbounded_is_not_refused() {
        var dataSource = untouchable(new AtomicInteger());
        for (var pool : new ConnectionPoolMetadata[]{ConnectionPoolMetadata.unknown(), fixed(-1, 0), throwing()}) {
            assertThatCode(() -> PoolBudget.release(PoolBudget.reserve(dataSource, pool, 2)))
                    .as("%s", pool)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void the_share_of_the_pool_is_logged_even_when_it_is_comfortable() {
        // The quiet case used to log nothing, so nobody learnt the number until it was a problem.
        var assessment = PoolBudget.assess(3, 3, OptionalInt.of(20));
        assertThat(assessment.warn()).isFalse();
        assertThat(assessment.message())
                .contains("holding 3 of the connection pool's 20 connections permanently (15%; 2 pump(s) + 1 listener)")
                .contains("leaving 17");
    }

    @Test
    void more_than_half_the_pool_is_a_warning_that_says_how_large_to_make_it() {
        // Two runtimes of three on a default Hikari pool: the trading demo's start-up failure.
        var assessment = PoolBudget.assess(3, 6, OptionalInt.of(10));
        assertThat(assessment.warn()).isTrue();
        assertThat(assessment.message())
                .contains("holding 6 of the connection pool's 10 connections permanently (60%")
                .contains("plus 3 held by other shard-owned queue runtimes")
                .contains("leaving 4")
                .contains("at least 12")
                .contains("spring.datasource.hikari.maximum-pool-size")
                .contains("share one ShardRuntime");
    }

    @Test
    void exactly_half_the_pool_is_not_yet_a_warning() {
        assertThat(PoolBudget.assess(3, 5, OptionalInt.of(10)).warn()).isFalse();
        assertThat(PoolBudget.assess(3, 3, OptionalInt.of(6)).warn()).isFalse();
    }

    @Test
    void a_full_pool_is_a_warning_that_leaves_nothing() {
        var assessment = PoolBudget.assess(3, 6, OptionalInt.of(5));
        assertThat(assessment.warn()).isTrue();
        assertThat(assessment.message()).contains("leaving nothing");
    }

    @Test
    void an_unknown_pool_size_still_reports_what_is_held_and_what_to_size_for() {
        var assessment = PoolBudget.assess(3, 3, OptionalInt.empty());
        assertThat(assessment.warn()).isFalse();
        assertThat(assessment.message())
                .contains("holding 3 connection(s)")
                .contains("not known")
                .contains("at least 6");
    }

    @Test
    void runtimes_on_one_data_source_are_added_up_and_only_ever_warned_about() {
        // Two runtimes of three held connections each on a pool of five: each fits alone, together
        // they leave nothing. That is a warning — the refusal judges one runtime at a time, so a
        // bookkeeping slip can never refuse a healthy pool.
        var dataSource = untouchable(new AtomicInteger());
        var pool       = ConnectionPoolMetadata.ofMaximumSize(5);
        var first      = PoolBudget.reserve(dataSource, pool, 2);
        var second     = PoolBudget.reserve(dataSource, pool, 2);
        assertThat(PoolBudget.heldAgainst(dataSource)).isEqualTo(6);

        PoolBudget.release(first);
        assertThat(PoolBudget.heldAgainst(dataSource)).isEqualTo(3);
        PoolBudget.release(second);
        assertThat(PoolBudget.heldAgainst(dataSource)).isZero();
    }

    @Test
    void exhaustion_is_reported_only_when_every_connection_is_checked_out() {
        var dataSource = untouchable(new AtomicInteger());
        assertThat(PoolBudget.exhaustion(dataSource, fixed(10, 10)))
                .hasValueSatisfying(text -> assertThat(text).contains("10 of 10 connections checked out"));
        // Room in the pool: the failure was the database, not the pool.
        assertThat(PoolBudget.exhaustion(dataSource, fixed(10, 4))).isEmpty();
        // Unknown active count or size: say nothing rather than guess.
        assertThat(PoolBudget.exhaustion(dataSource, ConnectionPoolMetadata.ofMaximumSize(10))).isEmpty();
        assertThat(PoolBudget.exhaustion(dataSource, ConnectionPoolMetadata.unknown())).isEmpty();
        assertThat(PoolBudget.exhaustion(dataSource, throwing())).isEmpty();
    }

    @Test
    void the_runtime_refuses_before_it_asks_the_data_source_for_anything() {
        var connectionRequests = new AtomicInteger();
        var dataSource         = untouchable(connectionRequests);
        var settings           = ShardOwnerSettings.defaults();
        var tooSmall           = ConnectionPoolMetadata.ofMaximumSize(PoolBudget.heldConnections(settings.pumpThreads()));

        assertThatThrownBy(() -> new ShardRuntime(dataSource, settings, new ShardOwnerMetrics(), tooSmall))
                .isInstanceOf(IllegalStateException.class);
        assertThat(connectionRequests).hasValue(0);
        assertThat(PoolBudget.heldAgainst(dataSource)).isZero();
    }

    @Test
    void ofMaximumSize_rejects_a_size_that_is_not_positive() {
        assertThatThrownBy(() -> ConnectionPoolMetadata.ofMaximumSize(0)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A {@code DataSource} that counts, and refuses, every request made of it. Each test gets its own,
     * so the per-{@code DataSource} counts cannot leak between tests.
     */
    private static DataSource untouchable(AtomicInteger requests) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "untouchable";
                    default -> {
                        requests.incrementAndGet();
                        throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static ConnectionPoolMetadata fixed(int maximum, int active) {
        return new ConnectionPoolMetadata() {
            @Override
            public OptionalInt maximumSize() {
                return OptionalInt.of(maximum);
            }

            @Override
            public OptionalInt activeConnections() {
                return OptionalInt.of(active);
            }

            @Override
            public String toString() {
                return "fixed(" + maximum + ", " + active + ")";
            }
        };
    }

    /** Metadata is supplied from outside the engine; a broken implementation must not stop it. */
    private static ConnectionPoolMetadata throwing() {
        return new ConnectionPoolMetadata() {
            @Override
            public OptionalInt maximumSize() {
                throw new IllegalStateException("broken");
            }

            @Override
            public OptionalInt activeConnections() {
                throw new IllegalStateException("broken");
            }

            @Override
            public String toString() {
                return "throwing";
            }
        };
    }
}
