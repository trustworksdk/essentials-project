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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.shared.collections.Lists;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A repository implementation that manages durable, asynchronous operations related to aggregate snapshots.
 * <p>
 * This class coordinates with an event store, snapshot store, job repository, and serialization strategies
 * to handle snapshot creation, storage, deletion, and job scheduling. It ensures that snapshots are created
 * and managed efficiently to optimize aggregate state recovery in an event-sourced system.
 * <p>
 * The implementation uses strategies for determining whether snapshots should be created or deleted
 * and manages these operations asynchronously through job enqueuing.
 *
 * @see AggregateSnapshotRepository
 * @see ConfigurableEventStore
 * @see AggregateSnapshotStore
 * @see AggregateSnapshotJobRepository
 */
@SuppressWarnings("unchecked")
public class DurableAsyncAggregateSnapshotRepository implements AggregateSnapshotRepository {
    private static final Logger log = LoggerFactory.getLogger(DurableAsyncAggregateSnapshotRepository.class);

    private final ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore;
    private final AggregateSnapshotStore                                              snapshotStore;
    private final AggregateSnapshotJobRepository                                      jobRepository;
    private final JSONEventSerializer                                                 jsonSerializer;
    private final AggregateSnapshotMeasurementSupport                                 measurementSupport;
    private final AddNewAggregateSnapshotStrategy                                     addNewSnapshotStrategy;
    private final AggregateSnapshotDeletionStrategy                                   snapshotDeletionStrategy;

    /**
     * Constructs a {@code DurableAsyncAggregateSnapshotRepository} with the specified dependencies.
     *
     * @param eventStore the event store used to fetch and persist events associated with aggregates
     * @param snapshotStore the snapshot store responsible for storing aggregate snapshots
     * @param jobRepository the repository managing snapshot jobs and scheduling
     * @param jsonSerializer the serializer used to serialize and deserialize events to JSON
     * @param addNewSnapshotStrategy the strategy that defines how new snapshots are added
     * @param snapshotDeletionStrategy the strategy used to delete old or unused snapshots
     */
    public DurableAsyncAggregateSnapshotRepository(ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                                   AggregateSnapshotStore snapshotStore,
                                                   AggregateSnapshotJobRepository jobRepository,
                                                   JSONEventSerializer jsonSerializer,
                                                   AddNewAggregateSnapshotStrategy addNewSnapshotStrategy,
                                                   AggregateSnapshotDeletionStrategy snapshotDeletionStrategy) {
        this(eventStore,
             snapshotStore,
             jobRepository,
             jsonSerializer,
             addNewSnapshotStrategy,
             snapshotDeletionStrategy,
             Optional.empty());
    }

