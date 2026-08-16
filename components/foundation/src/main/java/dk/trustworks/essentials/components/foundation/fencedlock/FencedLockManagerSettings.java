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

import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.shared.network.Network;

import java.time.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * The tuning knobs shared by every {@link DBFencedLockManager} implementation, gathered into one cohesive value so
 * that a lock manager's constructor carries its collaborators (storage, unit-of-work factory, event bus) and not its
 * configuration.
 * <p>
 * The two invariants that used to be checked inside {@link DBFencedLockManager}'s constructor are checked here
 * instead, at the moment the settings are created — so an impossible configuration cannot be passed around and fail
 * later:
 * <ul>
 *     <li>{@code lockConfirmationInterval} must be strictly shorter than {@code lockTimeOut}, or every lock times out
 *         between confirmations.</li>
 *     <li>{@code lockManagerInstanceId} must resolve to something. Left unset it defaults to the machine's hostname,
 *         which is why it is a plain nullable input rather than an {@code Optional} — see
 *         {@link #lockManagerInstanceId()}.</li>
 * </ul>
 *
 * @param lockManagerInstanceId                                          the unique name for this lock manager instance. Never {@code null} once
 *                                                                       constructed — see {@link #withDefaults(Duration, Duration)}
 * @param lockTimeOut                                                    the period between {@link FencedLock#getLockLastConfirmedTimestamp()} and the current time
 *                                                                       before the lock is considered timed out
 * @param lockConfirmationInterval                                       how often locks are confirmed. MUST be shorter than {@code lockTimeOut}
 * @param releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation should {@link FencedLock}s acquired by this manager be released when
 *                                                                       {@link FencedLockStorage#confirmLockInDB(DBFencedLockManager, UnitOfWork, DBFencedLock, OffsetDateTime)}
 *                                                                       fails with an exception for which {@link IOExceptionUtil#isIOException(Throwable)}
 *                                                                       returns true. {@code true} releases them locally; {@code false} retains them as locked
 */
public record FencedLockManagerSettings(String lockManagerInstanceId,
                                        Duration lockTimeOut,
                                        Duration lockConfirmationInterval,
                                        boolean releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation) {

    public FencedLockManagerSettings {
        requireNonNull(lockManagerInstanceId, "No lockManagerInstanceId provided - use FencedLockManagerSettings.builder() to default it to the machine's hostname");
        requireNonNull(lockTimeOut, "No lockTimeOut value provided");
        requireNonNull(lockConfirmationInterval, "No lockConfirmationInterval value provided");
        if (lockConfirmationInterval.compareTo(lockTimeOut) >= 1) {
            throw new IllegalArgumentException(msg("lockConfirmationInterval {} duration MUST not be larger than the lockTimeOut {} duration, because locks will then always timeout",
                                                   lockConfirmationInterval,
                                                   lockTimeOut));
        }
    }

    /**
     * Creates a new builder. {@code lockManagerInstanceId} defaults to the machine's hostname and
     * {@code releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation} defaults to {@code false}, so only
     * {@code lockTimeOut} and {@code lockConfirmationInterval} are strictly required.
     *
     * @return a new builder
     */
    public static FencedLockManagerSettingsBuilder builder() {
        return new FencedLockManagerSettingsBuilder();
    }

    /**
     * Settings using the machine's hostname as the instance id and retaining locks across IO exceptions during
     * confirmation — the historical defaults.
     *
     * @param lockTimeOut              see {@link #lockTimeOut()}
     * @param lockConfirmationInterval see {@link #lockConfirmationInterval()}
     * @return the settings
     */
    public static FencedLockManagerSettings withDefaults(Duration lockTimeOut, Duration lockConfirmationInterval) {
        return builder().setLockTimeOut(lockTimeOut)
                        .setLockConfirmationInterval(lockConfirmationInterval)
                        .build();
    }

    /**
     * Resolves a lock-manager instance id: the supplied value, or the machine's hostname when it is {@code null}.
     *
     * @param lockManagerInstanceId the requested id, or {@code null} to derive one
     * @return a non-null instance id
     */
    static String resolveLockManagerInstanceId(String lockManagerInstanceId) {
        return lockManagerInstanceId != null
               ? lockManagerInstanceId
               : requireNonNull(Network.hostName(), "Couldn't resolve a LockManager instanceId");
    }
}
