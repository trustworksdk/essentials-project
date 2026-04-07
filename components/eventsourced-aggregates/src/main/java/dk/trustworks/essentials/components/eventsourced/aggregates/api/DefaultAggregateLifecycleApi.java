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

package dk.trustworks.essentials.components.eventsourced.aggregates.api;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateEventStream;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import dk.trustworks.essentials.types.LongRange;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.*;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.validateHasAnyEssentialsSecurityRoles;

public class DefaultAggregateLifecycleApi implements AggregateLifecycleApi {
    private final EssentialsSecurityProvider securityProvider;
    private final AggregateSnapshotPolicyRegistry     snapshotPolicyRegistry;
    private final AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry;
    private final Optional<AggregateClosingBooksGenerationAccessProvider> closingBooksGenerationAccessProvider;
    private final Optional<AggregateSnapshotStore>    snapshotStore;
    private final ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore;
    private final JSONEventSerializer jsonSerializer;

    public DefaultAggregateLifecycleApi(EssentialsSecurityProvider securityProvider,
                                        AggregateSnapshotPolicyRegistry snapshotPolicyRegistry,
                                        AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry,
                                        Optional<AggregateClosingBooksGenerationAccessProvider> closingBooksGenerationAccessProvider,
                                        Optional<AggregateSnapshotStore> snapshotStore,
                                        ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                        JSONEventSerializer jsonSerializer) {
        this.securityProvider = requireNonNull(securityProvider, "securityProvider must not be null");
        this.snapshotPolicyRegistry = requireNonNull(snapshotPolicyRegistry, "snapshotPolicyRegistry must not be null");
        this.closingBooksPolicyRegistry = requireNonNull(closingBooksPolicyRegistry, "closingBooksPolicyRegistry must not be null");
        this.closingBooksGenerationAccessProvider = requireNonNull(closingBooksGenerationAccessProvider, "closingBooksGenerationAccessProvider must not be null");
        this.snapshotStore = requireNonNull(snapshotStore, "snapshotStore must not be null");
        this.eventStore = requireNonNull(eventStore, "eventStore must not be null");
        this.jsonSerializer = requireNonNull(jsonSerializer, "jsonSerializer must not be null");
    }

    @Override
    public List<ApiAggregateSnapshotPolicy> findAllAggregateSnapshotPolicies(Object principal) {
        validateReadAccess(principal);
        return snapshotPolicyRegistry.getRegisteredPolicies()
                                     .stream()
                                     .map(ApiAggregateSnapshotPolicy::from)
                                     .sorted(Comparator.comparing((ApiAggregateSnapshotPolicy policy) -> policy.aggregateType() != null ? policy.aggregateType().toString() : "")
                                                       .thenComparing(ApiAggregateSnapshotPolicy::aggregateImplementationType))
                                     .toList();
    }

    @Override
    public List<ApiAggregateClosingBooksPolicy> findAllAggregateClosingBooksPolicies(Object principal) {
        validateReadAccess(principal);
        return closingBooksPolicyRegistry.getRegisteredPolicies()
                                         .stream()
                                         .map(ApiAggregateClosingBooksPolicy::from)
                                         .sorted(Comparator.comparing((ApiAggregateClosingBooksPolicy policy) -> policy.aggregateType() != null ? policy.aggregateType().toString() : "")
                                                           .thenComparing(ApiAggregateClosingBooksPolicy::aggregateImplementationType))
                                         .toList();
    }

    @Override
    public Optional<ApiClosingBooksGeneration> findCurrentClosingBooksGeneration(Object principal,
                                                                                 AggregateType aggregateType,
                                                                                 String logicalAggregateId) {
        validateReadAccess(principal);
        requireNonNull(aggregateType, "aggregateType must not be null");
        requireNonNull(logicalAggregateId, "logicalAggregateId must not be null");
        return resolveClosingBooksGenerationAccess(aggregateType)
                .flatMap(access -> access.resolveCurrentGeneration(logicalAggregateId))
                .map(ApiClosingBooksGeneration::from);
    }

    @Override
    public List<ApiClosingBooksGeneration> findClosingBooksGenerations(Object principal,
                                                                       AggregateType aggregateType,
                                                                       String logicalAggregateId) {
        validateReadAccess(principal);
        requireNonNull(aggregateType, "aggregateType must not be null");
        requireNonNull(logicalAggregateId, "logicalAggregateId must not be null");
        return resolveClosingBooksGenerationAccess(aggregateType)
                .map(access -> access.loadGenerations(logicalAggregateId).stream().map(ApiClosingBooksGeneration::from).toList())
                .orElseGet(List::of);
    }

    @Override
    public Optional<ApiClosingBooksGenerationEventStream> findClosingBooksGenerationEventStream(Object principal,
                                                                                                AggregateType aggregateType,
                                                                                                String logicalAggregateId,
                                                                                                long generation) {
        validateReadAccess(principal);
        requireNonNull(aggregateType, "aggregateType must not be null");
        requireNonNull(logicalAggregateId, "logicalAggregateId must not be null");

        return resolveClosingBooksGenerationAccess(aggregateType)
                .flatMap(access -> access.loadGenerations(logicalAggregateId)
                                         .stream()
                                         .filter(candidate -> candidate.generation() == generation)
                                         .findFirst())
                .flatMap(resolvedGeneration -> fetchGenerationEventStream(aggregateType, logicalAggregateId, resolvedGeneration));
    }

