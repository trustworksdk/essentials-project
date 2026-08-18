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

package dk.trustworks.essentials.examples.perflab.vthreads;

import java.util.*;
import java.util.concurrent.*;

/**
 * Adapts {@link Executors#newVirtualThreadPerTaskExecutor()} to the {@link ScheduledExecutorService} interface.
 * <p>
 * This class exists because of a concrete API constraint in the framework, and it is the reason the
 * {@code virtual-threads-queue} scenario needs an adapter at all:
 * {@code ConsumeFromQueue.getConsumerExecutorService()} is typed {@link ScheduledExecutorService}, while
 * {@link Executors#newVirtualThreadPerTaskExecutor()} returns a plain {@link ExecutorService}. There is no
 * virtual-thread-backed {@link ScheduledExecutorService} in the JDK (as of Java 25), so a consumer cannot be
 * handed a virtual-thread executor through the public builder without an adapter like this one.
 * <p>
 * Only the {@code CentralizedMessageFetcher}-based consumer path actually needs a plain
 * {@link ExecutorService} — it calls {@code submit(Runnable)} and nothing else. The legacy
 * {@code DefaultDurableQueueConsumer} genuinely calls {@code scheduleAtFixedRate}, which is why the shared
 * type ended up being the wider one.
 *
 * <h2>What is and is not supported</h2>
 * <ul>
 *     <li>{@link #execute(Runnable)}, {@code submit(...)}, {@code invokeAll(...)} — delegate to the
 *     virtual-thread-per-task executor: one new virtual thread per task, unbounded.</li>
 *     <li>{@link #schedule(Runnable, long, TimeUnit)} and {@link #schedule(Callable, long, TimeUnit)} — a
 *     single shared platform-thread timer holds the delay, then the body runs on a fresh virtual thread.</li>
 *     <li>{@code scheduleAtFixedRate} / {@code scheduleWithFixedDelay} — <strong>deliberately
 *     unsupported</strong>. A {@link ScheduledThreadPoolExecutor} guarantees that successive runs of the same
 *     periodic task never overlap, and {@code DefaultDurableQueueConsumer} relies on that: it schedules the
 *     same {@code pollQueue} runnable N times to get exactly N concurrent pollers. Dispatching each period
 *     onto a fresh virtual thread would drop that guarantee and let an arbitrary number of polls run
 *     concurrently whenever a poll outlasts its period. Failing loudly is better than silently changing the
 *     concurrency contract.</li>
 * </ul>
 */
public final class VirtualThreadScheduledExecutorAdapter implements ScheduledExecutorService {
    private final ExecutorService          virtualThreads;
    private final ScheduledExecutorService delayTimer;

    public VirtualThreadScheduledExecutorAdapter(String threadNamePrefix) {
        this.virtualThreads = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
                                                                       .name(threadNamePrefix + "-vt-", 0)
                                                                       .factory());
        this.delayTimer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, threadNamePrefix + "-vt-delay-timer");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return delayTimer.schedule(() -> virtualThreads.execute(command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException("schedule(Callable) is not supported by " + getClass().getSimpleName() +
                                                        " - the adapter exists only to feed submit()-style work onto virtual threads");
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        throw new UnsupportedOperationException("scheduleAtFixedRate is not supported by " + getClass().getSimpleName() +
                                                        " - see the class javadoc: dispatching each period onto a fresh virtual thread " +
                                                        "would drop ScheduledThreadPoolExecutor's non-overlap guarantee that " +
                                                        "DefaultDurableQueueConsumer relies on for its parallel-consumer count");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException("scheduleWithFixedDelay is not supported by " + getClass().getSimpleName() +
                                                        " - see scheduleAtFixedRate");
    }

    @Override
    public void shutdown() {
        delayTimer.shutdown();
        virtualThreads.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        var pending = new ArrayList<Runnable>();
        pending.addAll(delayTimer.shutdownNow());
        pending.addAll(virtualThreads.shutdownNow());
        return pending;
    }

    @Override
    public boolean isShutdown() {
        return virtualThreads.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return virtualThreads.isTerminated() && delayTimer.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        var deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        if (!virtualThreads.awaitTermination(timeout, unit)) {
            return false;
        }
        return delayTimer.awaitTermination(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return virtualThreads.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return virtualThreads.submit(task, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return virtualThreads.submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return virtualThreads.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return virtualThreads.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return virtualThreads.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return virtualThreads.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        virtualThreads.execute(command);
    }
}
