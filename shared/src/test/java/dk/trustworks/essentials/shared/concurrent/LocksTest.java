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

package dk.trustworks.essentials.shared.concurrent;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class LocksTest {
    private final ReentrantLock   lock    = new ReentrantLock();
    private final ExecutorService waiters = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() throws InterruptedException {
        waiters.shutdownNow();
        assertThat(waiters.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void takes_a_free_lock_at_once_without_warning() {
        var warnings = new AtomicInteger();

        Locks.lockWarningOnce(lock, Duration.ofSeconds(10), () -> warnings.incrementAndGet() > 0);
        try {
            assertThat(lock.isHeldByCurrentThread()).isTrue();
            assertThat(warnings).hasValue(0);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            lock.unlock();
        }
    }

    @Test
    void an_interrupt_does_not_end_the_wait_and_is_set_again_once_the_lock_is_held() throws Exception {
        lock.lock();
        var waiting = new CountDownLatch(1);
        var waiter  = new AtomicReference<Thread>();
        var result = waiters.submit(() -> {
            waiter.set(Thread.currentThread());
            waiting.countDown();
            Locks.lockWarningOnce(lock, Duration.ofSeconds(30), () -> true);
            try {
                return new boolean[]{lock.isHeldByCurrentThread(), Thread.currentThread().isInterrupted()};
            } finally {
                lock.unlock();
            }
        });
        waiting.await();
        await().atMost(Duration.ofSeconds(5)).until(lock::hasQueuedThreads);

        waiter.get().interrupt();
        // Still waiting - the interrupt neither ended the wait nor threw.
        Thread.sleep(200);
        assertThat(result.isDone()).isFalse();
        await().atMost(Duration.ofSeconds(5)).until(lock::hasQueuedThreads);

        lock.unlock();
        assertThat(result.get(5, TimeUnit.SECONDS)).containsExactly(true, true);
    }

    /**
     * Guards the wait after the warning - {@code lock.lock()} since F-506 - rather than reproducing F-506: that the
     * interrupt contract still holds there for a {@link ReentrantLock}, whose own {@code lock()} defers the interrupt.
     */
    @Test
    void an_interrupt_after_the_warning_does_not_end_the_wait_and_is_set_again_once_the_lock_is_held() throws Exception {
        lock.lock();
        var warned = new CountDownLatch(1);
        var waiter = new AtomicReference<Thread>();
        var result = waiters.submit(() -> {
            waiter.set(Thread.currentThread());
            Locks.lockWarningOnce(lock, Duration.ofMillis(20), () -> {
                warned.countDown();
                return true;
            });
            try {
                return new boolean[]{lock.isHeldByCurrentThread(), Thread.currentThread().isInterrupted()};
            } finally {
                lock.unlock();
            }
        });
        assertThat(warned.await(5, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> waiter.get() != null && lock.hasQueuedThread(waiter.get()));

        waiter.get().interrupt();
        Thread.sleep(200);
        assertThat(result.isDone()).isFalse();

        lock.unlock();
        assertThat(result.get(5, TimeUnit.SECONDS)).containsExactly(true, true);
    }

    @Test
    void warns_once_however_long_the_wait() throws Exception {
        lock.lock();
        var warnings = new AtomicInteger();
        var result   = waiters.submit(() -> {
            Locks.lockWarningOnce(lock, Duration.ofMillis(20), () -> {
                warnings.incrementAndGet();
                return true;
            });
            lock.unlock();
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> warnings.get() == 1);
        // Ten more budgets go by without a second warning.
        Thread.sleep(200);
        assertThat(warnings).hasValue(1);
        assertThat(result.isDone()).isFalse();

        lock.unlock();
        result.get(5, TimeUnit.SECONDS);
        assertThat(warnings).hasValue(1);
    }

    @Test
    void asks_again_each_budget_until_the_warning_says_it_has_warned() throws Exception {
        lock.lock();
        var warnings = new AtomicInteger();
        var result   = waiters.submit(() -> {
            Locks.lockWarningOnce(lock, Duration.ofMillis(20), () -> warnings.incrementAndGet() == 3);
            lock.unlock();
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> warnings.get() == 3);
        Thread.sleep(200);
        assertThat(warnings).hasValue(3);

        lock.unlock();
        result.get(5, TimeUnit.SECONDS);
        assertThat(warnings).hasValue(3);
    }

    @Test
    void once_warned_keeps_its_place_in_a_fair_locks_queue() throws Exception {
        var fairLock = new ReentrantLock(true);
        fairLock.lock();
        var acquiredInOrder = new CopyOnWriteArrayList<String>();
        var warned          = new CountDownLatch(1);
        var first           = new AtomicReference<Thread>();
        var firstResult = waiters.submit(() -> {
            first.set(Thread.currentThread());
            Locks.lockWarningOnce(fairLock, Duration.ofMillis(20), () -> {
                warned.countDown();
                return true;
            });
            acquiredInOrder.add("warned waiter");
            fairLock.unlock();
        });
        assertThat(warned.await(5, TimeUnit.SECONDS)).isTrue();
        // Queued again after the warning, ahead of the second waiter, which only now starts.
        await().atMost(Duration.ofSeconds(5)).until(() -> first.get() != null && fairLock.hasQueuedThread(first.get()));

        var second = new AtomicReference<Thread>();
        var secondResult = waiters.submit(() -> {
            second.set(Thread.currentThread());
            fairLock.lock();
            acquiredInOrder.add("later waiter");
            fairLock.unlock();
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> second.get() != null && fairLock.hasQueuedThread(second.get()));
        // Ten warnAfters go by: a wait that gave up its place every warnAfter would now queue behind the later waiter.
        Thread.sleep(200);

        fairLock.unlock();
        firstResult.get(5, TimeUnit.SECONDS);
        secondResult.get(5, TimeUnit.SECONDS);
        assertThat(acquiredInOrder).containsExactly("warned waiter", "later waiter");
    }

    @Test
    void interrupts_do_not_put_the_warning_off() throws Exception {
        lock.lock();
        var warned  = new CountDownLatch(1);
        var waiter  = new AtomicReference<Thread>();
        var waiting = new CountDownLatch(1);
        var result = waiters.submit(() -> {
            waiter.set(Thread.currentThread());
            waiting.countDown();
            Locks.lockWarningOnce(lock, Duration.ofMillis(500), () -> {
                warned.countDown();
                return true;
            });
            lock.unlock();
        });
        waiting.await();
        // Interrupted every 50 ms, far more often than the 500 ms budget: a wait that started its budget over on each
        // interrupt would never warn.
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (warned.getCount() > 0 && System.nanoTime() < deadline) {
            waiter.get().interrupt();
            Thread.sleep(50);
        }
        assertThat(warned.getCount()).as("warned while interrupted every 50 ms").isZero();

        lock.unlock();
        result.get(5, TimeUnit.SECONDS);
    }

    @Test
    void an_exception_from_the_warning_escapes_without_the_lock_and_with_the_interrupt_set_again() throws Exception {
        lock.lock();
        var result = waiters.submit(() -> {
            // Interrupted before the wait starts, so the first tryLock throws at once - no race with warnAfter running out.
            Thread.currentThread().interrupt();
            try {
                Locks.lockWarningOnce(lock, Duration.ofMillis(300), () -> {
                    throw new IllegalStateException("warning failed");
                });
                return "returned";
            } catch (IllegalStateException e) {
                return e.getMessage() + ", held=" + lock.isHeldByCurrentThread() + ", interrupted=" + Thread.currentThread().isInterrupted();
            }
        });

        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("warning failed, held=false, interrupted=true");
        lock.unlock();
    }

    @Test
    void rejects_missing_or_non_positive_arguments() {
        assertThatThrownBy(() -> Locks.lockWarningOnce(null, Duration.ofSeconds(1), () -> true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Locks.lockWarningOnce(lock, null, () -> true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Locks.lockWarningOnce(lock, Duration.ofSeconds(1), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Locks.lockWarningOnce(lock, Duration.ZERO, () -> true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Locks.lockWarningOnce(lock, Duration.ofSeconds(-1), () -> true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(lock.isLocked()).isFalse();
    }
}