    @Override
    public List<ApiAggregateSnapshot> findSnapshots(Object principal,
                                                    AggregateType aggregateType,
                                                    String aggregateId,
                                                    boolean includeSnapshotPayload) {
        validateReadAccess(principal);
        requireNonNull(aggregateType, "aggregateType must not be null");
        requireNonNull(aggregateId, "aggregateId must not be null");

        if (snapshotStore.isEmpty()) {
            return List.of();
        }

        var descriptor = resolveSnapshotPolicyDescriptor(aggregateType);
        var aggregateConfiguration = eventStore.getAggregateEventStreamConfiguration(aggregateType);
        var deserializedAggregateId = aggregateConfiguration.aggregateIdSerializer.deserialize(aggregateId);

        return snapshotStore.get()
                            .loadAllSnapshots(aggregateType,
                                              deserializedAggregateId,
                                              descriptor.aggregateImplementationType(),
                                              includeSnapshotPayload)
                            .stream()
                            .map(snapshot -> ApiAggregateSnapshot.from(snapshot, serializePayload(snapshot, includeSnapshotPayload)))
                            .toList();
    }

    private AggregateSnapshotPolicyDescriptor resolveSnapshotPolicyDescriptor(AggregateType aggregateType) {
        return snapshotPolicyRegistry.getRegisteredPolicies()
                                     .stream()
                                     .filter(descriptor -> descriptor.aggregateType().map(aggregateType.toString()::equals).orElse(false))
                                     .reduce((left, right) -> {
                                         throw new IllegalStateException("Multiple snapshot policy descriptors are registered for aggregateType '" + aggregateType + "'");
                                     })
                                     .orElseThrow(() -> new IllegalArgumentException("No snapshot policy descriptor is registered for aggregateType '" + aggregateType + "'"));
    }

    private Optional<AggregateClosingBooksGenerationAccess> resolveClosingBooksGenerationAccess(AggregateType aggregateType) {
        return closingBooksGenerationAccessProvider.flatMap(provider -> resolveClosingBooksPolicyDescriptor(aggregateType)
                .flatMap(descriptor -> provider.resolve(aggregateType, descriptor.aggregateImplementationType()))
                .or(() -> provider.resolve(aggregateType)));
    }

    private Optional<ApiClosingBooksGenerationEventStream> fetchGenerationEventStream(AggregateType aggregateType,
                                                                                      String logicalAggregateId,
                                                                                      AggregateGeneration<String> generation) {
        var aggregateConfiguration = eventStore.getAggregateEventStreamConfiguration(aggregateType);
        var deserializedStreamAggregateId = aggregateConfiguration.aggregateIdSerializer.deserialize(generation.streamAggregateId());
        return eventStore.fetchStream(aggregateType, deserializedStreamAggregateId, LongRange.from(0L))
                         .map(eventStream -> toApiClosingBooksGenerationEventStream(logicalAggregateId, generation, eventStream));
    }

    private ApiClosingBooksGenerationEventStream toApiClosingBooksGenerationEventStream(String logicalAggregateId,
                                                                                        AggregateGeneration<String> generation,
                                                                                        AggregateEventStream<?> eventStream) {
        var firstIncludedEventOrder = eventStream.eventList().isEmpty() ? null : eventStream.firstEvent().eventOrder().longValue();
        var lastIncludedEventOrder = eventStream.eventList().isEmpty() ? null : eventStream.lastEvent().eventOrder().longValue();
        return new ApiClosingBooksGenerationEventStream(generation.aggregateType().toString(),
                                                        logicalAggregateId,
                                                        generation.generation(),
                                                        generation.streamAggregateId(),
                                                        generation.state().name(),
                                                        generation.openedAt(),
                                                        generation.closedAt().orElse(null),
                                                        eventStream.isPartialEventStream(),
                                                        firstIncludedEventOrder,
                                                        lastIncludedEventOrder,
                                                        eventStream.eventList().stream().map(ApiPersistedEvent::from).toList());
    }

    private Optional<AggregateClosingBooksPolicyDescriptor> resolveClosingBooksPolicyDescriptor(AggregateType aggregateType) {
        return closingBooksPolicyRegistry.getRegisteredPolicies()
                                         .stream()
                                         .filter(descriptor -> descriptor.aggregateType().map(aggregateType.toString()::equals).orElse(false))
                                         .reduce((left, right) -> {
                                             throw new IllegalStateException("Multiple closing-books policy descriptors are registered for aggregateType '" + aggregateType + "'");
                                         });
    }

    private String serializePayload(AggregateSnapshot<?, ?> snapshot, boolean includeSnapshotPayload) {
        if (!includeSnapshotPayload || snapshot.aggregateSnapshot == null) {
            return null;
        }
        return jsonSerializer.serializePrettyPrint(snapshot.aggregateSnapshot);
    }

    private void validateReadAccess(Object principal) {
        validateHasAnyEssentialsSecurityRoles(securityProvider, principal, SUBSCRIPTION_READER, ESSENTIALS_ADMIN);
    }
}
