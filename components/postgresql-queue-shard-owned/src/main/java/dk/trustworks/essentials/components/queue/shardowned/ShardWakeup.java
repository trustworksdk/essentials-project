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

/**
 * Per-shard wake-up signal. An owner parks here when it finds nothing and is released either by a
 * notification or by the backstop timeout.
 * <p>
 * A permit rather than a condition variable, so a notification that arrives while the owner is still
 * working is not lost — it is remembered and the next park returns immediately. Losing that race
 * would strand a message until the backstop fired, which is the failure mode that makes naive
 * notification schemes look correct in tests and stall in production.
 * <p>
 * The permit count is capped at one: many notifications for the same shard mean the same thing, and
 * an owner drains everything it can see on each pass.
 */
final class ShardWakeup {
    private final Object      lock = new Object();
    private final ShardWakeup parent;
    private boolean           signalled;

    ShardWakeup() {
        this(null);
    }

    /**
     * @param parent also signalled whenever this one is — a shard's wake-up pointing at its pump's.
     *               The pump parks on the parent and then asks each of its shards whether the signal
     *               was for it, so a notification for one shard no longer makes the pump read all of
     *               them. That amplification measured 14.5 cursor reads per message at eight shards
     *               on two pumps.
     */
    ShardWakeup(ShardWakeup parent) {
        this.parent = parent;
    }

    /**
     * Record that this shard has work. Idempotent: many signals between two waits mean the same
     * thing, because the owner drains everything it can see on each pass.
     */
    public void signal() {
        synchronized (lock) {
            signalled = true;
            lock.notifyAll();
        }
        if (parent != null) {
            parent.signal();
        }
    }

    /**
     * Test and clear, without waiting. Used by a pump to ask which of its shards the wake-up it just
     * received was actually for.
     */
    public boolean consume() {
        synchronized (lock) {
            var was = signalled;
            signalled = false;
            return was;
        }
    }

    /**
     * Park until signalled or until the timeout expires.
     * <p>
     * A flag under a monitor rather than a semaphore, because both of the obvious semaphore shapes
     * are wrong and each failed in its own way:
     * <ul>
     *     <li><b>Acquire then {@code drainPermits}</b> discards any signal released between the two,
     *         and each discarded signal is a real queued message. It surfaced as a latency tail
     *         rather than a hang — p50 of 0.55 ms with a p99 of 256 ms, against a 500 ms sweep
     *         interval, which is exactly half a sweep: the wait of a message whose wake-up was
     *         thrown away.</li>
     *     <li><b>Acquire without draining</b> leaks permits instead, because capping the release with
     *         a check-then-act on {@code availablePermits} is itself a race. Permits accumulate, the
     *         wait stops waiting, and the owner spins hot and starves the other shards.</li>
     * </ul>
     * Setting a flag before notifying cannot lose a signal — a signal arriving before the wait
     * leaves the flag set, so the wait is skipped — and cannot accumulate, because the flag is
     * boolean.
     *
     * @return true if woken by a signal rather than by the timeout
     */
    public boolean await(long timeoutMillis) throws InterruptedException {
        synchronized (lock) {
            if (!signalled) {
                lock.wait(Math.max(1L, timeoutMillis));
            }
            var woken = signalled;
            signalled = false;
            return woken;
        }
    }
}
