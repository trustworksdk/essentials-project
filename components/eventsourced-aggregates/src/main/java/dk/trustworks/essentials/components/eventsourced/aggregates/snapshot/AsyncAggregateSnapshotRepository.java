/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
import dk.trustworks.essentials.shared.Lifecycle;
import dk.trustworks.essentials.shared.collections.Lists;
import dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Opt-in async-capable {@link AggregateSnapshotRepository} built on top of an {@link AggregateSnapshotStore}.
 * <p>
 * The repository is a {@link Lifecycle} bean: {@link #start()} provisions the executor pool sized
 * by {@link AsyncAggregateSnapshotSettings#workerThreads()}, {@link #stop()} tears it down. The
 * {@link Lifecycle} contract pairs with {@code DefaultLifecycleManager} so Spring-managed
 * applications get clean shutdown for free.
 * <p>
 * For {@link SnapshotExecutionMode#SYNC}, no executor is needed — tasks run on the calling thread.
 * For {@link SnapshotExecutionMode#ASYNC_IN_MEMORY}, a fixed-size daemon-threaded executor is used
 * so a forgotten {@code stop()} doesn't keep the JVM alive.
 */
@SuppressWarnings("unchecked")
public class AsyncAggregateSnapshotRepository implements AggregateSnapshotRepository, Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(AsyncAggregateSnapshotRepository.class);

    private final AggregateSnapshotStore                            snapshotStore;
    private final AggregateSnapshotStateAdapter                     snapshotStateAdapter;
    private final AddNewAggregateSnapshotStrategy                   addNewSnapshotStrategy;
    private final AggregateSnapshotDeletionStrategy                 snapshotDeletionStrategy;
    private final AsyncAggregateSnapshotSettings                    settings;
    private final AggregateSnapshotMeasurementSupport               measurementSupport;
    private final Optional<UnitOfWorkFactory<? extends UnitOfWork>> unitOfWorkFactory;

    private final    AtomicBoolean started = new AtomicBoolean();
    private volatile Executor      executor;

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
             Optional.empty(),
             Optional.empty());
    }

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
             Optional.of(unitOfWorkFactory),
             Optional.empty());
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public AsyncAggregateSnapshotRepository(AggregateSnapshotStore snapshotStore,
                                            JSONEventSerializer jsonSerializer,
                                            AddNewAggregateSnapshotStrategy addNewSnapshotStrategy,
                                            AggregateSnapshotDeletionStrategy snapshotDeletionStrategy,
                                            AsyncAggregateSnapshotSettings settings,
                                            UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory,
                                            Optional<MeterRegistry> meterRegistryOptional) {
        this(snapshotStore,
             jsonSerializer,
             addNewSnapshotStrategy,
             snapshotDeletionStrategy,
             settings,
             Optional.of(unitOfWorkFactory),
             meterRegistryOptional);
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public AsyncAggregateSnapshotRepository(AggregateSnapshotStore snapshotStore,
                                            JSONEventSerializer jsonSerializer,
                                            AddNewAggregateSnapshotStrategy addNewSnapshotStrategy,
                                            AggregateSnapshotDeletionStrategy snapshotDeletionStrategy,
                                            AsyncAggregateSnapshotSettings settings,
                                            Optional<UnitOfWorkFactory<? extends UnitOfWork>> unitOfWorkFactory,
                                            Optional<MeterRegistry> meterRegistryOptional) {
        this.snapshotStore = requireNonNull(snapshotStore, "No snapshotStore provided");
        this.snapshotStateAdapter = new DefaultAggregateSnapshotStateAdapter(requireNonNull(jsonSerializer, "No jsonSerializer provided"));
        this.addNewSnapshotStrategy = requireNonNull(addNewSnapshotStrategy, "No addNewSnapshotStrategy provided");
        this.snapshotDeletionStrategy = requireNonNull(snapshotDeletionStrategy, "No snapshotDeletionStrategy provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.measurementSupport = new AggregateSnapshotMeasurementSupport(requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided"));
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) return;

        if (settings.mode() == SnapshotExecutionMode.SYNC) {
            // No executor needed — tasks run inline on the calling thread.
            executor = Runnable::run;
            log.info("Started AsyncAggregateSnapshotRepository in SYNC mode");
            return;
        }
        if (settings.mode() == SnapshotExecutionMode.ASYNC_IN_MEMORY) {
            executor = Executors.newFixedThreadPool(settings.workerThreads(),
                                                    ThreadFactoryBuilder.builder()
                                                                        .nameFormat("async-aggregate-snapshot-%d")
                                                                        .daemon(true)
                                                                        .build());
            log.info("Started AsyncAggregateSnapshotRepository in ASYNC_IN_MEMORY mode with {} worker thread(s)",
                     settings.workerThreads());
            return;
        }
        throw new UnsupportedOperationException("SnapshotExecutionMode '" + settings.mode() + "' isn't supported yet");
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) return;

        if (executor instanceof ExecutorService executorService) {
            executorService.shutdownNow();
        }
        executor = null;
        log.info("Stopped AsyncAggregateSnapshotRepository");
    }

    @Override
    public boolean isStarted() {
        return started.get();
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
        if (!started.get() || executor == null) {
            throw new IllegalStateException("AsyncAggregateSnapshotRepository is not started — call start() before aggregateUpdated(...)");
        }

        var aggregateType     = persistedEvents.aggregateType();
        var aggregateId       = persistedEvents.aggregateId();
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
                                                                            () -> snapshotStateAdapter.serializeSnapshotState(aggregate));

        Runnable persistenceTask = () -> {
            if (!deleteSnapshotEventOrders.isEmpty()) {
                snapshotStore.deleteSnapshots(aggregateType,
                                              aggregateId,
                                              aggregateImplType,
                                              deleteSnapshotEventOrders);
            } else if (!snapshotDeletionStrategy.requiresExistingSnapshotDetailsToDetermineWhichAggregateSnapshotsToDelete()) {
                snapshotStore.deleteSnapshotsOlderThan(aggregateType,
                                                       aggregateId,
                                                       aggregateImplType,
                                                       lastAppliedEventOrder);
            }
            snapshotStore.saveSnapshot(aggregateType,
                                       aggregateId,
                                       aggregateImplType,
                                       lastAppliedEventOrder,
                                       serializedSnapshot);
        };

        if (settings.mode() == SnapshotExecutionMode.SYNC) {
            persistenceTask.run();
        } else if (settings.mode() == SnapshotExecutionMode.ASYNC_IN_MEMORY) {
            scheduleAsyncAfterCommit(aggregateType,
                                     aggregateId,
                                     aggregateImplType,
                                     lastAppliedEventOrder,
                                     persistenceTask);
        } else {
            throw new UnsupportedOperationException("SnapshotExecutionMode '" + settings.mode() + "' isn't supported yet");
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
            try {
                persistenceTask.run();
            } catch (Throwable t) {
                // Async snapshot persistence is best-effort in ASYNC_IN_MEMORY mode — there is no
                // retry queue. Surface the failure as a structured ERROR log instead of letting
                // the executor's default handler dump the stack to stderr (where it is easy to
                // miss and may also kill a worker thread). Use ASYNC_DURABLE mode if guaranteed
                // retry/delivery is required.
                log.error("[{}:{}] Failed to persist Aggregate Snapshot asynchronously for '{}' and last_included_event_order {}",
                          aggregateType,
                          aggregateId,
                          aggregateImplType.getName(),
                          lastAppliedEventOrder,
                          t);
            }
        };

        var executorSnapshot  = executor;
        var currentUnitOfWork = unitOfWorkFactory.flatMap(UnitOfWorkFactory::getCurrentUnitOfWork);
        if (currentUnitOfWork.isPresent()) {
            currentUnitOfWork.get().registerLifecycleCallbackForResource(asyncDispatchTask, new AfterCommitDispatchLifecycleCallback(executorSnapshot));
        } else {
            executorSnapshot.execute(asyncDispatchTask);
        }
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
