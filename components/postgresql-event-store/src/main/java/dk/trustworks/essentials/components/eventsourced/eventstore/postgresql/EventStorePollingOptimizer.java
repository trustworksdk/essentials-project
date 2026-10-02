/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

/**
 * Interface for optimizing polling behavior of an event store. This interface provides
 * mechanisms to adjust polling intervals and strategies based on the results of previous
 * polling attempts. Implementations can define specific backoff or jitter strategies to
 * manage polling efficiency and load on the system.
 */
public interface EventStorePollingOptimizer {
    /**
     * An optimizer that never skips or delays polling of its own. A polling worker using it still waits the
     * polling interval after a poll that returned no events - without that it would poll again at once, in a busy
     * loop. Use {@link SimpleEventStorePollingOptimizer} or {@link JitteredEventStorePollingOptimizer} to back off
     * further while the event store is idle.
     */
    static EventStorePollingOptimizer None() {
        return new NoEventStorePollingOptimizer();
    }

    void eventStorePollingReturnedNoEvents();

    void eventStorePollingReturnedEvents();

    @Deprecated
    boolean shouldSkipPolling();

    long currentDelayMs();
}
