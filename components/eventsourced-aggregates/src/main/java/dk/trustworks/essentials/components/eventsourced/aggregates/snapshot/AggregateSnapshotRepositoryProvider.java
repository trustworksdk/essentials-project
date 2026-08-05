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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

import java.util.Optional;

/**
 * Interface for resolving an {@link AggregateSnapshotRepository} based on an {@link AggregateType}
 * and its corresponding implementation type.
 * <p>
 * Implementations of this interface may provide mechanisms such as caching or conditional repository creation
 * based on the provided input parameters.
 */
public interface AggregateSnapshotRepositoryProvider {
    /**
     * Resolves an {@link AggregateSnapshotRepository} for the provided {@link AggregateType}
     * and corresponding aggregate implementation type.
     *
     * @param aggregateType                 the type of the aggregate, which typically determines the event stream name
     * @param aggregateImplementationType   the concrete class representing the implementation type of the aggregate
     * @return an {@link Optional} containing the resolved {@link AggregateSnapshotRepository} if available,
     *         or {@link Optional#empty()} if no repository could be resolved
     */
    Optional<AggregateSnapshotRepository> resolve(AggregateType aggregateType,
                                                  Class<?> aggregateImplementationType);
}
