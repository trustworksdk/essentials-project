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

import java.util.*;

/**
 * Registry for managing {@link AggregateSnapshotPolicyDescriptor} instances, which define snapshot policies
 * associated with specific aggregate implementation types in an event-sourced system.
 * This interface provides methods for registering, retrieving, and listing snapshot policy descriptors.
 */
public interface AggregateSnapshotPolicyRegistry {
    /**
     * Registers an {@link AggregateSnapshotPolicyDescriptor} in the registry.
     * The descriptor defines the snapshot policy associated with a specific aggregate
     * implementation type in an event-sourced system.
     *
     * @param descriptor the {@link AggregateSnapshotPolicyDescriptor} to be registered;
     *                   must not be null
     * @throws IllegalArgumentException if the descriptor is null
     */
    void register(AggregateSnapshotPolicyDescriptor descriptor);

    /**
     * Retrieves an {@link AggregateSnapshotPolicyDescriptor} from the registry based on the provided
     * aggregate implementation type. If a descriptor is registered for the specified type, it will
     * be returned wrapped in an {@link Optional}. If no descriptor is found, returns an empty {@link Optional}.
     *
     * @param aggregateImplementationType the implementation type of the aggregate whose
     *                                     snapshot policy descriptor is to be retrieved; must not be null
     * @return an {@link Optional} containing the {@link AggregateSnapshotPolicyDescriptor} associated
     *         with the given aggregate implementation type, or an empty {@link Optional} if no descriptor is found
     */
    Optional<AggregateSnapshotPolicyDescriptor> findByAggregateImplementationType(Class<?> aggregateImplementationType);

    /**
     * Retrieves all registered {@link AggregateSnapshotPolicyDescriptor} instances from the registry.
     * These descriptors represent snapshot policies associated with various aggregate types
     * in an event-sourced system.
     *
     * @return a collection containing all registered {@link AggregateSnapshotPolicyDescriptor} instances.
     *         Returns an empty collection if no policies have been registered.
     */
    Collection<AggregateSnapshotPolicyDescriptor> getRegisteredPolicies();
}
