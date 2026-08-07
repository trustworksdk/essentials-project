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

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Descriptor class that encapsulates information about the aggregate implementation type,
 * aggregate type, and the policy governing the closing of books for the aggregate.
 * <p>
 * This record ensures the required components are non-null and provides a basis for
 * configuring and applying aggregate-specific closing books policies.
 *
 * @param aggregateImplementationType the implementation type of the aggregate (must not be null)
 * @param aggregateType               an optional logical name or type of the aggregate (must not be null)
 * @param policy                      the closing books policy to be applied (must not be null)
 */
public record AggregateClosingBooksPolicyDescriptor(
        Class<?> aggregateImplementationType,
        Optional<String> aggregateType,
        AggregateClosingBooksPolicy policy
) {
    public AggregateClosingBooksPolicyDescriptor {
        requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(policy, "No policy provided");
    }
}
