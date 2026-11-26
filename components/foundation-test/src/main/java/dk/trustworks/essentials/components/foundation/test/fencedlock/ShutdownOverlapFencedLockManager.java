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

package dk.trustworks.essentials.components.foundation.test.fencedlock;

import dk.trustworks.essentials.components.foundation.fencedlock.*;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A {@link FencedLockManager} test double that replays, without timing, the overlap {@link LockCallback} documents for a
 * shutdown: <code>lockReleased</code> runs before a still-running <code>lockAcquired</code> has done its work.
 * <p>
 * In {@link DBFencedLockManager} the async acquiring tick records the lock, registers the callback and calls
 * <code>lockAcquired</code> while it holds the lock manager's lock. A component's own stop calls
 * {@link #cancelAsyncLockAcquiring(LockName)}, which during a shutdown releases the lock with one bounded attempt and then
 * releases it locally anyway - marking it released and calling <code>lockReleased</code> - while the tick may not have
 * reached <code>lockAcquired</code> yet. Here {@link #cancelAsyncLockAcquiring(LockName)} does that local release, and
 * {@link #deliverLateLockAcquired(LockName)} then plays the tick's late <code>lockAcquired</code>, with the lock already
 * released, as the tick would.
 * <p>
 * {@link #deliverLockAcquired(LockName)} plays an ordinary acquisition, so a test can also check that the component still
 * starts its work when nothing overlaps.
 * <p>
 * This is a copy of <code>dk.trustworks.essentials.components.foundation.fencedlock.ShutdownOverlapFencedLockManager</code>
 * in the foundation module's test tree: foundation-test depends on foundation, so foundation's own tests cannot use this
 * one, and modules downstream of foundation cannot use the test-tree one. Keep the two in step.
 */
public final class ShutdownOverlapFencedLockManager implements FencedLockManager {
    private final Map<LockName, LockCallback> callbacks = new ConcurrentHashMap<>();
    private final Map<LockName, TestLock>     locks     = new ConcurrentHashMap<>();
    private final List<LockName>              cancelled = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean                  started;
    private          long                     nextToken = 1;

    @Override
    public void acquireLockAsync(LockName lockName, LockCallback lockCallback) {
        requireNonNull(lockName, "No lockName provided");
        requireNonNull(lockCallback, "No lockCallback provided");
        callbacks.put(lockName, lockCallback);
        locks.put(lockName, new TestLock(lockName, nextToken++));
    }

    /**
     * Plays an ordinary acquisition: the lock is locked by this instance when <code>lockAcquired</code> runs
     */
    public void deliverLockAcquired(LockName lockName) {
        var lock = requireNonNull(locks.get(lockName), "Nothing called acquireLockAsync for " + lockName);
        lock.locked = true;
        callbackFor(lockName).lockAcquired(lock);
    }

    /**
     * Plays the async acquiring tick's <code>lockAcquired</code> arriving after the shutdown release already released the
     * lock locally and called <code>lockReleased</code> - the lock passed in is no longer locked
     */
    public void deliverLateLockAcquired(LockName lockName) {
        var lock = requireNonNull(locks.get(lockName), "Nothing called acquireLockAsync for " + lockName);
        if (!cancelled.contains(lockName)) {
            throw new IllegalStateException("cancelAsyncLockAcquiring was not called for " + lockName + " - nothing overlapped");
        }
        callbackFor(lockName).lockAcquired(lock);
    }

    /**
     * The shutdown release's local half: the lock, which the tick has recorded and is about to hand to
     * <code>lockAcquired</code>, is marked released and <code>lockReleased</code> is called
     */
    @Override
    public void cancelAsyncLockAcquiring(LockName lockName) {
        cancelled.add(lockName);
        var lock     = locks.get(lockName);
        var callback = callbacks.get(lockName);
        if (lock == null || callback == null) {
            return;
        }
        lock.locked = false;
        callback.lockReleased(lock);
    }

    public boolean wasCancelled(LockName lockName) {
        return cancelled.contains(lockName);
    }

    private LockCallback callbackFor(LockName lockName) {
        return requireNonNull(callbacks.get(lockName), "Nothing called acquireLockAsync for " + lockName);
    }

    @Override
    public Optional<FencedLock> lookupLock(LockName lockName) {
        return Optional.ofNullable(locks.get(lockName));
    }

    @Override
    public Optional<FencedLock> tryAcquireLock(LockName lockName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<FencedLock> tryAcquireLock(LockName lockName, java.time.Duration timeout) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FencedLock acquireLock(LockName lockName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isLockAcquired(LockName lockName) {
        var lock = locks.get(lockName);
        return lock != null && lock.locked;
    }

    @Override
    public boolean isLockedByThisLockManagerInstance(LockName lockName) {
        return isLockAcquired(lockName);
    }

    @Override
    public boolean isLockAcquiredByAnotherLockManagerInstance(LockName lockName) {
        return false;
    }

    @Override
    public String getLockManagerInstanceId() {
        return "shutdown-overlap";
    }

    @Override
    public void start() {
        started = true;
    }

    @Override
    public void stop() {
        started = false;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    private static final class TestLock implements FencedLock {
        private final    LockName       name;
        private final    long           token;
        private final    OffsetDateTime acquired = OffsetDateTime.now();
        private volatile boolean        locked;

        private TestLock(LockName name, long token) {
            this.name = name;
            this.token = token;
        }

        @Override
        public LockName getName() {
            return name;
        }

        @Override
        public Long getCurrentToken() {
            return token;
        }

        @Override
        public String getLockedByLockManagerInstanceId() {
            return locked ? "shutdown-overlap" : null;
        }

        @Override
        public OffsetDateTime getLockAcquiredTimestamp() {
            return acquired;
        }

        @Override
        public OffsetDateTime getLockLastConfirmedTimestamp() {
            return acquired;
        }

        @Override
        public boolean isLocked() {
            return locked;
        }

        @Override
        public boolean isLockedByThisLockManagerInstance() {
            return locked;
        }

        @Override
        public void release() {
            locked = false;
        }

        @Override
        public void registerCallback(LockCallback lockCallback) {
        }
    }
}
