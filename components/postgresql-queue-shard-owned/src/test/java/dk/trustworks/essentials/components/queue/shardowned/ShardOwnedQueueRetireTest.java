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
import java.sql.Connection;
import java.time.Duration;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.*;

/**
 * A retired {@link ShardOwnedQueue} - a lane of a {@code PostgresqlMessageQueue} subscription that has been unsubscribed -
 * starts nothing, whoever calls its {@code start()}. {@code PostgresqlMessageQueue} retires both lanes before it stops
 * them, so a {@code start()} of the queue still going over them, the holder of the queue's lifecycle lock that an
 * unsubscribe which did not get that lock leaves to take them out, starts neither from then on: it used to start them
 * after the unsubscribe had returned. The check is the lane's own, read under the lane's lifecycle lock, so it holds also for a
 * holder that read the lane as not retired just before the unsubscribe retired it.
 * <p>
 * No database is touched: a retired queue's start returns before it borrows a runtime or opens a connection, which is
 * what makes this a unit test - and any start that went further fails on the data source below.
 */
class ShardOwnedQueueRetireTest {

    private static final short QUEUE_ID = 1;

    @Test
    void a_retired_queue_starts_nothing() {
        var queue = configuredQueue();
        assertThat(queue.isRetired()).as("premise: a new queue is not retired").isFalse();

        queue.retire();

        assertThat(queue.isRetired()).isTrue();
        assertThatCode(queue::start).as("the start returns before it opens any connection").doesNotThrowAnyException();
        assertThat(queue.isStarted()).as("a retired queue stays not started").isFalse();
        assertThat(queue.shardsHeld()).isZero();
    }

    @Test
    void a_retired_queue_stays_retired_after_a_stop() {
        var queue = configuredQueue();
        queue.retire();
        queue.stop();

        assertThatCode(queue::start).as("a stop does not undo the retirement").doesNotThrowAnyException();
        assertThat(queue.isStarted()).isFalse();
    }

    private static ShardOwnedQueue configuredQueue() {
        var queue = ShardOwnedQueue.builder()
                                   .setDataSource(new UnusedDataSource())
                                   .setQueueId(QUEUE_ID)
                                   .setShardCount(4)
                                   .setInstanceId("retired")
                                   .build();
        queue.configureUnordered((messageId, payload, type) -> {
        }, ShardOwnerSettings.defaults(), 4, RedeliveryPolicy.fixed(Duration.ofMillis(10), 3));
        return queue;
    }

    /** A retired queue's start never opens a connection; one that does is the defect these tests pin. */
    private static final class UnusedDataSource implements DataSource {
        @Override
        public Connection getConnection() {
            throw new AssertionError("a retired queue's start must not open a connection");
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
