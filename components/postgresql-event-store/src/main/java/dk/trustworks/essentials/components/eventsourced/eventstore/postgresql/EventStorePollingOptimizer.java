/*
 *  Copyright 2021-2025 the original author or authors.
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
    static EventStorePollingOptimizer None() {
        return new EventStorePollingOptimizer() {

            @Override
            public void eventStorePollingReturnedNoEvents() {
            }

            @Override
            public void eventStorePollingReturnedEvents() {
            }

            @Override
            public boolean shouldSkipPolling() {
                return false;
            }

            @Override
            public long currentDelayMs() {
                return 0L;
            }

            @Override
            public String toString() {
                return "NoEventStorePollingOptimizer";
            }
        };
    }

    void eventStorePollingReturnedNoEvents();

    void eventStorePollingReturnedEvents();

    @Deprecated
    boolean shouldSkipPolling();

    long currentDelayMs();
}
