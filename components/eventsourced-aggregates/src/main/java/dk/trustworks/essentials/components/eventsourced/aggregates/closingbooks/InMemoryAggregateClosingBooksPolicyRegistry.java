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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An in-memory implementation of the {@link AggregateClosingBooksPolicyRegistry}.
 * This class provides a thread-safe mechanism for managing the registration and retrieval
 * of aggregate closing books policy descriptors using an in-memory data structure.
 * <p>
 * Responsibilities of this class include:
 * - Storing and organizing {@link AggregateClosingBooksPolicyDescriptor} instances by
 *   associating them with specific aggregate implementation types.
 * - Enabling lookup of a registered policy descriptor by the aggregate implementation type.
 * - Allowing retrieval of all registered policy descriptors.
 * <p>
 * The class uses a {@link ConcurrentHashMap} internally for thread-safe access and
 * efficient retrieval.
 */
public class InMemoryAggregateClosingBooksPolicyRegistry implements AggregateClosingBooksPolicyRegistry {
    private final Map<Class<?>, AggregateClosingBooksPolicyDescriptor> descriptors = new ConcurrentHashMap<>();

    @Override
    public void register(AggregateClosingBooksPolicyDescriptor descriptor) {
        requireNonNull(descriptor, "No descriptor provided");
        descriptors.put(descriptor.aggregateImplementationType(), descriptor);
    }

    @Override
    public Optional<AggregateClosingBooksPolicyDescriptor> findByAggregateImplementationType(Class<?> aggregateImplementationType) {
        requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");
        return Optional.ofNullable(descriptors.get(aggregateImplementationType));
    }

    @Override
    public Collection<AggregateClosingBooksPolicyDescriptor> getRegisteredPolicies() {
        return List.copyOf(descriptors.values());
    }
}
