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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DurableAsyncSnapshotManagerTest {
    @Test
    void start_polls_processor_and_stop_is_idempotent() {
        var processor = mock(PostgresqlAggregateSnapshotJobProcessor.class);
        var manager = new DurableAsyncSnapshotManager(processor,
                                                      new DurableAsyncSnapshotSettings(Duration.ofMillis(10), 25, 1, 3, Duration.ofSeconds(5)));

        manager.start();
        manager.start();

        Awaitility.waitAtMost(Duration.ofSeconds(2))
                  .untilAsserted(() -> verify(processor, atLeastOnce()).processNextBatch(any()));

        manager.stop();
        manager.stop();

        assertThat(manager.isStarted()).isFalse();
    }

    /**
     * The worker executor has to push back on the polling loop rather than queue without limit: each poll submits a
     * whole locked batch without waiting for it, so an unbounded queue let a poll interval shorter than the batch
     * drain time accumulate jobs — and the serialized snapshot payload each one retains — indefinitely.
     * <p>
     * Asserted on the executor the manager hands to the processor, because that is what carries the bound: once its
     * queue is full a submission runs on the submitting thread, which is the polling thread, so polling can only go as
     * fast as the workers drain.
     */
    @Test
    void the_worker_executor_runs_overflow_on_the_submitting_thread_instead_of_queueing_without_limit() throws Exception {
        var processor         = mock(PostgresqlAggregateSnapshotJobProcessor.class);
        var capturedExecutor  = new AtomicReference<Executor>();
        when(processor.processNextBatch(any())).thenAnswer(invocation -> {
            capturedExecutor.compareAndSet(null, invocation.getArgument(0));
            return 0;
        });

        var manager = new DurableAsyncSnapshotManager(processor,
                                                      new DurableAsyncSnapshotSettings(Duration.ofMillis(10), 25, 1, 3, Duration.ofSeconds(5)));
        manager.start();
        var releaseWorkers = new CountDownLatch(1);
        try {
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(capturedExecutor.get()).isNotNull());
            var workerExecutor = capturedExecutor.get();

            // One worker thread and a queue of one: the first task occupies the worker, the second fills the queue.
            workerExecutor.execute(() -> awaitQuietly(releaseWorkers));
            workerExecutor.execute(() -> awaitQuietly(releaseWorkers));

            var ranOnThread = new AtomicReference<Thread>();
            workerExecutor.execute(() -> ranOnThread.set(Thread.currentThread()));

            assertThat(ranOnThread.get()).describedAs("the overflow task must run on the submitting thread, not be queued")
                                         .isSameAs(Thread.currentThread());
        } finally {
            releaseWorkers.countDown();
            manager.stop();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
