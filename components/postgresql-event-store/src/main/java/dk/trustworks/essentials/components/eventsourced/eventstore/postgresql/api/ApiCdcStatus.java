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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api;

/**
 * Change Data Capture status of the queried instance.
 *
 * @param availability  the current CDC state, and how subscriptions have fared against it
 * @param configuration the effective CDC configuration
 * @param slot          the replication slot's state
 * @param tailer        the WAL replication tailer's state, or null if there is none in this instance
 * @param dispatcher    the CDC dispatcher's state, or null if there is none in this instance
 * @param interruptions times CDC stopped being active other than by a requested stop, kept after it recovers
 */
public record ApiCdcStatus(
        ApiCdcAvailability availability,
        ApiCdcConfiguration configuration,
        ApiCdcSlotStatus slot,
        ApiCdcTailerStatus tailer,
        ApiCdcDispatcherStatus dispatcher,
        ApiCdcInterruptions interruptions
) {
    /**
     * A status with no interruption recorded ({@link ApiCdcInterruptions#none()}) - the shape before
     * {@link #interruptions()} existed, kept so code constructing a status, typically a test double, keeps compiling.
     */
    public ApiCdcStatus(ApiCdcAvailability availability,
                        ApiCdcConfiguration configuration,
                        ApiCdcSlotStatus slot,
                        ApiCdcTailerStatus tailer,
                        ApiCdcDispatcherStatus dispatcher) {
        this(availability, configuration, slot, tailer, dispatcher, ApiCdcInterruptions.none());
    }
}
