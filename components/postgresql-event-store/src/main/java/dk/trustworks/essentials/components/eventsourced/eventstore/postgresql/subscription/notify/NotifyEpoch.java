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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.notify;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The notify epoch of one event-stream table: a counter that only moves forward, and that a polling worker can wait on
 * until it moves (S2, interruptible wake-up - see {@link NotifyAwareEventStorePollingOptimizer}).
 * <p>
 * The waiter states which epoch it last saw - read <b>before</b> its poll started - and {@link #awaitChangeFrom(long, long)}
 * returns at once when the epoch is already past it. Any append the poll did not see commits after that read, so its
 * notification advances the epoch after that read too, and the wait cannot miss it: there is no window between
 * "the poll came back empty" and "start waiting" in which a wake-up is lost.
 * <p>
 * Every advance collapses into the one state change a waiter checks - "the epoch is not what I saw" - so any number of
 * advances while a worker polls or waits ends at most one wait.
 * <p>
 * Thread-safe. {@link #advance()} is called by the notification publisher (the {@code MultiTableChangeListener}'s poll
 * thread), {@link #awaitChangeFrom(long, long)} by the polling workers, each on its own thread. No thread is created.
 */
public final class NotifyEpoch {
    private final AtomicLong epoch = new AtomicLong();

    /**
     * @return the current epoch; {@code 0} before the first {@link #advance()}
     */
    public long current() {
        return epoch.get();
    }

    /**
     * Move the epoch forward by one and wake every thread waiting in {@link #awaitChangeFrom(long, long)}
     *
     * @return the new epoch
     */
    public long advance() {
        long advanced = epoch.incrementAndGet();
        // The waiter checks the epoch while holding this monitor and releases it only by waiting, so a notifyAll under the
        // monitor after the increment either finds it waiting or it has not checked yet - and then sees the new epoch
        synchronized (this) {
            notifyAll();
        }
        return advanced;
    }

    /**
     * Wait until the epoch is no longer {@code seenEpoch}, or until {@code timeoutMs} has passed.
     *
     * @param seenEpoch the epoch the caller last saw (read before the poll it is waiting after)
     * @param timeoutMs the longest time to wait; {@code 0} or less does not wait
     * @return {@code true} if the epoch is no longer {@code seenEpoch} (also when it already was on entry), {@code false} on timeout
     * @throws InterruptedException if the waiting thread is interrupted - a polling worker that is stopped or cancelled
     */
    public boolean awaitChangeFrom(long seenEpoch, long timeoutMs) throws InterruptedException {
        if (epoch.get() != seenEpoch) {
            return true;
        }
        if (timeoutMs <= 0) {
            return false;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (this) {
            while (epoch.get() == seenEpoch) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
            }
            return true;
        }
    }

    @Override
    public String toString() {
        return "NotifyEpoch{" + epoch.get() + '}';
    }
}
