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
 *     <li>{@code lockManagerInstanceId} must resolve to something. Left unset it defaults to
 *         {@link #defaultLockManagerInstanceId() hostname:pid}, which is why it is a plain nullable input rather than an
 *         {@code Optional} — see {@link #lockManagerInstanceId()}.</li>
 * </ul>
 *
 * @param lockManagerInstanceId                                          the unique name for this lock manager instance. Never {@code null} once
 *                                                                       constructed — see {@link #withDefaults(Duration, Duration)}. It is what
 *                                                                       {@link FencedLock#getLockedByLockManagerInstanceId()}, the lock table and the
 *                                                                       admin API report as the lock holder, and what
 *                                                                       {@link FencedLock#isLockedByThisLockManagerInstance()} compares against, so it must
 *                                                                       be unique per running lock manager. Default: {@link #defaultLockManagerInstanceId()}
 * @param lockTimeOut                                                    the period between {@link FencedLock#getLockLastConfirmedTimestamp()} and the current time
 *                                                                       before the lock is considered timed out
 * @param lockConfirmationInterval                                       how often locks are confirmed. MUST be shorter than {@code lockTimeOut}
 * @param releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation should {@link FencedLock}s acquired by this manager be released when
 *                                                                       {@link FencedLockStorage#confirmLockInDB(DBFencedLockManager, UnitOfWork, DBFencedLock, OffsetDateTime)}
 *                                                                       fails with an exception for which {@link IOExceptionUtil#isIOException(Throwable)}
 *                                                                       returns true. {@code true} releases them locally; {@code false} retains them as locked
 * @param lockSpreading                                                  whether locks acquired asynchronously are spread over the live lock manager instances -
 *                                                                       {@link LockSpreading#disabled()} by default. Read {@link LockSpreading}'s costs before
 *                                                                       enabling it
 */
public record FencedLockManagerSettings(String lockManagerInstanceId,
                                        Duration lockTimeOut,
                                        Duration lockConfirmationInterval,
                                        boolean releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation,
                                        LockSpreading lockSpreading) {

    public FencedLockManagerSettings {
        requireNonNull(lockSpreading, "No lockSpreading provided - use LockSpreading.disabled()");
        requireNonNull(lockManagerInstanceId, "No lockManagerInstanceId provided - use FencedLockManagerSettings.builder() to default it to hostname:pid");
        requireNonNull(lockTimeOut, "No lockTimeOut value provided");
        requireNonNull(lockConfirmationInterval, "No lockConfirmationInterval value provided");
        // Strictly shorter, as documented: confirming exactly at the timeout loses the lock whenever a confirmation is late
        if (lockConfirmationInterval.compareTo(lockTimeOut) >= 0) {
            throw new IllegalArgumentException(msg("lockConfirmationInterval {} duration MUST be shorter than the lockTimeOut {} duration, because locks will otherwise time out",
                                                   lockConfirmationInterval,
                                                   lockTimeOut));
        }
    }

    /**
     * Settings with locks not spread ({@link LockSpreading#disabled()}).
     *
     * @param lockManagerInstanceId                                          see {@link #lockManagerInstanceId()}
     * @param lockTimeOut                                                    see {@link #lockTimeOut()}
     * @param lockConfirmationInterval                                       see {@link #lockConfirmationInterval()}
     * @param releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation see {@link #releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation()}
     * @deprecated use {@link #builder()}, or the canonical constructor with {@link LockSpreading#disabled()}
     */
    @Deprecated(forRemoval = true, since = "0.61.0")
    public FencedLockManagerSettings(String lockManagerInstanceId,
                                     Duration lockTimeOut,
                                     Duration lockConfirmationInterval,
                                     boolean releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation) {
        this(lockManagerInstanceId, lockTimeOut, lockConfirmationInterval, releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation,
             LockSpreading.disabled());
    }

    /**
     * Creates a new builder. {@code lockManagerInstanceId} defaults to {@link #defaultLockManagerInstanceId() hostname:pid} and
     * {@code releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation} defaults to {@code false}, so only
     * {@code lockTimeOut} and {@code lockConfirmationInterval} are strictly required.
     *
     * @return a new builder
     */
    public static FencedLockManagerSettingsBuilder builder() {
        return new FencedLockManagerSettingsBuilder();
    }

    /**
     * Settings using {@link #defaultLockManagerInstanceId() hostname:pid} as the instance id and retaining locks across IO
     * exceptions during confirmation.
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
     * The lock-manager instance id used when none is set: {@code <hostname>:<pid>}, e.g. {@code order-service-7d9f:1}.
     * <p>
     * Before 0.60.1 the default was the bare hostname, so two JVMs on one host - or two containers that share a
     * hostname - reported the same lock holder, and {@link FencedLock#isLockedByThisLockManagerInstance()} was true in
     * both. Mutual exclusion never depended on it: acquiring, confirming and releasing a lock are compare-and-set
     * updates on the lock's fence token. The id only decides who a lock is <em>reported</em> as held by - in the lock
     * table, the admin API and the logs - and the ownership predicates.
     * <p>
     * The pid is not stable across restarts. Nothing relies on that: a restarted lock manager never adopts a lock row
     * because its id matches - it acquires a lock only once the previous holder released it or it timed out. Set an
     * explicit id where a stable, readable one matters.
     *
     * @return {@code <hostname>:<pid>}
     */
    public static String defaultLockManagerInstanceId() {
        return requireNonNull(Network.hostName(), "Couldn't resolve a LockManager instanceId") + ":" + ProcessHandle.current().pid();
    }

    /**
     * Resolves a lock-manager instance id: the supplied value, or {@link #defaultLockManagerInstanceId()} when it is {@code null}.
     *
     * @param lockManagerInstanceId the requested id, or {@code null} to derive one
     * @return a non-null instance id
     */
    static String resolveLockManagerInstanceId(String lockManagerInstanceId) {
        return lockManagerInstanceId != null
               ? lockManagerInstanceId
               : defaultLockManagerInstanceId();
    }
}
