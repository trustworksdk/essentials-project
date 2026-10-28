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

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.BooleanSupplier;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Helpers for taking a {@link Lock} while keeping a pending interrupt for later: {@link #lockWarningOnce} waits as long
 * as it takes, {@link #tryLockDeferringPendingInterrupt} a bounded time.
 */
public final class Locks {
    private Locks() {
    }

    /**
     * Take {@code lock}, waiting as long as it takes - as {@link java.util.concurrent.locks.ReentrantLock#lock()} does -
     * but not silently: once the calling thread has waited {@code warnAfter} without the lock, {@code warn} is called,
     * and again after each further {@code warnAfter}, until it returns {@code true}; from then on it is not called again
     * and the wait goes on in silence. {@code warn} returns {@code false} when it had nothing to say yet (the holder it
     * would name was just changing, say), so it is asked again one {@code warnAfter} later.
     * <p>
     * Until {@code warn} has returned {@code true} the wait is a timed {@link Lock#tryLock(long, TimeUnit)} of one
     * {@code warnAfter} at a time, and on a fair lock each one that runs out gives up the calling thread's place in the
     * lock's queue, so a later waiter can be handed the lock first. From then on the wait is {@code lock.lock()} itself,
     * which keeps its place as {@code lock()} does.
     * <p>
     * Until {@code warn} has returned {@code true}, an interrupt of the calling thread does not end the wait, and does
     * not restart the {@code warnAfter} it falls in: it is deferred, and set on the thread again once this returns -
     * with the lock held, or with the exception {@code warn} threw. After the warning, an interrupt is treated as the
     * lock's own {@code lock()} treats it: every {@code java.util.concurrent.locks} lock (and {@link
     * java.util.concurrent.locks.StampedLock}'s views) defers it the same way, but the {@link Lock} interface does not
     * promise that.
     * <p>
     * {@code warn} runs on the calling thread, without the lock. An exception it throws escapes from here, the lock not
     * taken.
     *
     * @param lock      the lock to take
     * @param warnAfter how long to wait before {@code warn} is called, and between its calls until it returns
     *                  {@code true}; must be positive
     * @param warn      says that the calling thread is still waiting - typically by logging one WARN naming the lock's
     *                  holder - and returns {@code true} once it has said it
     */
    public static void lockWarningOnce(Lock lock, Duration warnAfter, BooleanSupplier warn) {
        requireNonNull(lock, "No lock provided");
        requireNonNull(warnAfter, "No warnAfter provided");
        requireNonNull(warn, "No warn provided");
        requireTrue(warnAfter.isPositive(), "warnAfter must be positive");
        var budgetNanos = warnAfter.toNanos();
        var interrupted = false;
        var deadline    = System.nanoTime() + budgetNanos;
        try {
            while (true) {
                try {
                    if (lock.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    // Waited on regardless, as lock() would, towards the same deadline; the flag is set again on the way out.
                    interrupted = true;
                    continue;
                }
                if (warn.getAsBoolean()) {
                    // Warned: wait in lock() itself from here, keeping this thread's place in a fair lock's queue - a timed
                    // tryLock gives it up each time it runs out. An interrupt from here on is the lock's own lock()'s to
                    // handle: the java.util.concurrent locks defer it and set it again on return, Lock does not promise it.
                    lock.lock();
                    return;
                }
                deadline = System.nanoTime() + budgetNanos;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Wait up to {@code wait} for {@code lock} - a timed {@link Lock#tryLock(long, TimeUnit)}, parked rather than
     * spinning - for a caller that must not block on the lock for long, and must not be kept from it by an interrupt it
     * already had: one that, say, takes the lock a few milliseconds at a time and between tries reads who holds it.
     * <p>
     * An interrupt pending on entry is cleared for the wait, so it does not cut the wait short, nor make a free lock
     * look taken (a timed {@code tryLock} throws at once on a pending interrupt, free lock or not). An interrupt arriving
     * during the wait ends it, for a lock whose timed {@code tryLock} can be interrupted - as every {@code
     * java.util.concurrent.locks} lock's (and {@link java.util.concurrent.locks.StampedLock}'s views') can, but the
     * {@link Lock} interface does not promise that: the wait returns {@code false}, the lock not taken. A lock that does
     * not support interrupting its acquisition waits on as its {@code tryLock} does. Either way the interrupt flag is set
     * again before this returns, so the caller leaves it as it found or got it.
     * <p>
     * A {@code wait} of zero tries once without waiting - but, unlike {@link Lock#tryLock()}, honouring a fair lock's
     * queue, as the timed {@code tryLock} of the {@code java.util.concurrent.locks} locks does. A {@code wait} too long to
     * count in nanoseconds (about 292 years) waits {@link Long#MAX_VALUE} nanoseconds.
     *
     * @param lock the lock to take
     * @param wait how long to wait for it; must not be negative
     * @return {@code true} if the lock was taken (the caller unlocks it), {@code false} if {@code wait} ran out or - for
     * a lock whose timed {@code tryLock} can be interrupted - an interrupt arrived during it
     */
    public static boolean tryLockDeferringPendingInterrupt(Lock lock, Duration wait) {
        requireNonNull(lock, "No lock provided");
        requireNonNull(wait, "No wait provided");
        requireFalse(wait.isNegative(), "wait must not be negative");
        var interrupted = Thread.interrupted();
        try {
            // TimeUnit.convert(Duration) saturates at Long.MAX_VALUE where Duration.toNanos() would throw.
            return lock.tryLock(TimeUnit.NANOSECONDS.convert(wait), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
            return false;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
