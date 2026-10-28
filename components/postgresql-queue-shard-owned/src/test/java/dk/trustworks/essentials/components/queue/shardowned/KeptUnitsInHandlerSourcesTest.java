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
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link ShardOwnerMetrics#keptUnitsInHandler()} reads from one source per {@link ShardOwnedQueue}, and metrics shared
 * through {@code ShardOwnedQueueBuilder.setMetrics} by queues built and closed over time must not keep a source for every
 * queue ever built on them: each source keeps its queue reachable, and every read went over all of them. Every queue's
 * constructor used to register one and nothing removed it. A source is now registered by the queue's start (and by the
 * writes that make it count a kept unit), and dropped by the next read or registration once its queue is neither started
 * nor counting a kept unit - kept while either holds.
 * <p>
 * No database is touched: the queues here are built and closed, never started; {@code ShardOwnedLifecycleIT} starts and
 * closes queues on shared metrics.
 */
class KeptUnitsInHandlerSourcesTest {

    @Test
    void queues_built_and_closed_on_shared_metrics_leave_no_source_behind() {
        var metrics = new ShardOwnerMetrics();
        for (var i = 0; i < 50; i++) {
            try (var queue = ShardOwnedQueue.builder()
                                            .setDataSource(new UnusedDataSource())
                                            .setQueueId((short) 1)
                                            .setShardCount(4)
                                            .setInstanceId("built-" + i)
                                            .setMetrics(metrics)
                                            .build()) {
                queue.configureUnordered((messageId, payload, type) -> {
                }, ShardOwnerSettings.defaults(), 4, RedeliveryPolicy.fixed(Duration.ofMillis(10), 3));
            }
        }

        assertThat(metrics.keptUnitsInHandlerSourceCount()).as("no closed queue stays registered").isZero();
        assertThat(metrics.keptUnitsInHandler()).isZero();
    }

    @Test
    void a_registration_drops_every_source_whose_queue_is_stopped_and_counts_nothing() {
        var metrics = new ShardOwnerMetrics();
        for (var i = 0; i < 50; i++) {
            var closed = new FakeSource();
            metrics.countKeptUnitsInHandler(closed);
            closed.started.set(false);
        }

        assertThat(metrics.keptUnitsInHandlerSourceCount())
                .as("each registration dropped the queue closed before it; only the last is left").isEqualTo(1);
        assertThat(metrics.keptUnitsInHandler()).isZero();
        assertThat(metrics.keptUnitsInHandlerSourceCount()).as("the read dropped the last").isZero();
    }

    @Test
    void a_source_stays_while_its_queue_runs_or_a_kept_handler_of_it_does() {
        var metrics = new ShardOwnerMetrics();
        var running = new FakeSource();
        var stoppedKeeping = new FakeSource();
        metrics.countKeptUnitsInHandler(running);
        metrics.countKeptUnitsInHandler(stoppedKeeping);
        stoppedKeeping.started.set(false);
        stoppedKeeping.kept.set(1);

        metrics.countKeptUnitsInHandler(new FakeSource());
        assertThat(metrics.keptUnitsInHandler()).as("the stopped queue's kept handler still runs").isEqualTo(1);
        assertThat(metrics.keptUnitsInHandlerSourceCount()).isEqualTo(3);

        stoppedKeeping.kept.set(0);
        assertThat(metrics.keptUnitsInHandler()).as("its handler has returned").isZero();
        assertThat(metrics.keptUnitsInHandlerSourceCount()).as("the stopped queue that counts nothing is dropped").isEqualTo(2);

        running.kept.set(2);
        assertThat(metrics.keptUnitsInHandler()).as("a running queue is counted throughout").isEqualTo(2);
    }

    @Test
    void a_dropped_source_is_counted_again_once_it_registers_again() {
        var metrics = new ShardOwnerMetrics();
        var queue   = new FakeSource();
        metrics.countKeptUnitsInHandler(queue);
        queue.started.set(false);
        assertThat(metrics.keptUnitsInHandler()).isZero();
        assertThat(metrics.keptUnitsInHandlerSourceCount()).as("premise: dropped").isZero();

        // A restart, or a stop that keeps a unit: the queue writes its state, then registers.
        queue.kept.set(1);
        metrics.countKeptUnitsInHandler(queue);
        metrics.countKeptUnitsInHandler(queue);

        assertThat(metrics.keptUnitsInHandlerSourceCount()).as("registered once, however often it registers").isEqualTo(1);
        assertThat(metrics.keptUnitsInHandler()).isEqualTo(1);
    }

    @Test
    void a_source_found_finished_but_no_longer_finished_once_it_is_out_is_put_back() {
        var metrics = new ShardOwnerMetrics();
        var queue = new FakeSource() {
            // The queue restarts as the read that found it finished takes it out: the drop's second look sees it started.
            private final AtomicInteger finishedAsked = new AtomicInteger();

            @Override
            public boolean finished() {
                if (finishedAsked.incrementAndGet() == 1) {
                    started.set(true);
                }
                return super.finished();
            }
        };
        metrics.countKeptUnitsInHandler(queue);
        queue.started.set(false);

        assertThat(metrics.keptUnitsInHandler()).isZero();

        assertThat(metrics.keptUnitsInHandlerSourceCount()).as("the restarted queue is still counted").isEqualTo(1);
    }

    private static class FakeSource implements ShardOwnerMetrics.KeptUnitsInHandlerSource {
        final AtomicBoolean started = new AtomicBoolean(true);
        final AtomicInteger kept    = new AtomicInteger();

        @Override
        public int keptUnitsInHandler() {
            return kept.get();
        }

        @Override
        public boolean isStarted() {
            return started.get();
        }
    }

    /** The queues here are never started, so none opens a connection. */
    private static final class UnusedDataSource implements DataSource {
        @Override
        public Connection getConnection() {
            throw new AssertionError("a queue that is never started must not open a connection");
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
