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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Configuration settings for managing the closing of books in a system.
 * This class encapsulates parameters required for polling, batching, and locking mechanisms.
 *
 * @param pollInterval      the interval at which polling for closing book events should occur.
 *                          Must be greater than zero.
 * @param batchSize         the maximum number of events or records to process in a single batch.
 *                          Must be greater than or equal to 1.
 * @param lockAcquireTimeout the maximum duration to wait for acquiring a lock while processing.
 *                           Must be greater than or equal to zero.
 */
public record ClosingBooksManagerSettings(Duration pollInterval,
                                          int batchSize,
                                          Duration lockAcquireTimeout) {
    /**
     * Constructs an instance of ClosingBooksManagerSettings with the specified configuration parameters.
     * Validates that provided arguments meet the required constraints.
     *
     * @param pollInterval      the interval at which polling for closing book events should occur.
     *                          Must not be null and must be greater than zero.
     * @param batchSize         the maximum number of events or records to process in a single batch.
     *                          Must be greater than or equal to 1.
     * @param lockAcquireTimeout the maximum duration to wait for acquiring a lock while processing.
     *                           Must not be null and must be greater than or equal to zero.
     * @throws IllegalArgumentException if any of the provided arguments do not meet the required constraints.
     */
    public ClosingBooksManagerSettings {
        requireNonNull(pollInterval, "No pollInterval provided");
        requireNonNull(lockAcquireTimeout, "No lockAcquireTimeout provided");
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be > 0");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        if (lockAcquireTimeout.isNegative()) {
            throw new IllegalArgumentException("lockAcquireTimeout must be >= 0");
        }
    }
}
