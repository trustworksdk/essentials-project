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

import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class DefaultAggregateSnapshotConfigurationResolver implements AggregateSnapshotConfigurationResolver {
    private final EssentialsEventStoreProperties      properties;
    private final AggregateSnapshotPolicyRegistry     policyRegistry;

    public DefaultAggregateSnapshotConfigurationResolver(EssentialsEventStoreProperties properties,
                                                         AggregateSnapshotPolicyRegistry policyRegistry) {
        this.properties = requireNonNull(properties, "No properties provided");
        this.policyRegistry = requireNonNull(policyRegistry, "No policyRegistry provided");
    }

    @Override
    public ResolvedAggregateSnapshotConfiguration resolve(AggregateType aggregateType,
                                                          Class<?> aggregateImplementationType) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");

        var snapshotProperties = properties.getSnapshots();
        var descriptor = policyRegistry.findByAggregateImplementationType(aggregateImplementationType);
        var aggregatePolicyOverride = resolveAggregateOverride(aggregateType, descriptor);

        // Resolution semantics for enabled:
        //   1. A per-aggregate property override always wins (escape hatch — lets an operator
        //      selectively enable specific aggregates even when the feature is globally off).
        //   2. Otherwise, the global kill switch wins: if `snapshots.enabled=false` the
        //      aggregate's annotation cannot force the feature on.
        //   3. Otherwise, fall back to the annotation value (default true).
        var enabled = aggregatePolicyOverride.flatMap(override -> Optional.ofNullable(override.getEnabled()))
                                             .orElseGet(() -> {
                                                 if (!snapshotProperties.isEnabled()) {
                                                     return false;
                                                 }
                                                 return descriptor.map(AggregateSnapshotPolicyDescriptor::policy)
                                                                  .map(AggregateSnapshotPolicy::enabled)
                                                                  .orElse(true);
                                             });

        var mode = aggregatePolicyOverride.flatMap(override -> Optional.ofNullable(override.getMode()))
                                          .orElseGet(() -> descriptor.map(AggregateSnapshotPolicyDescriptor::policy)
                                                                     .map(AggregateSnapshotPolicy::mode)
                                                                     .orElse(snapshotProperties.getDefaultMode()));

        var everyNEvents = aggregatePolicyOverride.flatMap(override -> Optional.ofNullable(override.getEveryNEvents()))
                                                  .orElseGet(() -> descriptor.map(AggregateSnapshotPolicyDescriptor::policy)
                                                                             .map(AggregateSnapshotPolicy::everyNEvents)
                                                                             .orElse(snapshotProperties.getDefaultEveryNEvents()));

        var deletionMode = aggregatePolicyOverride.flatMap(override -> Optional.ofNullable(override.getDeletionMode()))
                                                  .orElseGet(() -> descriptor.map(AggregateSnapshotPolicyDescriptor::policy)
                                                                             .map(AggregateSnapshotPolicy::deletionMode)
                                                                             .orElse(snapshotProperties.getDefaultDeletionMode()));

        var keepLastSnapshots = aggregatePolicyOverride.flatMap(override -> Optional.ofNullable(override.getKeepLastSnapshots()))
                                                       .orElseGet(() -> descriptor.map(AggregateSnapshotPolicyDescriptor::policy)
                                                                                  .map(AggregateSnapshotPolicy::keepLastSnapshots)
                                                                                  .orElse(snapshotProperties.getDefaultKeepLastSnapshots()));

        return new ResolvedAggregateSnapshotConfiguration(enabled,
                                                          mode,
                                                          everyNEvents,
                                                          deletionMode,
                                                          keepLastSnapshots);
    }

    private Optional<EssentialsEventStoreProperties.AggregateSnapshotPolicyProperties> resolveAggregateOverride(
            AggregateType aggregateType,
            Optional<AggregateSnapshotPolicyDescriptor> descriptor
                                                                                                               ) {
        var aggregates = properties.getSnapshots().getAggregates();
        var aggregateTypeKey = aggregateType.toString();
        if (aggregates.containsKey(aggregateTypeKey)) {
            return Optional.ofNullable(aggregates.get(aggregateTypeKey));
        }

        return descriptor.flatMap(AggregateSnapshotPolicyDescriptor::aggregateType)
                         .filter(aggregates::containsKey)
                         .map(aggregates::get);
    }
}
