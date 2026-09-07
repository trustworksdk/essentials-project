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

import java.util.concurrent.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Where handlers run, and how many may run at once — one budget for the whole process.
 * <p>
 * The two together rather than separately because they are never useful apart: an executor without a
 * bound is what let the ordered lane put ~19 000 handler invocations in flight at three hundred
 * queues, and a bound without an executor is what left the unordered lane running handlers inline on
 * the pump thread, capping the entire process at {@code pumpThreads} concurrent handlers.
 * <p>
 * <b>A permit is taken before the cursor advances past a message, never after.</b> That ordering is
 * the whole discipline: a row whose permit could not be taken must still be there for the next read,
 * and advancing the cursor past it would leave it to the head sweep. Getting this backwards is what
 * turned nearly every message into a phantom hole the first time asynchronous delivery was attempted.
 */
public record HandlerDispatch(Executor executor, Semaphore processPermits, Semaphore consumerPermits) {

    public HandlerDispatch {
        requireNonNull(executor, "No executor provided");
        requireNonNull(processPermits, "No processPermits provided");
        requireNonNull(consumerPermits, "No consumerPermits provided");
    }

    /**
     * A view of this dispatch bounded to one consumer.
     * <p>
     * <b>Two levels, because they answer different questions.</b> The consumer's own bound is what a
     * caller sets to say how much parallelism this queue deserves — the same thing
     * {@code ConsumeFromQueue.parallelConsumers} means in the current implementation. The process
     * bound is a ceiling over all of them, so that whatever the handlers contend for, usually a
     * connection pool, cannot be swamped by the sum of everyone's ambitions. One number could not do
     * both: a single process-wide budget lets one busy queue starve every other.
     */
    public HandlerDispatch forConsumer(int parallelConsumers) {
        return new HandlerDispatch(executor, processPermits,
                                   new Semaphore(Math.max(1, parallelConsumers)));
    }

    /** Inline and unbounded — for tests that want no concurrency at all. */
    public static HandlerDispatch inline() {
        return new HandlerDispatch(Runnable::run,
                                   new Semaphore(Integer.MAX_VALUE),
                                   new Semaphore(Integer.MAX_VALUE));
    }

    /**
     * Consumer first, then process; the consumer's permit is returned if the process is full, so no
     * caller can hold one level while waiting on the other. Both are non-blocking, so the order
     * cannot deadlock either way — it is chosen so the cheaper, more contended check comes first.
     */
    public boolean tryAcquire() {
        if (!consumerPermits.tryAcquire()) {
            return false;
        }
        if (!processPermits.tryAcquire()) {
            consumerPermits.release();
            return false;
        }
        return true;
    }

    public void release() {
        processPermits.release();
        consumerPermits.release();
    }

    public boolean saturated() {
        return consumerPermits.availablePermits() == 0 || processPermits.availablePermits() == 0;
    }

    public void execute(Runnable task) {
        executor.execute(task);
    }
}
