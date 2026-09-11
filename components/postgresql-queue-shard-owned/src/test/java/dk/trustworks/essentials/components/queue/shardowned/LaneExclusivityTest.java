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
import java.io.PrintWriter;
import java.sql.*;
import java.time.Duration;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.*;

/**
 * A {@link ShardOwnedQueue} serves one lane, and asking it for the other must fail rather than
 * quietly become the other.
 * <p>
 * Configuring both used to flip {@code activeLane} and leave the first handler populated but never
 * invoked — so the call reads like "configure both lanes" and delivers only the second. Everything
 * downstream then looks inexplicable rather than wrong: {@code remaining()} counts a lane you did not
 * think you chose, and messages on the other one are simply never consumed by that instance.
 * <p>
 * No database is touched: configuration is deliberately separate from {@code start()}, which is what
 * makes this a unit test.
 */
class LaneExclusivityTest {

    @Test
    void an_ordered_queue_refuses_to_be_reconfigured_as_unordered() {
        var queue = queue();
        queue.configureOrdered((key, payload, type) -> {
        }, ShardOwnerSettings.defaults(), 8, RedeliveryPolicy.fixed(Duration.ofMillis(10), 3));

        assertThatThrownBy(() -> queue.configureUnordered((payload, type) -> {
        }, ShardOwnerSettings.defaults(), 8, RedeliveryPolicy.fixed(Duration.ofMillis(10), 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already configured for the ordered lane")
                .hasMessageContaining("build a second ShardOwnedQueue");
    }

    @Test
    void an_unordered_queue_refuses_to_be_reconfigured_as_ordered() {
        var queue = queue();
        queue.configureUnordered((payload, type) -> {
        }, ShardOwnerSettings.defaults(), 8, RedeliveryPolicy.fixed(Duration.ofMillis(10), 3));

        assertThatThrownBy(() -> queue.configureOrdered((key, payload, type) -> {
        }, ShardOwnerSettings.defaults(), 8, RedeliveryPolicy.fixed(Duration.ofMillis(10), 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already configured for the unordered lane");
    }

    /** Re-stating the same lane is not the hazard and must keep working — startConsumingX does it. */
    @Test
    void reconfiguring_the_same_lane_is_allowed() {
        var queue = queue();
        queue.configureUnordered((payload, type) -> {
        }, ShardOwnerSettings.defaults(), 8, RedeliveryPolicy.fixed(Duration.ofMillis(10), 3));

        assertThatCode(() -> queue.configureUnordered((payload, type) -> {
        }, ShardOwnerSettings.defaults(), 4, RedeliveryPolicy.fixed(Duration.ofMillis(10), 3)))
                .doesNotThrowAnyException();
    }

    private ShardOwnedQueue queue() {
        return ShardOwnedQueue.builder()
                              .setDataSource(new UnusedDataSource())
                              .setQueueId((short) 1)
                              .setShardCount(4)
                              .setInstanceId("lane-exclusivity")
                              .build();
    }

    /** Configuration never opens a connection; anything that does here is the bug this test would hide. */
    private static final class UnusedDataSource implements DataSource {
        @Override
        public Connection getConnection() {
            throw new AssertionError("configuration must not open a connection");
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
