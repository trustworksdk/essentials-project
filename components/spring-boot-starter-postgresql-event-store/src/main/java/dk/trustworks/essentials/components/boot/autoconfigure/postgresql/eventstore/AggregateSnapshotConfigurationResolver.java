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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

/**
 * Interface defining the contract for resolving the configuration required to manage
 * Aggregate snapshot operations in an event-sourcing setup.
 */
public interface AggregateSnapshotConfigurationResolver {

    /**
     * Resolves and retrieves the snapshot configuration for a specified Aggregate type and its implementation.
     *
     * @param aggregateType the type of the aggregate for which the snapshot configuration is being resolved
     * @param aggregateImplementationType the class type representing the specific implementation of the aggregate
     * @return the resolved aggregate snapshot configuration containing snapshot settings such as enablement,
     *         execution mode, and retention policies
     */
    ResolvedAggregateSnapshotConfiguration resolve(AggregateType aggregateType,
                                                   Class<?> aggregateImplementationType);
}
