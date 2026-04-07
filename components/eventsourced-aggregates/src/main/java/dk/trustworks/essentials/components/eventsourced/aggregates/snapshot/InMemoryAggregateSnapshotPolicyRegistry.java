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
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An in-memory implementation of the {@code AggregateSnapshotPolicyRegistry} interface.
 * This registry manages the registration and retrieval of {@link AggregateSnapshotPolicyDescriptor} instances,
 * which define snapshot policies associated with specific aggregate implementation types
 * in an event-sourced system.
 * <p>
 * This class utilizes a thread-safe {@link ConcurrentHashMap} to store descriptors, ensuring
 * that operations are safe for concurrent use.
 */
public class InMemoryAggregateSnapshotPolicyRegistry implements AggregateSnapshotPolicyRegistry {
    private final Map<Class<?>, AggregateSnapshotPolicyDescriptor> descriptors = new ConcurrentHashMap<>();

    @Override
    public void register(AggregateSnapshotPolicyDescriptor descriptor) {
        requireNonNull(descriptor, "No descriptor provided");
        descriptors.put(descriptor.aggregateImplementationType(), descriptor);
    }

    @Override
    public Optional<AggregateSnapshotPolicyDescriptor> findByAggregateImplementationType(Class<?> aggregateImplementationType) {
        requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");
        return Optional.ofNullable(descriptors.get(aggregateImplementationType));
    }

    @Override
    public Collection<AggregateSnapshotPolicyDescriptor> getRegisteredPolicies() {
        return List.copyOf(descriptors.values());
    }
}
