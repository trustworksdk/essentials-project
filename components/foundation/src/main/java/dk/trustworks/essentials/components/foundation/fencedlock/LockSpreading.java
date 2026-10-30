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

package dk.trustworks.essentials.components.foundation.fencedlock;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Whether a {@link DBFencedLockManager} spreads the locks it acquires asynchronously
 * ({@link FencedLockManager#acquireLockAsync(LockName, LockCallback)}) over the live lock manager instances, instead of
 * leaving each one to whichever instance asks first. Off by default.
 * <p>
 * <b>How:</b> each lock manager holds a membership lock of its own, {@code __member:<lockManagerInstanceId>}, confirmed
 * like any other lock, so the live instances are the confirmed membership rows of the lock table - no schema change.
 * Every instance computes the same preferred owner for a lock by rendezvous hashing of the lock name over the live
 * instances. A free lock is taken by its preferred owner; another instance takes it only once it has seen it free for
 * {@link #acquireGrace()}, so a lock is never left unserved when its preferred owner is slow or gone. An instance joining
 * or leaving changes the preferred owner of only about 1/N of the locks.
 * <p>
 * <b>What it costs - read before turning it on:</b>
 * <ul>
 *     <li><b>Locks spread on acquire only; a held lock never moves.</b> Instances that start one after another still end
 *         up uneven: the first instance takes every free lock it is preferred for, and the grace only holds it back from
 *         the others for a little while once they are up. Spreading is even when instances are up before the locks are
 *         free - a crash of a holder, or a restart of the whole cluster - and a crashed instance's locks are spread over
 *         all survivors instead of landing on one. Moving held locks over (rebalancing) is not built.</li>
 *     <li><b>A free lock waits up to {@link #acquireGrace()} longer</b> when its preferred owner does not take it - its
 *         processor is idle for that long after a failover.</li>
 *     <li><b>It spreads whole locks, and so whole exclusive consumers.</b> One busy consumer behind one lock is still one
 *         instance's work.</li>
 *     <li><b>Each non-preferred instance reads a free lock's row once per tick</b> while it waits, and every instance
 *         reads the lock table's membership rows once per {@code lockConfirmationInterval}. The membership rows appear
 *         in the lock table and the admin API's lock list.</li>
 *     <li><b>Instances disagree on membership for up to a {@code lockConfirmationInterval}</b> after one joins or
 *         leaves. Two instances may then both prefer, or both not prefer, the same lock. Exclusion is unaffected - it is
 *         decided by the fence token as always - only who takes the lock.</li>
 * </ul>
 *
 * @param enabled      whether locks are spread
 * @param acquireGrace how long an instance that is not a free lock's preferred owner must see the lock free before it
 *                     takes it. Not negative. Ignored when {@code enabled} is false
 */
public record LockSpreading(boolean enabled, Duration acquireGrace) {
    /**
     * The prefix of every membership lock's name. A lock whose name starts with it is never spread.
     */
    public static final String MEMBERSHIP_LOCK_NAME_PREFIX = "__member:";

    public LockSpreading {
        requireNonNull(acquireGrace, "No acquireGrace provided");
        if (acquireGrace.isNegative()) {
            throw new IllegalArgumentException(msg("acquireGrace {} must not be negative", acquireGrace));
        }
    }

    /**
     * @return locks are not spread - the default
     */
    public static LockSpreading disabled() {
        return new LockSpreading(false, Duration.ZERO);
    }

    /**
     * @param acquireGrace see {@link #acquireGrace()}
     * @return locks are spread
     */
    public static LockSpreading enabled(Duration acquireGrace) {
        return new LockSpreading(true, acquireGrace);
    }

    /**
     * The grace used when spreading is enabled without one: two lock confirmation intervals, so the preferred owner has
     * two of its own acquire ticks to take a free lock before another instance may.
     *
     * @param lockConfirmationInterval the lock manager's confirmation interval, which is also its acquire tick
     * @return the default grace
     */
    public static Duration defaultAcquireGrace(Duration lockConfirmationInterval) {
        return requireNonNull(lockConfirmationInterval, "No lockConfirmationInterval provided").multipliedBy(2);
    }

    /**
     * @param lockName a lock name
     * @return whether it names a membership lock
     */
    public static boolean isMembershipLock(LockName lockName) {
        return lockName.toString().startsWith(MEMBERSHIP_LOCK_NAME_PREFIX);
    }
}
