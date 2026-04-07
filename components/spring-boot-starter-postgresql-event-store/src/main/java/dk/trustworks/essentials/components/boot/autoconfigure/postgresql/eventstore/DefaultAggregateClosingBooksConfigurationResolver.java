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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class DefaultAggregateClosingBooksConfigurationResolver implements AggregateClosingBooksConfigurationResolver {
    private final EssentialsEventStoreProperties       properties;
    private final AggregateClosingBooksPolicyRegistry  policyRegistry;

    public DefaultAggregateClosingBooksConfigurationResolver(EssentialsEventStoreProperties properties,
                                                             AggregateClosingBooksPolicyRegistry policyRegistry) {
        this.properties = requireNonNull(properties, "No properties provided");
        this.policyRegistry = requireNonNull(policyRegistry, "No policyRegistry provided");
    }

    @Override
    public ResolvedAggregateClosingBooksConfiguration resolve(AggregateType aggregateType,
                                                              Class<?> aggregateImplementationType) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");

        var closingBooksProperties = properties.getClosingBooks();
        var descriptor = policyRegistry.findByAggregateImplementationType(aggregateImplementationType);
        var aggregatePolicyOverride = resolveAggregateOverride(aggregateType, descriptor);

        var enabled = aggregatePolicyOverride.flatMap(override -> Optional.ofNullable(override.getEnabled()))
                                             .orElseGet(() -> descriptor.map(AggregateClosingBooksPolicyDescriptor::policy)
                                                                        .map(AggregateClosingBooksPolicy::enabled)
                                                                        .orElse(closingBooksProperties.isEnabled()));

        var triggerMode = aggregatePolicyOverride.flatMap(override -> Optional.ofNullable(override.getTriggerMode()))
                                                 .orElseGet(() -> descriptor.map(AggregateClosingBooksPolicyDescriptor::policy)
                                                                            .map(AggregateClosingBooksPolicy::triggerMode)
                                                                            .orElse(closingBooksProperties.getDefaultTriggerMode()));

        var defaultPolicy = aggregatePolicyOverride.flatMap(override -> Optional.ofNullable(override.getDefaultPolicy()))
                                                   .orElseGet(() -> descriptor.map(AggregateClosingBooksPolicyDescriptor::policy)
                                                                              .map(AggregateClosingBooksPolicy::defaultPolicy)
                                                                              .filter(policyType -> policyType != ClosingBooksDefaultPolicyType.UNSPECIFIED)
                                                                              .orElse(closingBooksProperties.getDefaultPolicy()));

        var eventThreshold = aggregatePolicyOverride.flatMap(override -> Optional.ofNullable(override.getEventThreshold()))
                                                    .orElseGet(() -> descriptor.map(AggregateClosingBooksPolicyDescriptor::policy)
                                                                               .map(AggregateClosingBooksPolicy::eventThreshold)
                                                                               .filter(value -> value > 0)
                                                                               .orElse(closingBooksProperties.getEventThreshold()));

        var timeBoundary = aggregatePolicyOverride.flatMap(override -> Optional.ofNullable(override.getTimeBoundary()))
                                                  .orElseGet(() -> descriptor.map(AggregateClosingBooksPolicyDescriptor::policy)
                                                                             .map(AggregateClosingBooksPolicy::timeBoundary)
                                                                             .filter(boundary -> boundary != ClosingBooksTimeBoundary.NONE)
                                                                             .orElse(closingBooksProperties.getTimeBoundary()));

        var zoneId = aggregatePolicyOverride.flatMap(override -> Optional.ofNullable(override.getZoneId()))
                                            .orElseGet(() -> descriptor.map(AggregateClosingBooksPolicyDescriptor::policy)
                                                                       .map(AggregateClosingBooksPolicy::zoneId)
                                                                       .filter(value -> !value.isBlank())
                                                                       .orElse(closingBooksProperties.getZoneId()));

        var intervalDays = aggregatePolicyOverride.flatMap(override -> Optional.ofNullable(override.getIntervalDays()))
                                                  .orElseGet(() -> descriptor.map(AggregateClosingBooksPolicyDescriptor::policy)
                                                                             .map(AggregateClosingBooksPolicy::intervalDays)
                                                                             .filter(value -> value > 0)
                                                                             .orElse(closingBooksProperties.getIntervalDays()));

        return new ResolvedAggregateClosingBooksConfiguration(enabled,
                                                              triggerMode,
                                                              defaultPolicy,
                                                              eventThreshold,
                                                              timeBoundary,
                                                              zoneId,
                                                              intervalDays);
    }

    private Optional<EssentialsEventStoreProperties.AggregateClosingBooksPolicyProperties> resolveAggregateOverride(
            AggregateType aggregateType,
            Optional<AggregateClosingBooksPolicyDescriptor> descriptor
                                                                                                                   ) {
        var aggregates = properties.getClosingBooks().getAggregates();
        var aggregateTypeKey = aggregateType.toString();
        if (aggregates.containsKey(aggregateTypeKey)) {
            return Optional.ofNullable(aggregates.get(aggregateTypeKey));
        }

        return descriptor.flatMap(AggregateClosingBooksPolicyDescriptor::aggregateType)
                         .filter(aggregates::containsKey)
                         .map(aggregates::get);
    }
}
