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
 * The {@link EventStorePollingOptimizer} returned by {@link EventStorePollingOptimizer#None()}. A class of its own so
 * the polling worker can tell it from an optimizer that returns a zero delay on purpose (such as
 * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.notify.NotifyAwareEventStorePollingOptimizer},
 * which returns zero to re-poll at once when a NOTIFY has landed).
 */
final class NoEventStorePollingOptimizer implements EventStorePollingOptimizer {
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
}
