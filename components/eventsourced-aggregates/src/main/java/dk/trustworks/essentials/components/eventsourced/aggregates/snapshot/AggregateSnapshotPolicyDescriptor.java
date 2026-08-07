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

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents a descriptor for defining snapshot policies associated with aggregate types in an event-sourced system.
 * This class consolidates metadata about the aggregate type, its snapshot policy, and optional additional settings.
 * <p>
 * This record ensures that all its fields are non-null during creation, throwing an exception if any required component
 * is missing or null.
 */
public record AggregateSnapshotPolicyDescriptor(
        Class<?> aggregateImplementationType,
        Optional<String> aggregateType,
        AggregateSnapshotPolicy policy
) {
    /**
     * Constructs an instance of {@code AggregateSnapshotPolicyDescriptor}, ensuring that all
     * provided parameters are non-null. This descriptor object encapsulates the relationship
     * between an aggregate type, its implementation class, and the corresponding snapshot policy.
     *
     * @param aggregateImplementationType the implementation type of the aggregate; must not be null
     * @param aggregateType               an optional name of the aggregate type; must not be null
     * @param policy                      the snapshot policy associated with the aggregate; must not be null
     * @throws IllegalArgumentException if any of the parameters are null
     */
    public AggregateSnapshotPolicyDescriptor {
        requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(policy, "No policy provided");
    }
}
