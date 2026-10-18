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
 * Helpers for taking a {@link Lock}.
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
     * An interrupt of the calling thread does not end the wait, and does not restart the {@code warnAfter} it falls in:
     * it is deferred, and set on the thread again once this returns - with the lock held, or with the exception
     * {@code warn} threw.
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
        var warned      = false;
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
                if (!warned) {
                    warned = warn.getAsBoolean();
                }
                deadline = System.nanoTime() + budgetNanos;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
