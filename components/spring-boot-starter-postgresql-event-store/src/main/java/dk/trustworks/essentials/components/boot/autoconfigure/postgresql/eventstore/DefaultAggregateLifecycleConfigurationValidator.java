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

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class DefaultAggregateLifecycleConfigurationValidator implements AggregateLifecycleConfigurationValidator, SmartInitializingSingleton {
    private static final Logger log = LoggerFactory.getLogger(DefaultAggregateLifecycleConfigurationValidator.class);

    private final AggregateSnapshotPolicyRegistry            snapshotPolicyRegistry;
    private final AggregateClosingBooksPolicyRegistry        closingBooksPolicyRegistry;
    private final AggregateSnapshotConfigurationResolver     snapshotConfigurationResolver;
    private final AggregateClosingBooksConfigurationResolver closingBooksConfigurationResolver;
    private final Optional<FencedLockManager>                fencedLockManagerOptional;
    private final Set<Class<?>>                              nextGenerationFactoryAggregateTypes;

    public DefaultAggregateLifecycleConfigurationValidator(AggregateSnapshotPolicyRegistry snapshotPolicyRegistry,
                                                           AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry,
                                                           AggregateSnapshotConfigurationResolver snapshotConfigurationResolver,
                                                           AggregateClosingBooksConfigurationResolver closingBooksConfigurationResolver,
                                                           Optional<FencedLockManager> fencedLockManagerOptional,
                                                           List<TypedClosingBooksNextGenerationFactory<?, ?, ?, ?>> nextGenerationFactories) {
        this.snapshotPolicyRegistry = requireNonNull(snapshotPolicyRegistry, "No snapshotPolicyRegistry provided");
        this.closingBooksPolicyRegistry = requireNonNull(closingBooksPolicyRegistry, "No closingBooksPolicyRegistry provided");
        this.snapshotConfigurationResolver = requireNonNull(snapshotConfigurationResolver, "No snapshotConfigurationResolver provided");
        this.closingBooksConfigurationResolver = requireNonNull(closingBooksConfigurationResolver, "No closingBooksConfigurationResolver provided");
        this.fencedLockManagerOptional = requireNonNull(fencedLockManagerOptional, "No fencedLockManagerOptional provided");
        this.nextGenerationFactoryAggregateTypes = requireNonNull(nextGenerationFactories, "No nextGenerationFactories provided").stream()
                                                                                                                              .map(TypedClosingBooksNextGenerationFactory::aggregateImplementationType)
                                                                                                                              .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    @Override
    public void validate() {
        var aggregateImplementationTypes = new LinkedHashSet<Class<?>>();
        snapshotPolicyRegistry.getRegisteredPolicies().forEach(descriptor -> aggregateImplementationTypes.add(descriptor.aggregateImplementationType()));
        closingBooksPolicyRegistry.getRegisteredPolicies().forEach(descriptor -> aggregateImplementationTypes.add(descriptor.aggregateImplementationType()));

        for (var aggregateImplementationType : aggregateImplementationTypes) {
            var aggregateType = resolveAggregateType(aggregateImplementationType);
            var resolvedSnapshotConfiguration = snapshotConfigurationResolver.resolve(aggregateType, aggregateImplementationType);
            var resolvedClosingBooksConfiguration = closingBooksConfigurationResolver.resolve(aggregateType, aggregateImplementationType);

            if (resolvedSnapshotConfiguration.enabled() && resolvedClosingBooksConfiguration.enabled()) {
                log.warn("Aggregate '{}' enables both snapshotting and closing books for aggregateType '{}'. This is allowed, but validate that the snapshot cadence still makes sense for the closing-books policy.",
                         aggregateImplementationType.getName(),
                         aggregateType);
            }

            if (resolvedClosingBooksConfiguration.enabled() &&
                resolvedClosingBooksConfiguration.triggerMode() == ClosingBooksTriggerMode.SCHEDULED_SCAN &&
                fencedLockManagerOptional.isEmpty()) {
                throw new IllegalStateException("Aggregate '" + aggregateImplementationType.getName() + "' uses scheduled closing books for aggregateType '" + aggregateType + "' but no FencedLockManager is configured");
            }

            if (resolvedClosingBooksConfiguration.enabled() &&
                requiresNextGenerationFactory(resolvedClosingBooksConfiguration.defaultPolicy()) &&
                !nextGenerationFactoryAggregateTypes.contains(aggregateImplementationType)) {
                throw new IllegalStateException("Aggregate '" + aggregateImplementationType.getName() + "' uses automatic close-and-open-next-generation policy '" +
                                                       resolvedClosingBooksConfiguration.defaultPolicy() + "' for aggregateType '" + aggregateType +
                                                       "' but no TypedClosingBooksNextGenerationFactory is registered");
            }
        }
    }

    private boolean requiresNextGenerationFactory(ClosingBooksDefaultPolicyType defaultPolicy) {
        return switch (defaultPolicy) {
            case EVENT_COUNT, TIME_BOUNDARY, EVENT_COUNT_OR_TIME_BOUNDARY -> true;
            case MANUAL_ONLY, EXPLICIT_ONLY, UNSPECIFIED -> false;
        };
    }

    private AggregateType resolveAggregateType(Class<?> aggregateImplementationType) {
        var snapshotAggregateType = snapshotPolicyRegistry.findByAggregateImplementationType(aggregateImplementationType)
                                                          .flatMap(AggregateSnapshotPolicyDescriptor::aggregateType);
        if (snapshotAggregateType.isPresent()) {
            return AggregateType.of(snapshotAggregateType.get());
        }

        return closingBooksPolicyRegistry.findByAggregateImplementationType(aggregateImplementationType)
                                         .flatMap(AggregateClosingBooksPolicyDescriptor::aggregateType)
                                         .map(AggregateType::of)
                                         .orElseGet(() -> AggregateType.of(aggregateImplementationType.getSimpleName()));
    }
}
