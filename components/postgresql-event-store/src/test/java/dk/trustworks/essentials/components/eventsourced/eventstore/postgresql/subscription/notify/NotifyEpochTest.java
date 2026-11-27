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

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The S2 wait primitive. Every test waits with a timeout far longer than the test is allowed to run, so a missed wake-up
 * shows as a failure, never as a slow pass; and a waiter is only woken once its thread is seen parked in the wait, so the
 * outcome does not depend on machine speed.
 */
class NotifyEpochTest {
    private static final long LONG_TIMEOUT_MS = 60_000;

    @Test
    void wakes_a_waiting_thread_when_the_epoch_advances() throws Exception {
        var epoch  = new NotifyEpoch();
        var waiter = startWaiter(epoch, epoch.current(), LONG_TIMEOUT_MS);
        awaitParked(waiter.thread);

        epoch.advance();

        assertThat(waiter.result.get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void returns_false_when_the_timeout_passes_without_an_advance() throws Exception {
        var epoch   = new NotifyEpoch();
        long start  = System.nanoTime();

        assertThat(epoch.awaitChangeFrom(epoch.current(), 50)).isFalse();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(50);
    }

    @Test
    void an_advance_between_reading_the_epoch_and_starting_to_wait_is_not_lost() throws Exception {
        var  epoch = new NotifyEpoch();
        long seen  = epoch.current(); // read before the poll
        epoch.advance();               // the NOTIFY lands while the poll runs

        assertThat(epoch.awaitChangeFrom(seen, LONG_TIMEOUT_MS)).isTrue();
    }

    @Test
    void no_wake_up_is_lost_when_the_advance_races_the_start_of_the_wait() throws Exception {
        var epoch = new NotifyEpoch();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2_000; i++) {
                long seen    = epoch.current();
                var  barrier = new CyclicBarrier(2);
                var  waited  = executor.submit(() -> {
                    barrier.await();
                    return epoch.awaitChangeFrom(seen, LONG_TIMEOUT_MS);
                });
                executor.submit(() -> {
                    barrier.await();
                    return epoch.advance();
                });
                assertThat(waited.get(10, TimeUnit.SECONDS)).as("iteration %d", i).isTrue();
            }
        }
    }

    @Test
    void any_number_of_advances_ends_one_wait_and_the_next_wait_starts_from_the_new_epoch() throws Exception {
        var epoch = new NotifyEpoch();
        long seen = epoch.current();
        for (int i = 0; i < 100; i++) epoch.advance();

        assertThat(epoch.awaitChangeFrom(seen, LONG_TIMEOUT_MS)).isTrue();
        assertThat(epoch.awaitChangeFrom(epoch.current(), 20)).isFalse();
    }

    @Test
    void an_interrupt_ends_the_wait() throws Exception {
        var epoch  = new NotifyEpoch();
        var waiter = startWaiter(epoch, epoch.current(), LONG_TIMEOUT_MS);
        awaitParked(waiter.thread);

        waiter.thread.interrupt();

        assertThat(waiter.result).failsWithin(10, TimeUnit.SECONDS)
                                 .withThrowableOfType(ExecutionException.class)
                                 .withCauseInstanceOf(InterruptedException.class);
    }

    @Test
    void a_zero_timeout_does_not_wait() throws Exception {
        var epoch = new NotifyEpoch();
        assertThat(epoch.awaitChangeFrom(epoch.current(), 0)).isFalse();
        assertThat(epoch.awaitChangeFrom(epoch.current() - 1, 0)).isTrue();
    }

    // ---------------------------------------------------------------------------------------------------------------

    record Waiter(Thread thread, CompletableFuture<Boolean> result) {
    }

    static Waiter startWaiter(NotifyEpoch epoch, long seen, long timeoutMs) {
        var result = new CompletableFuture<Boolean>();
        var thread = Thread.ofPlatform().name("epoch-waiter").start(() -> {
            try {
                result.complete(epoch.awaitChangeFrom(seen, timeoutMs));
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return new Waiter(thread, result);
    }

    /** The thread is parked in a timed wait - the only timed wait the waiter makes is the one under test */
    static void awaitParked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            assertThat(System.nanoTime()).as("thread never started waiting").isLessThan(deadline);
            Thread.sleep(1);
        }
    }
}
