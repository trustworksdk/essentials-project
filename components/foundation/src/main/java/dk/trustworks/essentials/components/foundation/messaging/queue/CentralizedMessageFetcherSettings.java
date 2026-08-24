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

package dk.trustworks.essentials.components.foundation.messaging.queue;

import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * How a {@link CentralizedMessageFetcher} polls: the tick interval, which claim strategy it uses, and whether
 * acknowledgements are coalesced.
 * <p>
 * Exists because the fetcher's widest constructor reached six parameters, above the project's five-parameter
 * ceiling — see {@code docs/constructor-ergonomics-and-optional-policy.md}. A record used as a parameter object is
 * exempt from that ceiling, being wide is its job.
 *
 * @param pollingIntervalMs           the tick interval in milliseconds
 * @param useBatchedFetch             opt in to claiming across all active queues in one statement. When
 *                                    {@code false} every poll uses per-queue fetching regardless of how many
 *                                    queues are active, and {@code batchedFetchSwitchThreshold} is ignored
 * @param batchedFetchSwitchThreshold only consulted when {@code useBatchedFetch} is {@code true}: per-queue fetch
 *                                    for active-queue counts &lt;= threshold, batched fetch above it
 * @param acknowledgementBuffer       coalesces acknowledgements into batches, or {@code null} to acknowledge each
 *                                    message in its own transaction. {@link OrderedMessage}s are acknowledged
 *                                    immediately either way — see {@link BatchedAcknowledgementBuffer}
 */
public record CentralizedMessageFetcherSettings(long pollingIntervalMs,
                                                boolean useBatchedFetch,
                                                int batchedFetchSwitchThreshold,
                                                BatchedAcknowledgementBuffer acknowledgementBuffer) {

    public CentralizedMessageFetcherSettings {
        requireTrue(pollingIntervalMs > 0, "pollingIntervalMs must be > 0");
        requireTrue(batchedFetchSwitchThreshold >= 0, "batchedFetchSwitchThreshold must be >= 0");
    }

    /**
     * Per-queue fetching, no acknowledgement batching.
     *
     * @param pollingIntervalMs the tick interval in milliseconds
     */
    public static CentralizedMessageFetcherSettings perQueueFetch(long pollingIntervalMs) {
        return new CentralizedMessageFetcherSettings(pollingIntervalMs, false, 0, null);
    }
}
