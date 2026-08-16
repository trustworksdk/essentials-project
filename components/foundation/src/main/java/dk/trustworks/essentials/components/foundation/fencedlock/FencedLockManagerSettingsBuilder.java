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
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link FencedLockManagerSettings}, obtained from {@link FencedLockManagerSettings#builder()}.
 * <p>
 * {@code lockManagerInstanceId} is held as a plain nullable field and resolved in {@link #build()} — the neutral
 * default being the machine's hostname. That is why no setter here takes an {@code Optional}, except as an explicit
 * convenience overload for callers coming from a Spring {@code @Bean} method.
 */
public final class FencedLockManagerSettingsBuilder {
    private String   lockManagerInstanceId;
    private Duration lockTimeOut;
    private Duration lockConfirmationInterval;
    private boolean  releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation;

    /**
     * @param lockManagerInstanceId the unique name for this lock manager instance, or {@code null} to use the machine's hostname.
     *                              Containers without a stable hostname should set this explicitly
     * @return this builder instance for fluent chaining
     */
    public FencedLockManagerSettingsBuilder setLockManagerInstanceId(String lockManagerInstanceId) {
        this.lockManagerInstanceId = lockManagerInstanceId;
        return this;
    }

    /**
     * {@code Optional} overload of {@link #setLockManagerInstanceId(String)}. An empty {@code Optional} means "use the
     * machine's hostname".
     *
     * @param lockManagerInstanceId the instance id, or empty to derive one
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public FencedLockManagerSettingsBuilder setLockManagerInstanceId(Optional<String> lockManagerInstanceId) {
        requireNonNull(lockManagerInstanceId, "No lockManagerInstanceId provided");
        return setLockManagerInstanceId(lockManagerInstanceId.orElse(null));
    }

    /**
     * @param lockTimeOut the period after which an unconfirmed lock is considered timed out. Required
     * @return this builder instance for fluent chaining
     */
    public FencedLockManagerSettingsBuilder setLockTimeOut(Duration lockTimeOut) {
        this.lockTimeOut = lockTimeOut;
        return this;
    }

    /**
     * @param lockConfirmationInterval how often locks are confirmed. MUST be shorter than {@link #setLockTimeOut(Duration)}. Required
     * @return this builder instance for fluent chaining
     */
    public FencedLockManagerSettingsBuilder setLockConfirmationInterval(Duration lockConfirmationInterval) {
        this.lockConfirmationInterval = lockConfirmationInterval;
        return this;
    }

    /**
     * @param releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation whether locks are released locally when lock confirmation fails with an
     *                                                                       IO exception. Defaults to {@code false}, which retains them as locked
     * @return this builder instance for fluent chaining
     */
    public FencedLockManagerSettingsBuilder setReleaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation(boolean releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation) {
        this.releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation = releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation;
        return this;
    }

    /**
     * Builds the settings, resolving {@code lockManagerInstanceId} to the machine's hostname if it was not set, and
     * validating that {@code lockConfirmationInterval} is shorter than {@code lockTimeOut}.
     *
     * @return the settings
     */
    public FencedLockManagerSettings build() {
        return new FencedLockManagerSettings(FencedLockManagerSettings.resolveLockManagerInstanceId(lockManagerInstanceId),
                                             lockTimeOut,
                                             lockConfirmationInterval,
                                             releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation);
    }
}
