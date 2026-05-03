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

/**
 * Registry for managing the registration and retrieval of {@link AggregateClosingBooksPolicyDescriptor}.
 * This interface provides methods to register policy descriptors, retrieve a descriptor by the
 * aggregate implementation type, and retrieve all registered policy descriptors.
 * <p>
 * The registry serves as a central point for managing and applying closing books policies
 * to specific aggregate types, ensuring that policies are associated with the correct
 * aggregate implementation classes and can be retrieved as needed.
 */
public interface AggregateClosingBooksPolicyRegistry {

    /**
     * Registers the specified {@link AggregateClosingBooksPolicyDescriptor} in the registry.
     * The descriptor contains details about a specific aggregate implementation type, aggregate type,
     * and the policy to be applied for closing books for that aggregate.
     *
     * @param descriptor the policy descriptor to be registered; must not be null
     */
    void register(AggregateClosingBooksPolicyDescriptor descriptor);

    /**
     * Retrieves an {@link AggregateClosingBooksPolicyDescriptor} for the given aggregate implementation type, if one is registered.
     *
     * @param aggregateImplementationType the class of the aggregate implementation type for which to retrieve the policy descriptor; must not be null
     * @return an {@link Optional} containing the policy descriptor if found, or an empty {@link Optional} if no descriptor is registered for the given type
     */
    Optional<AggregateClosingBooksPolicyDescriptor> findByAggregateImplementationType(Class<?> aggregateImplementationType);

    /**
     * Retrieves all registered {@link AggregateClosingBooksPolicyDescriptor} instances from the registry.
     * This method returns a collection of descriptors that represent the policies associated
     * with various aggregate implementation types, enabling policy management and application
     * for closing books scenarios.
     *
     * @return a collection of all registered {@link AggregateClosingBooksPolicyDescriptor} instances; never null but may be empty
     */
    Collection<AggregateClosingBooksPolicyDescriptor> getRegisteredPolicies();
}
