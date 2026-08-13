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

/**
 * An application's declaration of which aggregate implementation classes serve which {@link AggregateType}s.
 * <p>
 * Declaring aggregates is what makes {@code @AggregateSnapshotPolicy} and {@code @AggregateClosingBooksPolicy} on an
 * aggregate root take effect: an aggregate root is not - and should not be - a Spring bean, so the policy
 * {@code BeanPostProcessor}s never observe it. Without a declaration the annotations reach no registry and the admin
 * API's lifecycle endpoints report nothing, silently.
 * <p>
 * Define one bean per configuration class; every declared bean is picked up and merged:
 * <pre>{@code
 * @Bean
 * EssentialsAggregateDeclarations tradingAggregates() {
 *     return EssentialsAggregateDeclarations.builder()
 *                                           .declare(TRADING_ACCOUNTS,  TradingAccount.class)
 *                                           .declare(INSTRUMENT_PRICES, InstrumentPrice.class)
 *                                           .declare(SETTLEMENTS,       Settlement.class)
 *                                           .build();
 * }
 * }</pre>
 * This type carries no Spring dependency, so a non-Spring application can hand the same declarations to whatever
 * wiring it uses.
 *
 * @see AggregateDeclaration
 */
public final class EssentialsAggregateDeclarations {
    private final List<AggregateDeclaration> declarations;

    /**
     * Use {@link #builder()}, or {@link #of(AggregateDeclaration...)} for a single-expression declaration.
     *
     * @param declarations the declarations; must not be null
     */
    public EssentialsAggregateDeclarations(List<AggregateDeclaration> declarations) {
        this.declarations = List.copyOf(requireNonNull(declarations, "No declarations provided"));
    }

    /**
     * @return a new builder
     */
    public static EssentialsAggregateDeclarationsBuilder builder() {
        return new EssentialsAggregateDeclarationsBuilder();
    }

    /**
     * Shorthand for declarations that are already built.
     *
     * @param declarations the declarations; must not be null
     * @return an {@link EssentialsAggregateDeclarations} over the given declarations
     */
    public static EssentialsAggregateDeclarations of(AggregateDeclaration... declarations) {
        return new EssentialsAggregateDeclarations(List.of(requireNonNull(declarations, "No declarations provided")));
    }

    /**
     * @return the declared aggregates, in declaration order; never null, may be empty
     */
    public List<AggregateDeclaration> declarations() {
        return declarations;
    }

    /**
     * Looks up the {@link AggregateType} declared for the given aggregate implementation class.
     *
     * @param aggregateImplementationType the aggregate implementation class; must not be null
     * @return the declared {@link AggregateType}, or an empty {@link Optional} if the class was not declared here
     */
    public Optional<AggregateType> findAggregateType(Class<?> aggregateImplementationType) {
        requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");
        return declarations.stream()
                           .filter(declaration -> declaration.aggregateImplementationType().equals(aggregateImplementationType))
                           .map(AggregateDeclaration::aggregateType)
                           .findFirst();
    }

    @Override
    public String toString() {
        return "EssentialsAggregateDeclarations{" + declarations + "}";
    }
}
