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

import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;

import java.time.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Base class for implementing persistent/durable {@link FencedLockManager}'s
 * @see DBFencedLockManager
 */
public class DBFencedLock implements FencedLock {
    /**
     * The name of the lock
     */
    private LockName lockName;
    /**
     * The current token value as of the {@link #getLockLastConfirmedTimestamp()} for this Lock across all {@link FencedLockManager} instances<br>
     * Every time a lock is acquired a new token is issued (i.e. it's ever-growing monotonic value)
     * <p>
     * To avoid two lock holders from interacting with other services, the fencing token MUST be passed
     * to external services. The external services must store the largest fencing token received, whereby they can ignore
     * requests with a lower fencing token.
     */
    private Long     currentToken;

    /**
     * Which JVM/{@link FencedLockManager#getLockManagerInstanceId()} that has acquired this lock
     */
    private String         lockedByLockManagerInstanceId;
    /**
     * At what time did the JVM/{@link FencedLockManager#getLockManagerInstanceId()} that currently has acquired the lock acquire it (at first acquiring the lock_last_confirmed_ts is set to lock_acquired_ts)
     */
    private OffsetDateTime lockAcquiredTimestamp;
    /**
     * At what time did the JVM/{@link FencedLockManager}, that currently has acquired the lock, last confirm that it still has access to the lock
     */
    private OffsetDateTime lockLastConfirmedTimestamp;

    private transient DBFencedLockManager<? extends UnitOfWork, DBFencedLock> fencedLockManager;
    private transient List<LockCallback>  lockCallbacks;

    /**
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    public DBFencedLock(DBFencedLockManager<? extends UnitOfWork, DBFencedLock> fencedLockManager,
                        LockName lockName,
                        Long currentToken,
                        String lockedByBusInstanceId,
                        OffsetDateTime lockAcquiredTimestamp,
                        OffsetDateTime lockLastConfirmedTimestamp) {
        this.fencedLockManager = fencedLockManager;
        this.lockName = lockName;
        this.currentToken = currentToken;
        this.lockedByLockManagerInstanceId = lockedByBusInstanceId;
        this.lockAcquiredTimestamp = lockAcquiredTimestamp;
        this.lockLastConfirmedTimestamp = lockLastConfirmedTimestamp;
        lockCallbacks = new ArrayList<>();
    }

    @Override
    public LockName getName() {
        return lockName;
    }

    @Override
    public Long getCurrentToken() {
        return currentToken;
    }

    @Override
    public String getLockedByLockManagerInstanceId() {
        return lockedByLockManagerInstanceId;
    }

    @Override
    public OffsetDateTime getLockAcquiredTimestamp() {
        return lockAcquiredTimestamp;
    }

    @Override
    public OffsetDateTime getLockLastConfirmedTimestamp() {
        return lockLastConfirmedTimestamp;
    }

    @Override
    public boolean isLocked() {
        return lockedByLockManagerInstanceId != null;
    }

    @Override
    public boolean isLockedByThisLockManagerInstance() {
        return isLocked() && Objects.equals(lockedByLockManagerInstanceId, fencedLockManager.getLockManagerInstanceId());
    }

    @Override
    public void release() {
        fencedLockManager.releaseLock(this);
    }

    @Override
    public void registerCallback(LockCallback lockCallback) {
        lockCallbacks.add(lockCallback);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        var that = (DBFencedLock) o;
        return getName().equals(that.getName());
    }


    @Override
    public int hashCode() {
        return Objects.hash(getName());
    }

    public Duration getDurationSinceLastConfirmation() {
        requireNonNull(lockLastConfirmedTimestamp, msg("FencedLock '{}' doesn't have a lockLastConfirmedTimestamp", getName()));
        return Duration.between(lockLastConfirmedTimestamp, ZonedDateTime.now()).abs();
    }

    public void markAsReleased() {
        lockCallbacks.forEach(lockCallback -> lockCallback.lockReleased(this));
        lockedByLockManagerInstanceId = null;
    }

    DBFencedLock markAsConfirmed(OffsetDateTime confirmedTimestamp) {
        lockLastConfirmedTimestamp = requireNonNull(confirmedTimestamp, "confirmedTimestamp is null");;
        return this;
    }

    public DBFencedLock markAsLocked(OffsetDateTime lockTime, String lockedByLockManagerInstanceId, long currentToken) {
        this.lockAcquiredTimestamp = requireNonNull(lockTime, "lockTime is null");
        this.lockLastConfirmedTimestamp = requireNonNull(lockTime, "lockTime is null");
        this.lockedByLockManagerInstanceId = requireNonNull(lockedByLockManagerInstanceId, "lockedByLockManagerInstanceId is null");
        this.currentToken = currentToken;
        lockCallbacks.forEach(lockCallback -> lockCallback.lockAcquired(this));
        return this;
    }

    public String toString() {
        return this.getClass().getSimpleName() + "{" +
                "lockName=" + lockName +
                ", currentTokenIssuedToThisLockInstance=" + currentToken +
                ", lockedByLockManagerInstanceId='" + lockedByLockManagerInstanceId + '\'' +
                ", lockAcquiredTimestamp=" + lockAcquiredTimestamp +
                ", lockLastConfirmedTimestamp=" + lockLastConfirmedTimestamp +
                '}';
    }

    /**
     * Creates a builder for a {@link DBFencedLock}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link DBFencedLock}, obtained from {@link #builder()}.
     * <p>
     * The previously-{@code Optional} constructor parameters are plain nullable fields here, each with a
     * plain-value setter and an {@code Optional} overload.
     */
    public static final class Builder {
        private DBFencedLockManager<? extends UnitOfWork, DBFencedLock> fencedLockManager;
        private LockName lockName;
        private Long currentToken;
        private String lockedByBusInstanceId;
        private OffsetDateTime lockAcquiredTimestamp;
        private OffsetDateTime lockLastConfirmedTimestamp;