    /**
     * Constructs a {@code DurableAsyncAggregateSnapshotRepository} with the specified dependencies.
     *
     * @param eventStore the event store used to fetch and persist events associated with aggregates
     * @param snapshotStore the snapshot store responsible for storing aggregate snapshots
     * @param jobRepository the repository managing snapshot jobs and scheduling
     * @param jsonSerializer the serializer used to serialize and deserialize events to JSON
     * @param addNewSnapshotStrategy the strategy that defines how new snapshots are added
     * @param snapshotDeletionStrategy the strategy used to delete old or unused snapshots
     * @param meterRegistryOptional an optional meter registry for metrics collection and monitoring
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public DurableAsyncAggregateSnapshotRepository(ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                                   AggregateSnapshotStore snapshotStore,
                                                   AggregateSnapshotJobRepository jobRepository,
                                                   JSONEventSerializer jsonSerializer,
                                                   AddNewAggregateSnapshotStrategy addNewSnapshotStrategy,
                                                   AggregateSnapshotDeletionStrategy snapshotDeletionStrategy,
                                                   Optional<MeterRegistry> meterRegistryOptional) {
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        this.snapshotStore = requireNonNull(snapshotStore, "No snapshotStore provided");
        this.jobRepository = requireNonNull(jobRepository, "No jobRepository provided");
        this.jsonSerializer = AggregateSnapshotJSONSerializer.create(requireNonNull(jsonSerializer, "No jsonSerializer provided"));
        this.measurementSupport = new AggregateSnapshotMeasurementSupport(requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided"));
        this.addNewSnapshotStrategy = requireNonNull(addNewSnapshotStrategy, "No addNewSnapshotStrategy provided");
        this.snapshotDeletionStrategy = requireNonNull(snapshotDeletionStrategy, "No snapshotDeletionStrategy provided");
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> Optional<AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>> loadSnapshot(AggregateType aggregateType, ID aggregateId, EventOrder withLastIncludedEventOrderLessThanOrEqualTo, Class<AGGREGATE_IMPL_TYPE> aggregateImplType) {
        return snapshotStore.loadSnapshot(aggregateType, aggregateId, withLastIncludedEventOrderLessThanOrEqualTo, aggregateImplType);
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> List<AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>> loadAllSnapshots(AggregateType aggregateType, ID aggregateId, Class<AGGREGATE_IMPL_TYPE> aggregateImplType, boolean includeSnapshotPayload) {
        return snapshotStore.loadAllSnapshots(aggregateType, aggregateId, aggregateImplType, includeSnapshotPayload);
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> void aggregateUpdated(AGGREGATE_IMPL_TYPE aggregate, AggregateEventStream<ID> persistedEvents) {
        requireNonNull(aggregate, "No aggregate instance supplied");
        requireNonNull(persistedEvents, "No persistedEvents stream supplied");

        var aggregateType = persistedEvents.aggregateType();
        var aggregateId = persistedEvents.aggregateId();
        var aggregateImplType = (Class<AGGREGATE_IMPL_TYPE>) aggregate.getClass();
        var latestSnapshotEventOrder = snapshotStore.findMostRecentLastIncludedEventOrder(aggregateType, aggregateId, aggregateImplType);
        if (!shouldSchedule(aggregate, persistedEvents, aggregateImplType.getName(), latestSnapshotEventOrder)) return;

        var config = eventStore.getAggregateEventStreamConfiguration(aggregateType);
        var serializedAggregateId = config.aggregateIdSerializer.serialize(aggregateId).toString();
        var lastAppliedEventOrder = Lists.last(persistedEvents.eventList()).get().eventOrder().longValue();
        var deleteAllExistingSnapshots = !snapshotDeletionStrategy.requiresExistingSnapshotDetailsToDetermineWhichAggregateSnapshotsToDelete();
        var eventOrdersToDelete = deleteAllExistingSnapshots ? List.<Long>of() : snapshotStore.loadAllSnapshots(aggregateType,
                                                                                                                 aggregateId,
                                                                                                                 aggregateImplType,
                                                                                                                 false)
                                                                                                     .stream()
                                                                                                     .map(snapshot -> (AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>) snapshot)
                                                                                                     .collect(Collectors.collectingAndThen(Collectors.toList(),
                                                                                                                                           snapshotDeletionStrategy::resolveSnapshotsToDelete))
                                                                                                     .map(snapshot -> snapshot.eventOrderOfLastIncludedEvent.longValue())
                                                                                                     .toList();

        var job = new AggregateSnapshotJob(UUID.randomUUID(),
                                           aggregateType.value().toString(),
                                           serializedAggregateId,
                                           aggregateImplType.getName(),
                                           lastAppliedEventOrder,
                                           measurementSupport.recordSerializeSnapshot(aggregateType,
                                                                                      aggregateImplType,
                                                                                      () -> jsonSerializer.serialize(aggregate)),
                                           deleteAllExistingSnapshots,
                                           eventOrdersToDelete,
                                           OffsetDateTime.now(),
                                           OffsetDateTime.now(),
                                           0,
                                           AggregateSnapshotJobStatus.PENDING,
                                           null);
        jobRepository.enqueue(job);
    }

    private <ID, AGGREGATE_IMPL_TYPE> boolean shouldSchedule(AGGREGATE_IMPL_TYPE aggregate,
                                                             AggregateEventStream<ID> persistedEvents,
                                                             String aggregateImplType,
                                                             Optional<EventOrder> latestSnapshotEventOrder) {
        var shouldSchedule = addNewSnapshotStrategy.shouldANewAggregateSnapshotBeAdded(aggregate, persistedEvents, latestSnapshotEventOrder);
        if (log.isDebugEnabled()) {
            log.debug("[{}:{}] {} strategy determined to {} a durable Aggregate Snapshot job for '{}' based on mostRecentlyStoredSnapshotLastIncludedEventOrder {} and persistedEvents->eventOrders: {}",
                      persistedEvents.aggregateType(),
                      persistedEvents.aggregateId(),
                      addNewSnapshotStrategy,
                      shouldSchedule ? "ADD" : "NOT add",
                      aggregateImplType,
                      latestSnapshotEventOrder,
                      persistedEvents.eventList().stream().map(persistedEvent -> persistedEvent.eventOrder().longValue()).collect(Collectors.toList()));
        }
        return shouldSchedule;
    }

    @Override
    public <AGGREGATE_IMPL_TYPE> void deleteAllSnapshots(Class<AGGREGATE_IMPL_TYPE> ofAggregateImplementationType) {
        snapshotStore.deleteAllSnapshots(ofAggregateImplementationType);
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> void deleteSnapshots(AggregateType aggregateType, ID aggregateId, Class<AGGREGATE_IMPL_TYPE> withAggregateImplementationType) {
        snapshotStore.deleteSnapshots(aggregateType, aggregateId, withAggregateImplementationType);
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> void deleteSnapshots(AggregateType aggregateType, ID aggregateId, Class<AGGREGATE_IMPL_TYPE> withAggregateImplementationType, List<EventOrder> snapshotEventOrdersToDelete) {
        snapshotStore.deleteSnapshots(aggregateType, aggregateId, withAggregateImplementationType, snapshotEventOrdersToDelete);
    }
}
