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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Represents the configuration for durable asynchronous snapshot processing.
 * This configuration controls aspects such as polling interval, batch size,
 * worker threads, retry behavior, and delay between retries.
 * <p>
 * Instances of this record can be created with custom configuration or by
 * using the default settings provided by the {@link #defaults()} factory method.
 * <p>
 * Each configuration parameter has the following constraints:
 * - The poll interval must be positive and non-zero.
 * - The batch size must be greater than zero.
 * - The number of worker threads must be greater than zero.
 * - The maximum retries must be zero or greater.
 * - The retry delay must be non-negative.
 */
public record DurableAsyncSnapshotSettings(
        Duration pollInterval,
        int batchSize,
        int workerThreads,
        int maxRetries,
        Duration retryDelay
) {
    public DurableAsyncSnapshotSettings(Duration pollInterval,
                                        int batchSize,
                                        int workerThreads,
                                        int maxRetries,
                                        Duration retryDelay) {
        this.pollInterval = requireNonNull(pollInterval, "No pollInterval provided");
        requireTrue(!pollInterval.isNegative() && !pollInterval.isZero(), "pollInterval must be > 0");
        requireTrue(batchSize > 0, "batchSize must be > 0");
        requireTrue(workerThreads > 0, "workerThreads must be > 0");
        requireTrue(maxRetries >= 0, "maxRetries must be >= 0");
        this.batchSize = batchSize;
        this.workerThreads = workerThreads;
        this.maxRetries = maxRetries;
        this.retryDelay = requireNonNull(retryDelay, "No retryDelay provided");
        requireTrue(!retryDelay.isNegative(), "retryDelay must be >= 0");
    }

    public static DurableAsyncSnapshotSettings defaults() {
        return new DurableAsyncSnapshotSettings(Duration.ofSeconds(1),
                                                25,
                                                2,
                                                10,
                                                Duration.ofSeconds(5));
    }
}