        /**
         * @param fencedLockManager required
         * @return this builder
         */
        public Builder setFencedLockManager(DBFencedLockManager<? extends UnitOfWork, DBFencedLock> fencedLockManager) {
            this.fencedLockManager = fencedLockManager;
            return this;
        }

        /**
         * @param lockName required
         * @return this builder
         */
        public Builder setLockName(LockName lockName) {
            this.lockName = lockName;
            return this;
        }

        /**
         * @param currentToken required
         * @return this builder
         */
        public Builder setCurrentToken(Long currentToken) {
            this.currentToken = currentToken;
            return this;
        }

        /**
         * @param lockedByBusInstanceId required
         * @return this builder
         */
        public Builder setLockedByBusInstanceId(String lockedByBusInstanceId) {
            this.lockedByBusInstanceId = lockedByBusInstanceId;
            return this;
        }

        /**
         * @param lockAcquiredTimestamp required
         * @return this builder
         */
        public Builder setLockAcquiredTimestamp(OffsetDateTime lockAcquiredTimestamp) {
            this.lockAcquiredTimestamp = lockAcquiredTimestamp;
            return this;
        }

        /**
         * @param lockLastConfirmedTimestamp required
         * @return this builder
         */
        public Builder setLockLastConfirmedTimestamp(OffsetDateTime lockLastConfirmedTimestamp) {
            this.lockLastConfirmedTimestamp = lockLastConfirmedTimestamp;
            return this;
        }

        /**
         * @return the new {@link DBFencedLock}
         */
        @SuppressWarnings("removal")
        public DBFencedLock build() {
            return new DBFencedLock(fencedLockManager,
                                    lockName,
                                    currentToken,
                                    lockedByBusInstanceId,
                                    lockAcquiredTimestamp,
                                    lockLastConfirmedTimestamp);
        }
    }

}
