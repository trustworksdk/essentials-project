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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.shared.collections.Lists;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Opt-in async-capable {@link AggregateSnapshotRepository} built on top of an {@link AggregateSnapshotStore}.
 * <p>
 * Snapshot scheduling semantics are currently in-memory only for {@link SnapshotExecutionMode#ASYNC_IN_MEMORY}.
 */
@SuppressWarnings("unchecked")
public class AsyncAggregateSnapshotRepository implements AggregateSnapshotRepository {
    private static final Logger log = LoggerFactory.getLogger(AsyncAggregateSnapshotRepository.class);

    private final AggregateSnapshotStore             snapshotStore;
    private final JSONEventSerializer                jsonSerializer;
    private final AddNewAggregateSnapshotStrategy    addNewSnapshotStrategy;
    private final AggregateSnapshotDeletionStrategy  snapshotDeletionStrategy;
    private final AsyncAggregateSnapshotSettings     settings;
    private final Executor                           executor;
    private final AggregateSnapshotMeasurementSupport measurementSupport;
    private final Optional<UnitOfWorkFactory<? extends UnitOfWork>> unitOfWorkFactory;

    /**
     * Constructs an instance of AsyncAggregateSnapshotRepository.
     *
     * @param snapshotStore the storage mechanism for aggregate snapshots.
     * @param jsonSerializer the serializer used to convert events to and from JSON.
     * @param addNewSnapshotStrategy the strategy used for adding new aggregate snapshots.
     * @param snapshotDeletionStrategy the strategy used for deleting stale aggregate snapshots.
     * @param settings the configuration settings for the asynchronous snapshot repository.
     */
    public AsyncAggregateSnapshotRepository(AggregateSnapshotStore snapshotStore,
                                            JSONEventSerializer jsonSerializer,
                                            AddNewAggregateSnapshotStrategy addNewSnapshotStrategy,
                                            AggregateSnapshotDeletionStrategy snapshotDeletionStrategy,
                                            AsyncAggregateSnapshotSettings settings) {
        this(snapshotStore,
             jsonSerializer,
             addNewSnapshotStrategy,
             snapshotDeletionStrategy,
             settings,
             defaultExecutor(settings),
             Optional.empty(),
             Optional.empty());
    }

    /**
     * Constructs an instance of AsyncAggregateSnapshotRepository.
     *
     * @param snapshotStore The store responsible for persisting aggregate snapshots.
     * @param jsonSerializer The JSON serializer used for serializing and deserializing events.
     * @param addNewSnapshotStrategy The strategy used for adding new aggregate snapshots.
     * @param snapshotDeletionStrategy The strategy used for deleting aggregate snapshots.
     * @param settings The configuration settings for asynchronous aggregate snapshot operations.
     * @param executor The executor used for performing asynchronous tasks.
     */
    public AsyncAggregateSnapshotRepository(AggregateSnapshotStore snapshotStore,
                                            JSONEventSerializer jsonSerializer,
                                            AddNewAggregateSnapshotStrategy addNewSnapshotStrategy,
                                            AggregateSnapshotDeletionStrategy snapshotDeletionStrategy,
                                            AsyncAggregateSnapshotSettings settings,
                                            Executor executor) {
        this(snapshotStore,
             jsonSerializer,
             addNewSnapshotStrategy,
             snapshotDeletionStrategy,
             settings,
             executor,
             Optional.empty(),
             Optional.empty());
    }

    /**
     * Constructs an instance of AsyncAggregateSnapshotRepository with the provided dependencies
     * and configurations to handle aggregate snapshot operations asynchronously.
     *
     * @param snapshotStore The storage mechanism for aggregate snapshots.
     * @param jsonSerializer The serializer used for converting events to and from JSON format.
     * @param addNewSnapshotStrategy The strategy to add new aggregate snapshots.
     * @param snapshotDeletionStrategy The strategy to handle the deletion of aggregate snapshots.
     * @param settings Configuration settings for the asynchronous snapshot handling.
     * @param unitOfWorkFactory Factory for creating instances of UnitOfWork for transactional operations.
     */
    public AsyncAggregateSnapshotRepository(AggregateSnapshotStore snapshotStore,
                                            JSONEventSerializer jsonSerializer,
                                            AddNewAggregateSnapshotStrategy addNewSnapshotStrategy,
                                            AggregateSnapshotDeletionStrategy snapshotDeletionStrategy,
                                            AsyncAggregateSnapshotSettings settings,
                                            UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory) {
        this(snapshotStore,
             jsonSerializer,
             addNewSnapshotStrategy,
             snapshotDeletionStrategy,
             settings,
             defaultExecutor(settings),
             Optional.of(unitOfWorkFactory),
             Optional.empty());
    }

    /**
     * Constructs a new AsyncAggregateSnapshotRepository with the provided dependencies.
     *
     * @param snapshotStore The store used to persist and retrieve aggregate snapshots.
     * @param jsonSerializer The serializer for converting events to and from JSON.
     * @param addNewSnapshotStrategy Strategy for determining how new snapshots are added.
     * @param snapshotDeletionStrategy Strategy for determining how snapshots are deleted.
     * @param settings Configuration settings for the repository.
     * @param executor The executor used for asynchronous operations.
     * @param unitOfWorkFactory The factory for creating units of work.
     */
    public AsyncAggregateSnapshotRepository(AggregateSnapshotStore snapshotStore,
                                            JSONEventSerializer jsonSerializer,
                                            AddNewAggregateSnapshotStrategy addNewSnapshotStrategy,
                                            AggregateSnapshotDeletionStrategy snapshotDeletionStrategy,
                                            AsyncAggregateSnapshotSettings settings,
                                            Executor executor,
                                            UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory) {
        this(snapshotStore,
             jsonSerializer,
             addNewSnapshotStrategy,
             snapshotDeletionStrategy,
             settings,
             executor,
             Optional.of(unitOfWorkFactory),
             Optional.empty());
    }

    /**
     * Constructs an instance of AsyncAggregateSnapshotRepository.
     *
     * @param snapshotStore the store responsible for managing aggregate snapshots
     * @param jsonSerializer the serializer used to serialize and deserialize JSON events
     * @param addNewSnapshotStrategy the strategy for adding new aggregate snapshots
     * @param snapshotDeletionStrategy the strategy for deleting aggregate snapshots
     * @param settings the configuration settings for the async aggregate snapshot repository
     * @param executor the executor used for asynchronous operations
     * @param meterRegistryOptional an optional meter registry for tracking metrics
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public AsyncAggregateSnapshotRepository(AggregateSnapshotStore snapshotStore,
                                            JSONEventSerializer jsonSerializer,
                                            AddNewAggregateSnapshotStrategy addNewSnapshotStrategy,
                                            AggregateSnapshotDeletionStrategy snapshotDeletionStrategy,
                                            AsyncAggregateSnapshotSettings settings,
                                            Executor executor,
                                            Optional<MeterRegistry> meterRegistryOptional) {
        this(snapshotStore,
             jsonSerializer,
             addNewSnapshotStrategy,
             snapshotDeletionStrategy,
             settings,
             executor,
             Optional.empty(),
             meterRegistryOptional);
    }

    /**
     * Constructs an instance of {@code AsyncAggregateSnapshotRepository}, a class responsible for
     * managing aggregate snapshots asynchronously.
     *
     * @param snapshotStore The store responsible for storing and retrieving aggregate snapshots.
     * @param jsonSerializer Serializer to convert events to and from JSON format.
     * @param addNewSnapshotStrategy Strategy for determining how new aggregate snapshots are added.
     * @param snapshotDeletionStrategy Strategy for managing the deletion of aggregate snapshots.
     * @param settings Configuration settings for asynchronous aggregate snapshot operations.
     * @param executor Executor used for asynchronous task execution.
     * @param unitOfWorkFactory Factory used to create instances of {@link UnitOfWork}.
     * @param meterRegistryOptional Optional MeterRegistry for metric tracking and monitoring.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public AsyncAggregateSnapshotRepository(AggregateSnapshotStore snapshotStore,
                                            JSONEventSerializer jsonSerializer,
                                            AddNewAggregateSnapshotStrategy addNewSnapshotStrategy,
                                            AggregateSnapshotDeletionStrategy snapshotDeletionStrategy,
                                            AsyncAggregateSnapshotSettings settings,
                                            Executor executor,
                                            UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory,
                                            Optional<MeterRegistry> meterRegistryOptional) {
        this(snapshotStore,
             jsonSerializer,
             addNewSnapshotStrategy,
             snapshotDeletionStrategy,
             settings,
             executor,
             Optional.of(unitOfWorkFactory),
             meterRegistryOptional);
    }

    /**
     * Constructs an instance of {@code AsyncAggregateSnapshotRepository}.
     *
     * @param snapshotStore The store to manage aggregate snapshots. Must not be null.
     * @param jsonSerializer The JSON event serializer used for serializing/deserializing snapshots. Must not be null.
     * @param addNewSnapshotStrategy Strategy for adding new aggregate snapshots. Must not be null.
     * @param snapshotDeletionStrategy Strategy for deleting aggregate snapshots. Must not be null.
     * @param settings Configuration settings for the repository. Must not be null.
     * @param executor The executor used to perform asynchronous operations. Must not be null.
     * @param unitOfWorkFactory An optional factory for creating units of work. Must not be null.
     * @param meterRegistryOptional An optional meter registry for capturing metrics. Must not be null.
     */
    private AsyncAggregateSnapshotRepository(AggregateSnapshotStore snapshotStore,
                                             JSONEventSerializer jsonSerializer,
                                             AddNewAggregateSnapshotStrategy addNewSnapshotStrategy,
                                             AggregateSnapshotDeletionStrategy snapshotDeletionStrategy,
                                             AsyncAggregateSnapshotSettings settings,
                                             Executor executor,
                                             Optional<UnitOfWorkFactory<? extends UnitOfWork>> unitOfWorkFactory,
                                             Optional<MeterRegistry> meterRegistryOptional) {
        this.snapshotStore = requireNonNull(snapshotStore, "No snapshotStore provided");
        this.jsonSerializer = AggregateSnapshotJSONSerializer.create(requireNonNull(jsonSerializer, "No jsonSerializer provided"));
        this.addNewSnapshotStrategy = requireNonNull(addNewSnapshotStrategy, "No addNewSnapshotStrategy provided");
        this.snapshotDeletionStrategy = requireNonNull(snapshotDeletionStrategy, "No snapshotDeletionStrategy provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.executor = requireNonNull(executor, "No executor provided");
        this.measurementSupport = new AggregateSnapshotMeasurementSupport(requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided"));
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> Optional<AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>> loadSnapshot(AggregateType aggregateType,
                                                                                                        ID aggregateId,
                                                                                                        EventOrder withLastIncludedEventOrderLessThanOrEqualTo,
                                                                                                        Class<AGGREGATE_IMPL_TYPE> aggregateImplType) {
        return snapshotStore.loadSnapshot(aggregateType,
                                          aggregateId,
                                          withLastIncludedEventOrderLessThanOrEqualTo,
                                          aggregateImplType);
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> List<AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>> loadAllSnapshots(AggregateType aggregateType,
                                                                                                        ID aggregateId,
                                                                                                        Class<AGGREGATE_IMPL_TYPE> aggregateImplType,
                                                                                                        boolean includeSnapshotPayload) {
        return snapshotStore.loadAllSnapshots(aggregateType,
                                              aggregateId,
                                              aggregateImplType,
                                              includeSnapshotPayload);
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> void aggregateUpdated(AGGREGATE_IMPL_TYPE aggregate, AggregateEventStream<ID> persistedEvents) {
        requireNonNull(aggregate, "No aggregate instance supplied");
        requireNonNull(persistedEvents, "No persistedEvents stream supplied");

        var aggregateType = persistedEvents.aggregateType();
        var aggregateId = persistedEvents.aggregateId();
        var aggregateImplType = (Class<AGGREGATE_IMPL_TYPE>) aggregate.getClass();
        var mostRecentlyStoredSnapshotLastIncludedEventOrder = snapshotStore.findMostRecentLastIncludedEventOrder(aggregateType,
                                                                                                                   aggregateId,
                                                                                                                   aggregateImplType);
        if (!shouldWeAddANewAggregateSnapshot(aggregate,
                                              persistedEvents,
                                              aggregateType,
                                              aggregateImplType.getName(),
                                              mostRecentlyStoredSnapshotLastIncludedEventOrder)) {
            return;
        }

        var lastAppliedEventOrder = Lists.last(persistedEvents.eventList()).get().eventOrder();
        var deleteSnapshotEventOrders = resolveSnapshotEventOrdersToDelete(aggregateType,
                                                                           aggregateId,
                                                                           aggregateImplType);
        var serializedSnapshot = measurementSupport.recordSerializeSnapshot(aggregateType,
                                                                           aggregateImplType,
                                                                           () -> jsonSerializer.serialize(aggregate));

        Runnable persistenceTask = () -> {
            if (!deleteSnapshotEventOrders.isEmpty()) {
                snapshotStore.deleteSnapshots(aggregateType,
                                              aggregateId,
                                              aggregateImplType,
                                              deleteSnapshotEventOrders);
            } else if (!snapshotDeletionStrategy.requiresExistingSnapshotDetailsToDetermineWhichAggregateSnapshotsToDelete()) {
                snapshotStore.deleteSnapshots(aggregateType,
                                              aggregateId,
                                              aggregateImplType);
            }
            snapshotStore.saveSnapshot(aggregateType,
                                       aggregateId,
                                       aggregateImplType,
                                       lastAppliedEventOrder,
                                       serializedSnapshot);
        };

        if (settings.mode == SnapshotExecutionMode.SYNC) {
            persistenceTask.run();
        } else if (settings.mode == SnapshotExecutionMode.ASYNC_IN_MEMORY) {
            scheduleAsyncAfterCommit(aggregateType,
                                     aggregateId,
                                     aggregateImplType,
                                     lastAppliedEventOrder,
                                     persistenceTask);
        } else {
            throw new UnsupportedOperationException("SnapshotExecutionMode '" + settings.mode + "' isn't supported yet");
        }
    }

    private <ID, AGGREGATE_IMPL_TYPE> void scheduleAsyncAfterCommit(AggregateType aggregateType,
                                                                    ID aggregateId,
                                                                    Class<AGGREGATE_IMPL_TYPE> aggregateImplType,
                                                                    EventOrder lastAppliedEventOrder,
                                                                    Runnable persistenceTask) {
        Runnable asyncDispatchTask = () -> {
            log.debug("[{}:{}] Persisting Aggregate Snapshot asynchronously for '{}' and last_included_event_order {}",
                      aggregateType,
                      aggregateId,
                      aggregateImplType.getName(),
                      lastAppliedEventOrder);
            persistenceTask.run();
        };

        var currentUnitOfWork = unitOfWorkFactory.flatMap(UnitOfWorkFactory::getCurrentUnitOfWork);
        if (currentUnitOfWork.isPresent()) {
            currentUnitOfWork.get().registerLifecycleCallbackForResource(asyncDispatchTask, new AfterCommitDispatchLifecycleCallback(executor));
        } else {
            executor.execute(asyncDispatchTask);
        }
    }

    private static Executor defaultExecutor(AsyncAggregateSnapshotSettings settings) {
        if (settings.mode == SnapshotExecutionMode.SYNC) {
            return Runnable::run;
        }
        if (settings.mode == SnapshotExecutionMode.ASYNC_IN_MEMORY) {
            return Executors.newSingleThreadExecutor();
        }
        throw new UnsupportedOperationException(msg("SnapshotExecutionMode '{}' isn't supported yet", settings.mode));
    }

    private <ID, AGGREGATE_IMPL_TYPE> List<EventOrder> resolveSnapshotEventOrdersToDelete(AggregateType aggregateType,
                                                                                           ID aggregateId,
                                                                                           Class<AGGREGATE_IMPL_TYPE> aggregateImplType) {
        if (snapshotDeletionStrategy.requiresExistingSnapshotDetailsToDetermineWhichAggregateSnapshotsToDelete()) {
            var existingSnapshots = snapshotStore.loadAllSnapshots(aggregateType,
                                                                   aggregateId,
                                                                   aggregateImplType,
                                                                   false);
            return snapshotDeletionStrategy.resolveSnapshotsToDelete(existingSnapshots)
                                           .map(snapshot -> snapshot.eventOrderOfLastIncludedEvent)
                                           .collect(Collectors.toList());
        }
        return List.of();
    }

    private <ID, AGGREGATE_IMPL_TYPE> boolean shouldWeAddANewAggregateSnapshot(AGGREGATE_IMPL_TYPE aggregate,
                                                                               AggregateEventStream<ID> persistedEvents,
                                                                               AggregateType aggregateType,
                                                                               String aggregateImplType,
                                                                               Optional<EventOrder> mostRecentlyStoredSnapshotLastIncludedEventOrder) {
        if (addNewSnapshotStrategy.shouldANewAggregateSnapshotBeAdded(aggregate, persistedEvents, mostRecentlyStoredSnapshotLastIncludedEventOrder)) {
            if (log.isDebugEnabled()) {
                log.debug("[{}:{}] {} strategy determined to ADD a new Aggregate Snapshot for '{}' based on mostRecentlyStoredSnapshotLastIncludedEventOrder {} and persistedEvents->eventOrders: {}",
                          aggregateType,
                          persistedEvents.aggregateId(),
                          addNewSnapshotStrategy,
                          aggregateImplType,
                          mostRecentlyStoredSnapshotLastIncludedEventOrder,
                          persistedEvents.eventList().stream().map(persistedEvent -> persistedEvent.eventOrder().longValue()).collect(Collectors.toList()));
            }
            return true;
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}:{}] {} strategy determined NOT to add a new Aggregate Snapshot for '{}' based on mostRecentlyStoredSnapshotLastIncludedEventOrder {} and persistedEvents->eventOrders: {}",
                          aggregateType,
                          persistedEvents.aggregateId(),
                          addNewSnapshotStrategy,
                          aggregateImplType,
                          mostRecentlyStoredSnapshotLastIncludedEventOrder,
                          persistedEvents.eventList().stream().map(persistedEvent -> persistedEvent.eventOrder().longValue()).collect(Collectors.toList()));
            }
            return false;
        }
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
    public <ID, AGGREGATE_IMPL_TYPE> void deleteSnapshots(AggregateType aggregateType,
                                                          ID aggregateId,
                                                          Class<AGGREGATE_IMPL_TYPE> withAggregateImplementationType,
                                                          List<EventOrder> snapshotEventOrdersToDelete) {
        snapshotStore.deleteSnapshots(aggregateType,
                                      aggregateId,
                                      withAggregateImplementationType,
                                      snapshotEventOrdersToDelete);
    }

    private static final class AfterCommitDispatchLifecycleCallback implements UnitOfWorkLifecycleCallback<Runnable> {
        private final Executor executor;

        private AfterCommitDispatchLifecycleCallback(Executor executor) {
            this.executor = executor;
        }

        @Override
        public BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<Runnable> associatedResources) {
            return BeforeCommitProcessingStatus.COMPLETED;
        }

        @Override
        public void afterCommit(UnitOfWork unitOfWork, List<Runnable> associatedResources) {
            associatedResources.forEach(executor::execute);
        }

        @Override
        public void beforeRollback(UnitOfWork unitOfWork, List<Runnable> associatedResources, Throwable causeOfTheRollback) {
        }

        @Override
        public void afterRollback(UnitOfWork unitOfWork, List<Runnable> associatedResources, Throwable causeOfTheRollback) {
        }
    }
}
