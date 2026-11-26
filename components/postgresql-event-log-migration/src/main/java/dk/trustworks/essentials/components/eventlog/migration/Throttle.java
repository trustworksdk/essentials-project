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

package dk.trustworks.essentials.components.eventlog.migration;

import java.util.function.LongSupplier;

/**
 * The online copy's optional rate limit: after each batch, sleeps until the rows copied since
 * {@link #start()} fit {@code maxEventsPerSecond}. 0 never sleeps.
 */
final class Throttle {
    @FunctionalInterface
    interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    private final long         maxEventsPerSecond;
    private final LongSupplier nanoTime;
    private final Sleeper      sleeper;
    private       long         startNanos;
    private       long         rows;

    Throttle(long maxEventsPerSecond, LongSupplier nanoTime, Sleeper sleeper) {
        this.maxEventsPerSecond = maxEventsPerSecond;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    static Throttle of(long maxEventsPerSecond) {
        return new Throttle(maxEventsPerSecond, System::nanoTime, nanos -> Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000)));
    }

    void start() {
        startNanos = nanoTime.getAsLong();
        rows = 0;
    }

    /**
     * @param copied the rows the batch just copied
     */
    void afterBatch(long copied) {
        if (maxEventsPerSecond <= 0) {
            return;
        }
        rows += copied;
        var due     = (long) (rows * 1e9 / maxEventsPerSecond);
        var elapsed = nanoTime.getAsLong() - startNanos;
        if (due > elapsed) {
            try {
                sleeper.sleepNanos(due - elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while throttling the copy", e);
            }
        }
    }
}
