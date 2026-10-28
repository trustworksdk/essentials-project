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

import dk.trustworks.essentials.reactive.LocalEventBus;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public interface FencedLockEvents {
    FencedLockManager getLockManager();

    /**
     * Is published on the {@link LocalEventBus} when a {@link FencedLockManager#start()} is called (and the lock manager isn't already started)
     */
    class FencedLockManagerStarted implements FencedLockEvents {
        public final FencedLockManager lockManager;

        public FencedLockManagerStarted(FencedLockManager lockManager) {
            this.lockManager = requireNonNull(lockManager, "No lockManager instance provided");
        }

        @Override
        public FencedLockManager getLockManager() {
            return lockManager;
        }

        @Override
        public String toString() {
            return "FencedLockManagerStarted{" +
                    "lockManager=" + lockManager +
                    '}';
        }
    }

    /**
     * Is published on the {@link LocalEventBus} when a {@link FencedLockManager#stop()} is called (and the lock manager isn't already stopped)
     */
    class FencedLockManagerStopped implements FencedLockEvents {
        public final FencedLockManager lockManager;

        public FencedLockManagerStopped(FencedLockManager lockManager) {
            this.lockManager = requireNonNull(lockManager, "No lockManager instance provided");
        }

        @Override
        public FencedLockManager getLockManager() {
            return lockManager;
        }

        @Override
        public String toString() {
            return "FencedLockManagerStopped{" +
                    "lockManager=" + lockManager +
                    '}';
        }
    }

    /**
     * Is published on the {@link LocalEventBus} by a {@link DBFencedLockManager} when its scheduled lock confirmation has ended
     * (in practice on a critical error - see the class javadoc of {@link DBFencedLockManager}, "Errors"), after it has
     * released every lock it held locally. From then on {@link FencedLockManager#isLockConfirmationEnded()} returns
     * <code>true</code> and the lock manager acquires no lock, although {@link FencedLockManager#isStarted()} is still
     * <code>true</code>, until it is stopped and started again (or the application is restarted).
     * Published once per end of the confirmation, on the lock confirmation thread.
     */
    class LockConfirmationEnded implements FencedLockEvents {
        public final FencedLockManager lockManager;
        /**
         * What ended the scheduled lock confirmation
         */
        public final Throwable         failure;
        /**
         * The names of the locks the lock manager held, and released locally, as the confirmation ended
         */
        public final List<LockName>    releasedLocks;

        public LockConfirmationEnded(FencedLockManager lockManager, Throwable failure, List<LockName> releasedLocks) {
            this.lockManager = requireNonNull(lockManager, "No lockManager instance provided");
            this.failure = requireNonNull(failure, "No failure provided");
            this.releasedLocks = List.copyOf(requireNonNull(releasedLocks, "No releasedLocks provided"));
        }

        @Override
        public FencedLockManager getLockManager() {
            return lockManager;
        }

        @Override
        public String toString() {
            return "LockConfirmationEnded{" +
                    "lockManager=" + lockManager +
                    ", failure=" + failure +
                    ", releasedLocks=" + releasedLocks +
                    '}';
        }
    }

    class LockConfirmed implements FencedLockEvents {
        public final FencedLock        lock;
        public final FencedLockManager lockManager;

        public LockConfirmed(FencedLock lock, FencedLockManager lockManager) {
            this.lock = lock;
            this.lockManager = requireNonNull(lockManager, "No lockManager instance provided");
        }

        @Override
        public FencedLockManager getLockManager() {
            return lockManager;
        }

        @Override
        public String toString() {
            return "LockConfirmed{" +
                    "lock=" + lock +
                    ", lockManager=" + lockManager +
                    '}';
        }
    }

    class LockReleased implements FencedLockEvents {
        public final FencedLock        lock;
        public final FencedLockManager lockManager;

        public LockReleased(FencedLock lock, FencedLockManager lockManager) {
            this.lock = lock;
            this.lockManager = requireNonNull(lockManager, "No lockManager instance provided");
        }

        @Override
        public FencedLockManager getLockManager() {
            return lockManager;
        }

        @Override
        public String toString() {
            return "LockConfirmed{" +
                    "lock=" + lock +
                    ", lockManager=" + lockManager +
                    '}';
        }
    }

    class LockAcquired implements FencedLockEvents {
        public final FencedLock        lock;
        public final FencedLockManager lockManager;

        public LockAcquired(FencedLock lock, FencedLockManager lockManager) {
            this.lock = lock;
            this.lockManager = requireNonNull(lockManager, "No lockManager instance provided");
        }

        @Override
        public FencedLockManager getLockManager() {
            return lockManager;
        }

        @Override
        public String toString() {
            return "LockAcquired{" +
                    "lock=" + lock +
                    ", lockManager=" + lockManager +
                    '}';
        }
    }

}
