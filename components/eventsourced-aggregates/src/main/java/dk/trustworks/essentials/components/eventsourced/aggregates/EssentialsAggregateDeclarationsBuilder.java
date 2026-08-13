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

package dk.trustworks.essentials.components.eventsourced.aggregates;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Builder for {@link EssentialsAggregateDeclarations}. Obtain one via {@link EssentialsAggregateDeclarations#builder()}.
 * <p>
 * {@code declare(...)} is repeatable and additive rather than a property setter, which is why it does not follow the
 * {@code setXxx(...)} convention used elsewhere.
 */
public final class EssentialsAggregateDeclarationsBuilder {
    private final List<AggregateDeclaration> declarations = new ArrayList<>();

    /**
     * Declares that the given aggregate implementation class serves the given {@link AggregateType}.
     *
     * @param aggregateType               the aggregate type; must not be null
     * @param aggregateImplementationType the aggregate implementation class; must not be null
     * @return this builder
     * @throws IllegalArgumentException if the same implementation class has already been declared for a different
     *                                  aggregate type - the policy registries are keyed by implementation class, so
     *                                  the second declaration would silently displace the first
     */
    public EssentialsAggregateDeclarationsBuilder declare(AggregateType aggregateType,
                                                         Class<?> aggregateImplementationType) {
        return declare(new AggregateDeclaration(aggregateType, aggregateImplementationType));
    }

    /**
     * Adds an already-created {@link AggregateDeclaration}.
     *
     * @param declaration the declaration; must not be null
     * @return this builder
     * @throws IllegalArgumentException if the same implementation class has already been declared for a different
     *                                  aggregate type
     */
    public EssentialsAggregateDeclarationsBuilder declare(AggregateDeclaration declaration) {
        requireNonNull(declaration, "No declaration provided");
        var existing = declarations.stream()
                                   .filter(candidate -> candidate.aggregateImplementationType().equals(declaration.aggregateImplementationType()))
                                   .findFirst();
        if (existing.isPresent() && !existing.get().aggregateType().equals(declaration.aggregateType())) {
            throw new IllegalArgumentException(msg("Aggregate implementation type '{}' is already declared for aggregateType '{}' and cannot also be declared for '{}'",
                                                   declaration.aggregateImplementationType().getName(),
                                                   existing.get().aggregateType(),
                                                   declaration.aggregateType()));
        }
        if (existing.isEmpty()) {
            declarations.add(declaration);
        }
        return this;
    }

    /**
     * @return the built {@link EssentialsAggregateDeclarations}
     */
    public EssentialsAggregateDeclarations build() {
        return new EssentialsAggregateDeclarations(declarations);
    }
}
