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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcEventStore;

/**
 * Resolves the {@link ConfigurableEventStore} behind an {@link EventStore} that may be wrapped in one or more
 * decorators.
 * <p>
 * Event processors need the configuration side of the event store (to look up an {@link AggregateType}'s
 * {@code aggregateIdSerializer}), but the {@link EventStore} bean they are handed can be a {@link CdcEventStore},
 * which decorates the configurable store and only implements the narrower {@link EventStore} contract. Casting the
 * bean directly therefore fails with a {@link ClassCastException} as soon as CDC is enabled.
 */
final class ConfigurableEventStoreResolver {

    private ConfigurableEventStoreResolver() {
    }

    /**
     * @param eventStore the event store to resolve, possibly a decorator
     * @return the {@link ConfigurableEventStore} that {@code eventStore} either is or decorates
     * @throws IllegalStateException if no {@link ConfigurableEventStore} can be reached
     */
    static ConfigurableEventStore<?> resolve(EventStore eventStore) {
        var candidate = eventStore;
        while (candidate != null) {
            if (candidate instanceof ConfigurableEventStore<?> configurableEventStore) {
                return configurableEventStore;
            }
            candidate = candidate instanceof CdcEventStore cdcEventStore ? cdcEventStore.getDelegate() : null;
        }
        throw new IllegalStateException("Could not resolve a ConfigurableEventStore from EventStore of type '" + eventStore.getClass().getName() + "'");
    }
}
