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

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RateLimitedCount}, which {@link InDoubtReleaseFailures} and {@link OwedMoveReleaseRefusals} count on: the first
 * event is a WARN, later ones within the minute are logged at DEBUG, and the next WARN once the minute has passed counts
 * every event since the last one. An event another thread records while a caller is taking the window is carried into
 * the next WARN, not lost.
 */
class RateLimitedCountTest {
    private static final long MINUTE = ErrorWindow.EVERY_NANOS;

    @Test
    void the_first_event_is_a_warning_of_one() {
        assertThat(new RateLimitedCount().record(1_000L)).isEqualTo(1);
    }

    @Test
    void events_within_the_minute_are_not_warnings() {
        var count = new RateLimitedCount();
        var first = 1_000L;
        count.record(first);

        assertThat(count.record(first + 1)).isZero();
        assertThat(count.record(first + MINUTE - 1)).isZero();
    }

    @Test
    void the_next_warning_once_the_minute_has_passed_counts_every_event_since_the_last_one() {
        var count = new RateLimitedCount();
        var first = 1_000L;
        count.record(first);
        count.record(first + 10);
        count.record(first + 20);

        var second = first + MINUTE;
        assertThat(count.record(second)).as("two at DEBUG, and this one").isEqualTo(3);
        assertThat(count.record(second + 1)).as("the minute runs from the second warning").isZero();
        assertThat(count.record(second + MINUTE)).as("counted from the second warning").isEqualTo(2);
    }

    @Test
    void a_record_landing_during_a_warning_is_counted_in_the_next_one() {
        var first = 1_000L;
        var other = new AtomicReference<Thread>();
        var otherWarning = new AtomicInteger(-1);
        var counter = new AtomicReference<RateLimitedCount>();
        var window = new AtomicLong(ErrorWindow.NEVER_TAKEN);
        // The other thread records once the first caller has taken the window, before that caller subtracts what it read
        var count = new RateLimitedCount(now -> {
            var taken = ErrorWindow.take(window, now);
            if (taken && other.get() == null) {
                var thread = new Thread(() -> otherWarning.set(counter.get().record(first + 1)), "other-recorder");
                other.set(thread);
                thread.start();
                joinUninterruptibly(thread);
            }
            return taken;
        });
        counter.set(count);

        assertThat(count.record(first)).as("the first caller read only its own event").isEqualTo(1);
        assertThat(otherWarning.get()).as("the other thread's event, recorded inside the minute").isZero();
        assertThat(count.record(first + MINUTE))
                .as("the other thread's event is carried into the next warning, and this one")
                .isEqualTo(2);
    }

    @Test
    void every_event_recorded_from_several_threads_is_counted_in_exactly_one_warning() throws Exception {
        var threads = 8;
        var perThread = 20_000;
        var count = new RateLimitedCount();
        var clock = new AtomicLong(1_000L);
        var warned = new AtomicLong();
        var start = new CountDownLatch(1);
        // shutdownNow before close: after a timed-out get, close() alone would wait for the tasks to finish their loops;
        // the interrupt ends them, as each loop iteration checks for it
        try (var pool = Executors.newFixedThreadPool(threads)) {
            try {
                var done = new ArrayList<Future<?>>();
                for (var t = 0; t < threads; t++) {
                    done.add(pool.submit(() -> {
                        start.await();
                        for (var i = 0; i < perThread; i++) {
                            if (Thread.currentThread().isInterrupted()) {
                                throw new InterruptedException();
                            }
                            warned.addAndGet(count.record(clock.addAndGet(MINUTE / 50)));
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (var future : done) {
                    future.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
        }

        var pending = count.record(clock.get() + 2 * MINUTE) - 1;
        assertThat(warned.get() + pending)
                .as("the warnings' counts plus what is still pending add up to every event recorded")
                .isEqualTo((long) threads * perThread);
    }

    private static void joinUninterruptibly(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
