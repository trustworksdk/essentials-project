/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.EventStoreEventBus;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.GenericHandleAwareUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.shared.Exceptions;
import dk.trustworks.essentials.shared.time.StopWatch;
import dk.trustworks.essentials.types.LongRange;
import org.slf4j.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.stream.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptorChain.newInterceptorChainForOperation;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;
import static dk.trustworks.essentials.shared.interceptor.DefaultInterceptorChain.sortInterceptorsByOrder;

/**
 * Postgresql specific {@link EventStore} implementation
 * <p>
 * Relevant logger names:
 * <ul>
 *     <li>dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore</li>
 *     <li>dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore.PollingEventStream</li>
 * </ul>
 *
 * @param <CONFIG> The concrete {@link AggregateEventStreamConfiguration}
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class PostgresqlEventStore<CONFIG extends AggregateEventStreamConfiguration> implements ConfigurableEventStore<CONFIG> {
    private static final Logger       log              = LoggerFactory.getLogger(PostgresqlEventStore.class);
    private static final SubscriberId NO_SUBSCRIBER_ID = SubscriberId.of("NoSubscriberId");

    private final EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> unitOfWorkFactory;
    private final AggregateEventStreamPersistenceStrategy<CONFIG>   persistenceStrategy;
    private final EventStoreSubscriptionObserver                    eventStoreSubscriptionObserver;
    private final ReactiveEventStreamSubscriptionManager            reactiveSubscriptionManager;


    /**
     * Cache of specific a {@link InMemoryProjector} instance that support rehydrating/projecting a specific projection/aggregate type<br>
     * Key: Projection/Aggregate type<br>
     * Value: The specific {@link InMemoryProjector} that supports the given projection type (if provided to {@link #addSpecificInMemoryProjector(Class, InMemoryProjector)})
     * or the first {@link InMemoryProjector#supports(Class)} that reports true for the given projection type
     */
    private final ConcurrentMap<Class<?>, InMemoryProjector> inMemoryProjectorPerProjectionType;
    private final HashSet<InMemoryProjector>                 inMemoryProjectors;
    private final List<EventStoreInterceptor>                eventStoreInterceptors;
    private final EventStoreEventBus                         eventStoreEventBus;
    private final EventStreamGapHandler<CONFIG>              eventStreamGapHandler;

    /**
     * Create a {@link PostgresqlEventStore} without EventStreamGapHandler (specifically with {@link NoEventStreamGapHandler}) as a backwards compatible configuration and
     * {@link NoOpEventStoreSubscriptionObserver}
     *
     * @param unitOfWorkFactory                       the unit of work factory
     * @param aggregateEventStreamPersistenceStrategy the persistence strategy - please see {@link AggregateEventStreamPersistenceStrategy} documentation regarding <b>Security</b> considerations
     * @param jsonEventSerializer                     The JSON serializer used to serialize events and objects to/from JSON
     * @param <STRATEGY>                              the persistence strategy type
     */
    public <STRATEGY extends AggregateEventStreamPersistenceStrategy<CONFIG>> PostgresqlEventStore(EventStoreUnitOfWorkFactory unitOfWorkFactory,
                                                                                                   STRATEGY aggregateEventStreamPersistenceStrategy,
                                                                                                   JSONEventSerializer jsonEventSerializer) {
        this(unitOfWorkFactory,
             aggregateEventStreamPersistenceStrategy,
             Optional.empty(),
             eventStore -> new NoEventStreamGapHandler<>(),
             new NoOpEventStoreSubscriptionObserver(),
             jsonEventSerializer);
    }

    /**
     * Create a {@link PostgresqlEventStore} without EventStreamGapHandler (specifically with {@link NoEventStreamGapHandler}) as a backwards compatible configuration and
     * {@link NoOpEventStoreSubscriptionObserver}
     *
     * @param unitOfWorkFactory                       the unit of work factory
     * @param aggregateEventStreamPersistenceStrategy the persistence strategy - please see {@link AggregateEventStreamPersistenceStrategy} documentation regarding <b>Security</b> considerations
     * @param eventStoreSubscriptionObserver          The {@link EventStoreSubscriptionObserver} that will be used the {@link EventStore} and {@link EventStoreSubscriptionManager} to track and
     *                                                measure statistics related to {@link EventStoreSubscription}'s
     *                                                and calls to {@link #pollEvents(AggregateType, long, Optional, Optional, Optional, Optional)}
     * @param jsonEventSerializer                     The JSON serializer used to serialize events and objects to/from JSON
     * @param <STRATEGY>                              the persistence strategy type
     */
    public <STRATEGY extends AggregateEventStreamPersistenceStrategy<CONFIG>> PostgresqlEventStore(EventStoreUnitOfWorkFactory unitOfWorkFactory,
                                                                                                   STRATEGY aggregateEventStreamPersistenceStrategy,
                                                                                                   EventStoreSubscriptionObserver eventStoreSubscriptionObserver,
                                                                                                   JSONEventSerializer jsonEventSerializer) {
        this(unitOfWorkFactory,
             aggregateEventStreamPersistenceStrategy,
             Optional.empty(),
             eventStore -> new NoEventStreamGapHandler<>(),
             eventStoreSubscriptionObserver,
             jsonEventSerializer);
    }


    /**
     * Create a {@link PostgresqlEventStore} with EventStreamGapHandler (specifically with {@link PostgresqlEventStreamGapHandler})
     *
     * @param unitOfWorkFactory                       the unit of work factory
     * @param aggregateEventStreamPersistenceStrategy the persistence strategy - please see {@link AggregateEventStreamPersistenceStrategy} documentation regarding <b>Security</b> considerations
     * @param eventStoreLocalEventBusOption           option that contains {@link EventStoreEventBus} to use. If empty a new {@link EventStoreEventBus} instance will be used
     * @param eventStreamGapHandlerFactory            the {@link EventStreamGapHandler} to use for tracking event stream gaps
     * @param eventStoreSubscriptionObserver          The {@link EventStoreSubscriptionObserver} that will be used the {@link EventStore} and {@link EventStoreSubscriptionManager} to track and
     *                                                measure statistics related to {@link EventStoreSubscription}'s
     *                                                and calls to {@link #pollEvents(AggregateType, long, Optional, Optional, Optional, Optional)}
     * @param jsonEventSerializer                     The JSON serializer used to serialize events and objects to/from JSON
     * @param <STRATEGY>                              the persistence strategy type
     */
    public <STRATEGY extends AggregateEventStreamPersistenceStrategy<CONFIG>> PostgresqlEventStore(EventStoreUnitOfWorkFactory unitOfWorkFactory,
                                                                                                   STRATEGY aggregateEventStreamPersistenceStrategy,
                                                                                                   Optional<EventStoreEventBus> eventStoreLocalEventBusOption,
                                                                                                   Function<PostgresqlEventStore<CONFIG>, EventStreamGapHandler<CONFIG>> eventStreamGapHandlerFactory,
                                                                                                   EventStoreSubscriptionObserver eventStoreSubscriptionObserver,
                                                                                                   JSONEventSerializer jsonEventSerializer) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.persistenceStrategy = requireNonNull(aggregateEventStreamPersistenceStrategy, "No eventStreamPersistenceStrategy provided");
        requireNonNull(eventStoreLocalEventBusOption, "No eventStoreLocalEventBus option provided");
        requireNonNull(eventStreamGapHandlerFactory, "No eventStreamGapHandlerFactory provided");
        this.eventStoreEventBus = eventStoreLocalEventBusOption.orElseGet(() -> new EventStoreEventBus(unitOfWorkFactory));
        this.eventStreamGapHandler = eventStreamGapHandlerFactory.apply(this);
        this.eventStoreSubscriptionObserver = requireNonNull(eventStoreSubscriptionObserver, "No eventStoreSubscriptionObserver provided");

        // Initialize reactive subscription manager
        this.reactiveSubscriptionManager = new ReactiveEventStreamSubscriptionManager(
                ((GenericHandleAwareUnitOfWorkFactory<?>) unitOfWorkFactory).getJdbi(),
                requireNonNull(jsonEventSerializer, "No jsonEventSerializer provided"),
                eventStoreEventBus,
                Duration.ofMillis(10), // Faster polling interval for MultiTableChangeListener to achieve low reactive latency in tests
                persistenceStrategy,
                eventStoreSubscriptionObserver
        );
        this.reactiveSubscriptionManager.start();

        eventStoreInterceptors = new CopyOnWriteArrayList<>();
        inMemoryProjectors = new HashSet<>();
        inMemoryProjectorPerProjectionType = new ConcurrentHashMap<>();
    }

    /**
     * Create a {@link PostgresqlEventStore} without EventStreamGapHandler (specifically with {@link NoEventStreamGapHandler})<br>
     * Same as calling {@link #PostgresqlEventStore(EventStoreUnitOfWorkFactory, AggregateEventStreamPersistenceStrategy, JSONEventSerializer)}
     *
     * @param unitOfWorkFactory                       the unit of work factory
     * @param aggregateEventStreamPersistenceStrategy the persistence strategy - please see {@link AggregateEventStreamPersistenceStrategy} documentation regarding <b>Security</b> considerations
     * @param jsonEventSerializer                     The JSON serializer used to serialize events and objects to/from JSON
     * @param <CONFIG>                                The concrete {@link AggregateEventStreamConfiguration}
     * @param <STRATEGY>                              the persistence strategy type
     * @return new {@link PostgresqlEventStore} instance
     */
    public static <CONFIG extends AggregateEventStreamConfiguration, STRATEGY extends AggregateEventStreamPersistenceStrategy<CONFIG>> PostgresqlEventStore withoutGapHandling(EventStoreUnitOfWorkFactory unitOfWorkFactory,
                                                                                                                                                                               STRATEGY aggregateEventStreamPersistenceStrategy,
                                                                                                                                                                               JSONEventSerializer jsonEventSerializer) {
        return new PostgresqlEventStore<>(unitOfWorkFactory,
                                          aggregateEventStreamPersistenceStrategy,
                                          Optional.empty(),
                                          eventStore -> new NoEventStreamGapHandler<>(),
                                          new NoOpEventStoreSubscriptionObserver(),
                                          jsonEventSerializer);
    }

    /**
     * Create a {@link PostgresqlEventStore} with {@link EventStreamGapHandler} (specifically with {@link PostgresqlEventStreamGapHandler})<br>
     * Same as calling {@link #PostgresqlEventStore(EventStoreUnitOfWorkFactory, AggregateEventStreamPersistenceStrategy, Optional, Function, EventStoreSubscriptionObserver, JSONEventSerializer)} with an empty {@link EventStoreEventBus} {@link Optional}
     * and {@link NoOpEventStoreSubscriptionObserver}
     *
     * @param unitOfWorkFactory                       the unit of work factory
     * @param aggregateEventStreamPersistenceStrategy the persistence strategy - please see {@link AggregateEventStreamPersistenceStrategy} documentation regarding <b>Security</b> considerations
     * @param jsonEventSerializer                     The JSON serializer used to serialize events and objects to/from JSON
     * @param <CONFIG>                                The concrete {@link AggregateEventStreamConfiguration}
     * @param <STRATEGY>                              the persistence strategy type
     * @return new {@link PostgresqlEventStore} instance
     */
    public static <CONFIG extends AggregateEventStreamConfiguration, STRATEGY extends AggregateEventStreamPersistenceStrategy<CONFIG>> PostgresqlEventStore withGapHandling(EventStoreUnitOfWorkFactory unitOfWorkFactory,
                                                                                                                                                                            STRATEGY aggregateEventStreamPersistenceStrategy,
                                                                                                                                                                            JSONEventSerializer jsonEventSerializer) {
        return new PostgresqlEventStore<>(unitOfWorkFactory,
                                          aggregateEventStreamPersistenceStrategy,
                                          Optional.empty(),
                                          eventStore -> new PostgresqlEventStreamGapHandler<>(eventStore, unitOfWorkFactory),
                                          new NoOpEventStoreSubscriptionObserver(),
                                          jsonEventSerializer);
    }

    /**
     * Please see {@link AggregateEventStreamPersistenceStrategy} documentation regarding <b>Security</b> considerations
     *
     * @return the chosen persistenceStrategy
     */
    public AggregateEventStreamPersistenceStrategy<CONFIG> getPersistenceStrategy() {
        return persistenceStrategy;
    }

    public EventStreamGapHandler<CONFIG> getEventStreamGapHandler() {
        return eventStreamGapHandler;
    }

    @Override
    public EventBus localEventBus() {
        return eventStoreEventBus;
    }

    public ReactiveEventStreamSubscriptionManager getReactiveSubscriptionManager() {
        return reactiveSubscriptionManager;
    }

    @Override
    public ConfigurableEventStore<CONFIG> addGenericInMemoryProjector(InMemoryProjector inMemoryProjector) {
        inMemoryProjectors.add(requireNonNull(inMemoryProjector, "No inMemoryProjection"));
        return this;
    }

    @Override
    public ConfigurableEventStore<CONFIG> removeGenericInMemoryProjector(InMemoryProjector inMemoryProjector) {
        inMemoryProjectors.remove(requireNonNull(inMemoryProjector, "No inMemoryProjection"));
        return this;
    }

    @Override
    public ConfigurableEventStore<CONFIG> addSpecificInMemoryProjector(Class<?> projectionType,
                                                                       InMemoryProjector inMemoryProjector) {
        inMemoryProjectorPerProjectionType.put(requireNonNull(projectionType, "No projectionType provided"),
                                               requireNonNull(inMemoryProjector, "No inMemoryProjection"));
        return this;
    }

    @Override
    public ConfigurableEventStore<CONFIG> removeSpecificInMemoryProjector(Class<?> projectionType) {
        inMemoryProjectorPerProjectionType.remove(requireNonNull(projectionType, "No projectionType provided"));
        return this;
    }

    @Override
    public ConfigurableEventStore<CONFIG> addEventStoreInterceptor(EventStoreInterceptor eventStoreInterceptor) {
        this.eventStoreInterceptors.add(requireNonNull(eventStoreInterceptor, "No eventStoreInterceptor provided"));
        sortInterceptorsByOrder(this.eventStoreInterceptors);
        return this;
    }

    @Override
    public ConfigurableEventStore<CONFIG> removeEventStoreInterceptor(EventStoreInterceptor eventStoreInterceptor) {
        this.eventStoreInterceptors.remove(requireNonNull(eventStoreInterceptor, "No eventStoreInterceptor provided"));
        sortInterceptorsByOrder(this.eventStoreInterceptors);
        return this;
    }

    @Override
    public EventStoreSubscriptionObserver getEventStoreSubscriptionObserver() {
        return eventStoreSubscriptionObserver;
    }

    @Override
    public List<EventStoreInterceptor> getEventStoreInterceptors() {
        return Collections.unmodifiableList(this.eventStoreInterceptors);
    }

    @Override
    public <ID> AggregateEventStream<ID> appendToStream(AppendToStream<ID> operation) {
        requireNonNull(operation, "You must supply an AppendToStream operation instance");
        var unitOfWork = unitOfWorkFactory.getRequiredUnitOfWork();

        var aggregateEventStream = newInterceptorChainForOperation(operation,
                                                                   this,
                                                                   eventStoreInterceptors,
                                                                   (eventStoreInterceptor, eventStoreInterceptorChain) -> eventStoreInterceptor.intercept(operation, eventStoreInterceptorChain),
                                                                   () -> {
                                                                       var stream = persistenceStrategy.persist(unitOfWork,
                                                                                                                operation.aggregateType,
                                                                                                                operation.aggregateId,
                                                                                                                operation.getAppendEventsAfterEventOrder(),
                                                                                                                operation.getEventsToAppend());
                                                                       unitOfWork.registerEventsPersisted(stream.eventList());
                                                                       return stream;
                                                                   })
                .proceed();

        return aggregateEventStream;
    }


    @Override
    public <ID> Optional<PersistedEvent> loadLastPersistedEventRelatedTo(LoadLastPersistedEventRelatedTo<ID> operation) {
        requireNonNull(operation, "You must supply an LoadLastPersistedEventRelatedTo operation instance");
        return newInterceptorChainForOperation(operation,
                                               this,
                                               eventStoreInterceptors,
                                               (eventStoreInterceptor, eventStoreInterceptorChain) -> eventStoreInterceptor.intercept(operation, eventStoreInterceptorChain),
                                               () -> persistenceStrategy.loadLastPersistedEventRelatedTo(unitOfWorkFactory.getRequiredUnitOfWork(),
                                                                                                         operation.aggregateType,
                                                                                                         operation.aggregateId))
                .proceed();

    }

    @Override
    public Optional<PersistedEvent> loadEvent(LoadEvent operation) {
        requireNonNull(operation, "You must supply an LoadEvent operation instance");
        return newInterceptorChainForOperation(operation,
                                               this,
                                               eventStoreInterceptors,
                                               (eventStoreInterceptor, eventStoreInterceptorChain) -> eventStoreInterceptor.intercept(operation, eventStoreInterceptorChain),
                                               () -> persistenceStrategy.loadEvent(unitOfWorkFactory.getRequiredUnitOfWork(),
                                                                                   operation.aggregateType,
                                                                                   operation.eventId))
                .proceed();
    }

    @Override
    public List<PersistedEvent> loadEvents(LoadEvents operation) {
        requireNonNull(operation, "You must supply an LoadEvents operation instance");
        return newInterceptorChainForOperation(operation,
                                               this,
                                               eventStoreInterceptors,
                                               (eventStoreInterceptor, eventStoreInterceptorChain) -> eventStoreInterceptor.intercept(operation, eventStoreInterceptorChain),
                                               () -> persistenceStrategy.loadEvents(unitOfWorkFactory.getRequiredUnitOfWork(),
                                                                                    operation.aggregateType,
                                                                                    operation.eventIds))
                .proceed();
    }

    @Override
    public <ID> Optional<AggregateEventStream<ID>> fetchStream(FetchStream<ID> operation) {
        requireNonNull(operation, "You must supply an LoadEvent operation instance");

        return newInterceptorChainForOperation(operation,
                                               this,
                                               eventStoreInterceptors,
                                               (eventStoreInterceptor, eventStoreInterceptorChain) -> eventStoreInterceptor.intercept(operation, eventStoreInterceptorChain),
                                               () -> persistenceStrategy.loadAggregateEvents(unitOfWorkFactory.getRequiredUnitOfWork(),
                                                                                             operation.aggregateType,
                                                                                             operation.aggregateId,
                                                                                             operation.getEventOrderRange(),
                                                                                             operation.getTenant()))
                .proceed();
    }

    @Override
    public Optional<GlobalEventOrder> findHighestGlobalEventOrderPersisted(AggregateType aggregateType) {
        return persistenceStrategy.findHighestGlobalEventOrderPersisted(unitOfWorkFactory.getRequiredUnitOfWork(),
                                                                        aggregateType);
    }

    @Override
    public Optional<GlobalEventOrder> findLowestGlobalEventOrderPersisted(AggregateType aggregateType) {
        return persistenceStrategy.findLowestGlobalEventOrderPersisted(unitOfWorkFactory.getRequiredUnitOfWork(),
                                                                       aggregateType);
    }

    @Override
    public <ID, AGGREGATE> Optional<AGGREGATE> inMemoryProjection(AggregateType aggregateType,
                                                                  ID aggregateId,
                                                                  Class<AGGREGATE> projectionType) {
        requireNonNull(projectionType, "No projectionType provided");
        var inMemoryProjector = inMemoryProjectorPerProjectionType.computeIfAbsent(projectionType,
                                                                                   _aggregateType -> inMemoryProjectors.stream().filter(_inMemoryProjection -> _inMemoryProjection.supports(projectionType))
                                                                                                                       .findFirst()
                                                                                                                       .orElseThrow(() -> new EventStoreException(msg("Couldn't find an {} that supports projection-type '{}'",
                                                                                                                                                                      InMemoryProjector.class.getSimpleName(),
                                                                                                                                                                      projectionType.getName()))));
        return inMemoryProjection(aggregateType,
                                  aggregateId,
                                  projectionType,
                                  inMemoryProjector);
    }

    @Override
    public <ID, AGGREGATE> Optional<AGGREGATE> inMemoryProjection(AggregateType aggregateType,
                                                                  ID aggregateId,
                                                                  Class<AGGREGATE> projectionType,
                                                                  InMemoryProjector inMemoryProjector) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(aggregateId, "No aggregateId provided");
        requireNonNull(projectionType, "No projectionType provided");
        requireNonNull(inMemoryProjector, "No inMemoryProjector provided");

        if (!inMemoryProjector.supports(projectionType)) {
            throw new IllegalArgumentException(msg("The provided {} '{}' does not support projection type '{}'",
                                                   InMemoryProjector.class.getName(),
                                                   inMemoryProjector.getClass().getName(),
                                                   projectionType.getName()));
        }
        return inMemoryProjector.projectEvents(aggregateType,
                                               aggregateId,
                                               projectionType,
                                               this);
    }

    @Override
    public Stream<PersistedEvent> loadEventsByGlobalOrder(LoadEventsByGlobalOrder operation) {
        requireNonNull(operation, "You must supply an LoadEventsByGlobalOrder operation instance");

        return newInterceptorChainForOperation(operation,
                                               this,
                                               eventStoreInterceptors,
                                               (eventStoreInterceptor, eventStoreInterceptorChain) -> eventStoreInterceptor.intercept(operation, eventStoreInterceptorChain),
                                               () -> persistenceStrategy.loadEventsByGlobalOrder(unitOfWorkFactory.getRequiredUnitOfWork(),
                                                                                                 operation.aggregateType,
                                                                                                 operation.getGlobalEventOrderRange(),
                                                                                                 operation.getIncludeAdditionalGlobalOrders(),
                                                                                                 operation.getOnlyIncludeEventIfItBelongsToTenant()))
                .proceed();
    }

    @Override
    public Flux<PersistedEvent> pollEvents(AggregateType aggregateType,
                                           long fromInclusiveGlobalOrder,
                                           Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                           Optional<Duration> pollingInterval,
                                           Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                           Optional<SubscriberId> subscriberId) {
        requireNonNull(aggregateType, "You must supply an aggregateType");
        requireNonNull(pollingInterval, "You must supply a pollingInterval option");
        requireNonNull(onlyIncludeEventIfItBelongsToTenant, "You must supply a onlyIncludeEventIfItBelongsToTenant option");
        requireNonNull(subscriberId, "You must supply a subscriberId option");

        var eventStreamLogName  = "EventStream:" + aggregateType + ":" + subscriberId.orElseGet(SubscriberId::random);
        var eventStoreStreamLog = LoggerFactory.getLogger(EventStore.class.getName() + ".PollingEventStream");

        long batchFetchSize = loadEventsByGlobalOrderBatchSize.orElse(DEFAULT_QUERY_BATCH_SIZE);
        eventStoreStreamLog.debug("[{}] Creating polling reactive '{}' EventStream with fromInclusiveGlobalOrder {} and batch size {}",
                                  eventStreamLogName,
                                  aggregateType,
                                  fromInclusiveGlobalOrder,
                                  batchFetchSize);
        var consecutiveNoPersistedEventsReturned = new AtomicInteger(0);
        var lastBatchSizeForThisQuery            = new AtomicLong(batchFetchSize);
        var nextFromInclusiveGlobalOrder         = new AtomicLong(fromInclusiveGlobalOrder);
        var subscriptionGapHandler               = subscriberId.map(eventStreamGapHandler::gapHandlerFor);

        return Flux.create((FluxSink<PersistedEvent> sink) -> {
            var actualSubscriberId = subscriberId.orElse(NO_SUBSCRIBER_ID);
            var scheduler          = Schedulers.newSingle("Publish-" + actualSubscriberId + "-" + aggregateType, true);
            sink.onRequest(eventDemandSize -> {
                eventStoreStreamLog.debug("[{}] Received demand for {} events",
                                          eventStreamLogName,
                                          eventDemandSize);
                scheduler.schedule(new PollEventStoreTask(eventDemandSize,
                                                          sink,
                                                          aggregateType,
                                                          onlyIncludeEventIfItBelongsToTenant,
                                                          eventStreamLogName,
                                                          eventStoreStreamLog,
                                                          pollingInterval,
                                                          consecutiveNoPersistedEventsReturned,
                                                          batchFetchSize,
                                                          lastBatchSizeForThisQuery,
                                                          nextFromInclusiveGlobalOrder,
                                                          subscriptionGapHandler,
                                                          actualSubscriberId));

            });

            sink.onCancel(scheduler);

        }, FluxSink.OverflowStrategy.ERROR);
    }

    @Override
    public Flux<PersistedEvent> unboundedPollForEvents(AggregateType aggregateType,
                                                       long fromInclusiveGlobalOrder,
                                                       Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                                       Optional<Duration> pollingInterval,
                                                       Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                                       Optional<SubscriberId> subscriberId) {
        requireNonNull(aggregateType, "You must supply an aggregateType");
        requireNonNull(pollingInterval, "You must supply a pollingInterval option");
        requireNonNull(onlyIncludeEventIfItBelongsToTenant, "You must supply a onlyIncludeEventIfItBelongsToTenant option");
        requireNonNull(subscriberId, "You must supply a subscriberId option");

        var eventStreamLogName  = "EventStream:" + aggregateType + ":" + subscriberId.orElseGet(SubscriberId::random);
        var eventStoreStreamLog = LoggerFactory.getLogger(EventStore.class.getName() + ".PollingEventStream");

        long batchFetchSize = loadEventsByGlobalOrderBatchSize.orElse(DEFAULT_QUERY_BATCH_SIZE);
        eventStoreStreamLog.debug("[{}] Creating polling reactive '{}' EventStream with fromInclusiveGlobalOrder {} and batch size {}",
                                  eventStreamLogName,
                                  aggregateType,
                                  fromInclusiveGlobalOrder,
                                  batchFetchSize);
        var consecutiveNoPersistedEventsReturned = new AtomicInteger(0);
        var lastBatchSizeForThisQuery            = new AtomicLong(batchFetchSize);
        var nextFromInclusiveGlobalOrder         = new AtomicLong(fromInclusiveGlobalOrder);
        var subscriptionGapHandler               = subscriberId.map(eventStreamGapHandler::gapHandlerFor);
        var actualSubscriberId                   = subscriberId.orElse(NO_SUBSCRIBER_ID);
        var persistedEventsFlux = Flux.defer(() -> {
            EventStoreUnitOfWork unitOfWork;
            try {
                unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
            } catch (Exception e) {
                if (IOExceptionUtil.isIOException(e)) {
                    eventStoreStreamLog.debug(msg("[{}] Experienced a IO/Connection related issue '{}'. Will return an empty Flux",
                                                  eventStreamLogName,
                                                  e.getClass().getSimpleName()),
                                              e);
                } else {
                    eventStoreStreamLog.error(msg("[{}] Experienced a non-IO related issue '{}'. Will return an empty Flux",
                                                  eventStreamLogName,
                                                  e.getClass().getSimpleName()),
                                              e);
                }
                return Flux.empty();
            }

            try {
                var resolveBatchSizeForThisQueryTiming = StopWatch.start("resolveBatchSizeForThisQuery (" + actualSubscriberId + ", " + aggregateType + ")");
                long batchSizeForThisQuery = resolveBatchSizeForThisQuery(aggregateType,
                                                                          eventStreamLogName,
                                                                          eventStoreStreamLog,
                                                                          lastBatchSizeForThisQuery.get(),
                                                                          batchFetchSize,
                                                                          consecutiveNoPersistedEventsReturned,
                                                                          nextFromInclusiveGlobalOrder,
                                                                          unitOfWork);
                eventStoreSubscriptionObserver.resolvedBatchSizeForEventStorePoll(actualSubscriberId,
                                                                                  aggregateType,
                                                                                  batchFetchSize,
                                                                                  Long.MAX_VALUE,
                                                                                  lastBatchSizeForThisQuery.get(),
                                                                                  consecutiveNoPersistedEventsReturned.get(),
                                                                                  nextFromInclusiveGlobalOrder.get(),
                                                                                  batchSizeForThisQuery,
                                                                                  resolveBatchSizeForThisQueryTiming.stop().getDuration()
                                                                                 );

                if (batchSizeForThisQuery == 0) {
                    consecutiveNoPersistedEventsReturned.set(0);
                    lastBatchSizeForThisQuery.set(batchFetchSize);

                    eventStoreStreamLog.debug("[{}] Skipping polling as no new events have been persisted since last poll",
                                              eventStreamLogName);
                    return Flux.empty();
                } else {
                    lastBatchSizeForThisQuery.set(batchSizeForThisQuery);
                }

                var globalOrderRange = LongRange.from(nextFromInclusiveGlobalOrder.get(), batchSizeForThisQuery);
                var transientGapsToIncludeInQuery = subscriptionGapHandler.map(gapHandler -> gapHandler.findTransientGapsToIncludeInQuery(aggregateType, globalOrderRange))
                                                                          .orElse(null);

                var loadEventsByGlobalOrderTiming = StopWatch.start("loadEventsByGlobalOrder(" + actualSubscriberId + ", " + aggregateType + ")");
                var persistedEvents = loadEventsByGlobalOrder(aggregateType,
                                                              globalOrderRange,
                                                              transientGapsToIncludeInQuery,
                                                              onlyIncludeEventIfItBelongsToTenant).collect(Collectors.toList());
                eventStoreSubscriptionObserver.eventStorePolled(actualSubscriberId,
                                                                aggregateType,
                                                                globalOrderRange,
                                                                transientGapsToIncludeInQuery,
                                                                onlyIncludeEventIfItBelongsToTenant,
                                                                persistedEvents,
                                                                loadEventsByGlobalOrderTiming.stop().getDuration());
                subscriptionGapHandler.ifPresent(gapHandler -> {
                    var reconcileGapsTiming = StopWatch.start("reconcileGaps(" + actualSubscriberId + ", " + aggregateType + ")");
                    gapHandler.reconcileGaps(aggregateType,
                                             globalOrderRange,
                                             persistedEvents,
                                             transientGapsToIncludeInQuery);
                    eventStoreSubscriptionObserver.reconciledGaps(actualSubscriberId,
                                                                  aggregateType,
                                                                  globalOrderRange,
                                                                  transientGapsToIncludeInQuery, persistedEvents,
                                                                  reconcileGapsTiming.stop().getDuration());

                });
                unitOfWork.commit();
                if (persistedEvents.size() > 0) {
                    consecutiveNoPersistedEventsReturned.set(0);
                    if (log.isTraceEnabled()) {
                        eventStoreStreamLog.debug("[{}] loadEventsByGlobalOrder using globalOrderRange {} and transientGapsToIncludeInQuery {} returned {} events: {}",
                                                  eventStreamLogName,
                                                  globalOrderRange,
                                                  transientGapsToIncludeInQuery,
                                                  persistedEvents.size(),
                                                  persistedEvents.stream().map(PersistedEvent::globalEventOrder).collect(Collectors.toList()));
                    } else {
                        eventStoreStreamLog.debug("[{}] loadEventsByGlobalOrder using globalOrderRange {} and transientGapsToIncludeInQuery {} returned {} events",
                                                  eventStreamLogName,
                                                  globalOrderRange,
                                                  transientGapsToIncludeInQuery,
                                                  persistedEvents.size());
                    }
                } else {
                    consecutiveNoPersistedEventsReturned.incrementAndGet();
                    eventStoreStreamLog.trace("[{}] loadEventsByGlobalOrder using globalOrderRange {} and transientGapsToIncludeInQuery {} returned no events",
                                              eventStreamLogName,
                                              globalOrderRange,
                                              transientGapsToIncludeInQuery);
                }

                return Flux.fromIterable(persistedEvents);
            } catch (RuntimeException e) {
                log.error(msg("[{}] Polling failed", eventStreamLogName), e);
                if (unitOfWork != null) {
                    try {
                        unitOfWork.rollback(e);
                    } catch (Exception rollbackException) {
                        log.error(msg("[{}] Failed to rollback unit of work", eventStreamLogName), rollbackException);
                    }
                }
                eventStoreStreamLog.error(msg("[{}] Returning Error for '{}' EventStream with nextFromInclusiveGlobalOrder {}",
                                              eventStreamLogName,
                                              aggregateType,
                                              nextFromInclusiveGlobalOrder.get()),
                                          e);
                return Flux.error(e);
            }
        }).doOnNext(event -> {
            final long nextGlobalOrder = event.globalEventOrder().longValue() + 1L;
            eventStoreStreamLog.trace("[{}] Updating nextFromInclusiveGlobalOrder from {} to {}",
                                      eventStreamLogName,
                                      nextFromInclusiveGlobalOrder.get(),
                                      nextGlobalOrder);
            nextFromInclusiveGlobalOrder.set(nextGlobalOrder);
        }).onErrorResume(throwable -> {
            if (isCriticalError(throwable)) {
                return Flux.error(throwable);
            }
            eventStoreStreamLog.error(msg("[{}] Failed: {}",
                                          eventStreamLogName,
                                          throwable.getMessage()),
                                      throwable);
            return Flux.empty();
        });

        return persistedEventsFlux
                .repeatWhen(longFlux -> Flux.interval(pollingInterval.orElse(Duration.ofMillis(DEFAULT_POLLING_INTERVAL_MILLISECONDS)))
                                            .onBackpressureDrop()
                                            .publishOn(Schedulers.newSingle("Publish-" + subscriberId.orElse(NO_SUBSCRIBER_ID) + "-" + aggregateType, true)));
    }

    /**
     * Creates a reactive stream of events for the given aggregate type that combines immediate PostgreSQL Listen/Notify
     * notifications with intelligent fallback polling. This provides sub-millisecond latency for new events while
     * maintaining reliability during connection issues or quiet periods.
     *
     * @param aggregateType                       the type of aggregate to poll for events
     * @param fromInclusiveGlobalOrder            the global order to start polling from (inclusive)
     * @param loadEventsByGlobalOrderBatchSize    the batch size for loading events
     * @param onlyIncludeEventIfItBelongsToTenant tenant filtering option
     * @param subscriberId                        the subscriber ID for tracking and logging
     * @return a Flux that emits PersistedEvents with immediate notifications and intelligent backoff
     */
    public Flux<PersistedEvent> pollEventsReactive(AggregateType aggregateType,
                                                   long fromInclusiveGlobalOrder,
                                                   Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                                   Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                                   Optional<SubscriberId> subscriberId) {
        requireNonNull(aggregateType, "You must supply an aggregateType");
        requireNonNull(onlyIncludeEventIfItBelongsToTenant, "You must supply a onlyIncludeEventIfItBelongsToTenant option");
        requireNonNull(subscriberId, "You must supply a subscriberId option");

        var eventStreamLogName  = "ReactiveEventStream:" + aggregateType + ":" + subscriberId.orElseGet(SubscriberId::random);
        var eventStoreStreamLog = LoggerFactory.getLogger(EventStore.class.getName() + ".ReactivePollingEventStream");

        log.debug("[{}] Creating reactive event stream for '{}' starting from global order {}",
                  eventStreamLogName, aggregateType, fromInclusiveGlobalOrder);

        // State used for polling
        long batchFetchSize = loadEventsByGlobalOrderBatchSize.orElse(DEFAULT_QUERY_BATCH_SIZE);
        var consecutiveNoPersistedEventsReturned = new AtomicInteger(0);
        var lastBatchSizeForThisQuery            = new AtomicLong(batchFetchSize);
        var nextFromInclusiveGlobalOrder         = new AtomicLong(fromInclusiveGlobalOrder);
        var subscriptionGapHandler               = subscriberId.map(eventStreamGapHandler::gapHandlerFor);
        var actualSubscriberId                   = subscriberId.orElse(NO_SUBSCRIBER_ID);

        // Subscribe to notifications for immediate polling triggers
        var notificationStream = reactiveSubscriptionManager.subscribeToAggregateType(aggregateType)
                .filter(notification -> notification.getGlobalOrder() >= nextFromInclusiveGlobalOrder.get())
                .doOnNext(notification -> eventStoreStreamLog.trace("[{}] Received immediate notification: {}",
                        eventStreamLogName, notification))
                .map(EventStreamChangeNotification::getGlobalOrder)
                .distinct(); // Avoid duplicate processing of the same global order

        // Build a polling flux (single cycle) that we will repeat with dynamic backoff
        var singlePollFlux = Flux.defer(() -> {
            EventStoreUnitOfWork unitOfWork;
            try {
                unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
            } catch (Exception e) {
                if (IOExceptionUtil.isIOException(e)) {
                    eventStoreStreamLog.debug(msg("[{}] Experienced a IO/Connection related issue '{}'. Will return an empty Flux",
                            eventStreamLogName,
                            e.getClass().getSimpleName()),
                            e);
                } else {
                    eventStoreStreamLog.error(msg("[{}] Experienced a non-IO related issue '{}'. Will return an empty Flux",
                            eventStreamLogName,
                            e.getClass().getSimpleName()),
                            e);
                }
                return Flux.<PersistedEvent>empty();
            }

            try {
                var resolveBatchSizeForThisQueryTiming = StopWatch.start("resolveBatchSizeForThisQuery (" + actualSubscriberId + ", " + aggregateType + ")");
                long batchSizeForThisQuery = resolveBatchSizeForThisQuery(aggregateType,
                        eventStreamLogName,
                        eventStoreStreamLog,
                        lastBatchSizeForThisQuery.get(),
                        batchFetchSize,
                        consecutiveNoPersistedEventsReturned,
                        nextFromInclusiveGlobalOrder,
                        unitOfWork);
                eventStoreSubscriptionObserver.resolvedBatchSizeForEventStorePoll(actualSubscriberId,
                        aggregateType,
                        batchFetchSize,
                        Long.MAX_VALUE,
                        lastBatchSizeForThisQuery.get(),
                        consecutiveNoPersistedEventsReturned.get(),
                        nextFromInclusiveGlobalOrder.get(),
                        batchSizeForThisQuery,
                        resolveBatchSizeForThisQueryTiming.stop().getDuration()
                );

                if (batchSizeForThisQuery == 0) {
                    int emptyCount = consecutiveNoPersistedEventsReturned.incrementAndGet();
                    lastBatchSizeForThisQuery.set(batchFetchSize);

                    eventStoreStreamLog.debug("[{}] Skipping polling as no new events have been persisted since last poll",
                            eventStreamLogName);
                    // Record empty poll and allow backoff to increase
                    reactiveSubscriptionManager.recordEmptyPoll(aggregateType, emptyCount);
                    return Flux.<PersistedEvent>empty();
                } else {
                    lastBatchSizeForThisQuery.set(batchSizeForThisQuery);
                }

                var globalOrderRange = LongRange.from(nextFromInclusiveGlobalOrder.get(), batchSizeForThisQuery);
                var transientGapsToIncludeInQuery = subscriptionGapHandler.map(gapHandler -> gapHandler.findTransientGapsToIncludeInQuery(aggregateType, globalOrderRange))
                        .orElse(null);

                var loadEventsByGlobalOrderTiming = StopWatch.start("loadEventsByGlobalOrder(" + actualSubscriberId + ", " + aggregateType + ")");
                var persistedEvents = loadEventsByGlobalOrder(aggregateType,
                        globalOrderRange,
                        transientGapsToIncludeInQuery,
                        onlyIncludeEventIfItBelongsToTenant).collect(Collectors.toList());
                eventStoreSubscriptionObserver.eventStorePolled(actualSubscriberId,
                        aggregateType,
                        globalOrderRange,
                        transientGapsToIncludeInQuery,
                        onlyIncludeEventIfItBelongsToTenant,
                        persistedEvents,
                        loadEventsByGlobalOrderTiming.stop().getDuration());
                subscriptionGapHandler.ifPresent(gapHandler -> {
                    var reconcileGapsTiming = StopWatch.start("reconcileGaps(" + actualSubscriberId + ", " + aggregateType + ")");
                    gapHandler.reconcileGaps(aggregateType,
                            globalOrderRange,
                            persistedEvents,
                            transientGapsToIncludeInQuery);
                    eventStoreSubscriptionObserver.reconciledGaps(actualSubscriberId,
                            aggregateType,
                            globalOrderRange,
                            transientGapsToIncludeInQuery, persistedEvents,
                            reconcileGapsTiming.stop().getDuration());

                });
                unitOfWork.commit();
                if (persistedEvents.size() > 0) {
                    consecutiveNoPersistedEventsReturned.set(0);
                    if (log.isTraceEnabled()) {
                        eventStoreStreamLog.debug("[{}] loadEventsByGlobalOrder using globalOrderRange {} and transientGapsToIncludeInQuery {} returned {} events: {}",
                                eventStreamLogName,
                                globalOrderRange,
                                transientGapsToIncludeInQuery,
                                persistedEvents.size(),
                                persistedEvents.stream().map(PersistedEvent::globalEventOrder).collect(Collectors.toList()));
                    } else {
                        eventStoreStreamLog.debug("[{}] loadEventsByGlobalOrder using globalOrderRange {} and transientGapsToIncludeInQuery {} returned {} events",
                                eventStreamLogName,
                                globalOrderRange,
                                transientGapsToIncludeInQuery,
                                persistedEvents.size());
                    }
                    // Record activity to reset backoff
                    reactiveSubscriptionManager.recordPollingActivity(aggregateType, persistedEvents.size());
                } else {
                    consecutiveNoPersistedEventsReturned.incrementAndGet();
                    eventStoreStreamLog.trace("[{}] loadEventsByGlobalOrder using globalOrderRange {} and transientGapsToIncludeInQuery {} returned no events",
                            eventStreamLogName,
                            globalOrderRange,
                            transientGapsToIncludeInQuery);
                    reactiveSubscriptionManager.recordEmptyPoll(aggregateType, consecutiveNoPersistedEventsReturned.get());
                }

                return Flux.fromIterable(persistedEvents);
            } catch (RuntimeException e) {
                log.error(msg("[{}] Polling failed", eventStreamLogName), e);
                if (unitOfWork != null) {
                    try {
                        unitOfWork.rollback(e);
                    } catch (Exception rollbackException) {
                        log.error(msg("[{}] Failed to rollback unit of work", eventStreamLogName), rollbackException);
                    }
                }
                eventStoreStreamLog.error(msg("[{}] Returning Error for '{}' EventStream with nextFromInclusiveGlobalOrder {}",
                        eventStreamLogName,
                        aggregateType,
                        nextFromInclusiveGlobalOrder.get()),
                        e);
                return Flux.error(e);
            }
        }).onErrorResume(throwable -> {
            if (isCriticalError(throwable)) {
                return Flux.error(throwable);
            }
            eventStoreStreamLog.error(msg("[{}] Failed: {}",
                    eventStreamLogName,
                    throwable.getMessage()),
                    throwable);
            return Flux.empty();
        });

        // Schedule repeats using the reactive backoff strategy
        final ReactivePollingBackoffStrategy backoffStrategy =
                java.util.Optional.ofNullable(reactiveSubscriptionManager.getBackoffStrategy(aggregateType))
                        .orElseGet(ReactivePollingBackoffStrategy::new);

        var scheduledPollingFlux = Mono.delay(backoffStrategy.getCurrentInterval())
                .thenMany(singlePollFlux)
                .repeatWhen(signals -> signals
                        .flatMap(ignored -> Mono.delay(backoffStrategy.getCurrentInterval()))
                        .onBackpressureDrop()
                        .publishOn(Schedulers.newSingle("Publish-" + actualSubscriberId + "-" + aggregateType, true))
                );

        // Merge notification-triggered polling with scheduled polling
        return Flux.merge(
                notificationStream.flatMap(globalOrder -> {
                    eventStoreStreamLog.debug("[{}] Triggering immediate poll for global order {}", eventStreamLogName, globalOrder);
                    return pollForSingleEvent(aggregateType, globalOrder, onlyIncludeEventIfItBelongsToTenant);
                }),
                scheduledPollingFlux
        )
                .distinct(PersistedEvent::globalEventOrder)
                .doOnNext(event -> {
                    final long nextGlobalOrder = event.globalEventOrder().longValue() + 1L;
                    long prev = nextFromInclusiveGlobalOrder.get();
                    if (nextGlobalOrder > prev) {
                        eventStoreStreamLog.trace("[{}] Updating nextFromInclusiveGlobalOrder from {} to {}",
                                eventStreamLogName,
                                prev,
                                nextGlobalOrder);
                        nextFromInclusiveGlobalOrder.set(nextGlobalOrder);
                    }
                })
                .doOnError(error -> eventStoreStreamLog.error("[{}] Error in reactive event stream", eventStreamLogName, error))
                .onErrorResume(throwable -> {
                    if (isCriticalError(throwable)) {
                        eventStoreStreamLog.error("[{}] Critical error in reactive stream, failing fast", eventStreamLogName, throwable);
                        return Flux.error(throwable);
                    }
                    eventStoreStreamLog.warn("[{}] Non-critical error in reactive stream. Resuming with existing scheduled polling/backoff: {}",
                            eventStreamLogName, throwable.getMessage());

                    // Resume using existing scheduled polling flux and preserve pointer updates
                    return scheduledPollingFlux
                            .distinct(PersistedEvent::globalEventOrder)
                            .doOnNext(event -> {
                                final long nextGlobalOrder = event.globalEventOrder().longValue() + 1L;
                                long prev = nextFromInclusiveGlobalOrder.get();
                                if (nextGlobalOrder > prev) {
                                    eventStoreStreamLog.trace("[{}] Updating nextFromInclusiveGlobalOrder from {} to {}",
                                            eventStreamLogName,
                                            prev,
                                            nextGlobalOrder);
                                    nextFromInclusiveGlobalOrder.set(nextGlobalOrder);
                                }
                            })
                            .onErrorResume(inner -> {
                                if (isCriticalError(inner)) {
                                    eventStoreStreamLog.error("[{}] Critical error in fallback polling, failing fast", eventStreamLogName, inner);
                                    return Flux.error(inner);
                                }
                                eventStoreStreamLog.warn("[{}] Non-critical error in fallback polling: {}. Suppressing and waiting for next cycle.",
                                        eventStreamLogName, inner.getMessage());
                                return Flux.empty();
                            });
                });
    }

    /**
     * Polls for a single event with the specified global order.
     * Used by the reactive notification system to immediately fetch events that were just inserted.
     */
    private Flux<PersistedEvent> pollForSingleEvent(AggregateType aggregateType,
                                                    Long globalOrder,
                                                    Optional<Tenant> onlyIncludeEventIfItBelongsToTenant) {
        return Flux.defer(() -> {
            try {
                var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
                var events = loadEventsByGlobalOrder(aggregateType,
                                                     LongRange.only(globalOrder),
                                                     null,
                                                     onlyIncludeEventIfItBelongsToTenant)
                        .collect(Collectors.toList());
                unitOfWork.commit();

                // Record activity with reactive subscription manager
                reactiveSubscriptionManager.recordPollingActivity(aggregateType, events.size());

                return Flux.fromIterable(events);
            } catch (Exception e) {
                if (isCriticalError(e)) {
                    log.error("Critical error polling for single event with global order {}: {}", globalOrder, e.getMessage(), e);
                    return Flux.error(e);
                } else {
                    log.debug("Non-critical error polling for single event with global order {}: {}", globalOrder, e.getMessage());
                    // Record empty poll due to error
                    reactiveSubscriptionManager.recordEmptyPoll(aggregateType, 1);
                    return Flux.empty(); // Return empty on non-critical errors, regular polling will catch up
                }
            }
        });
    }

    /**
     * Determines if an error is critical and should cause the reactive stream to fail,
     * or if it's recoverable and should be handled gracefully.
     *
     * @param throwable the error to examine
     * @return true if the error is critical, false if it's recoverable
     */
    private boolean isCriticalError(Throwable throwable) {
        // Critical database connectivity issues
        if (IOExceptionUtil.isIOException(throwable)) {
            return true;
        }

        // Configuration or security issues are critical
        if (throwable instanceof IllegalArgumentException ||
                throwable instanceof SecurityException ||
                throwable instanceof IllegalStateException) {
            return true;
        }

        // OutOfMemoryError and other JVM issues are critical
        if (throwable instanceof OutOfMemoryError ||
                throwable instanceof StackOverflowError) {
            return true;
        }

        // Check root cause for SQL connection issues
        Throwable rootCause = Exceptions.getRootCause(throwable);
        if (rootCause instanceof java.sql.SQLException) {
            java.sql.SQLException sqlException = (java.sql.SQLException) rootCause;
            String                sqlState     = sqlException.getSQLState();

            // Connection issues are critical
            if (sqlState != null && (
                    sqlState.startsWith("08") ||    // Connection exception
                            sqlState.startsWith("57") ||    // Operator intervention (shutdown, etc.)
                            sqlState.equals("53300"))) {    // Too many connections
                return true;
            }
        }

        // Most other errors are recoverable (transient database issues, parsing errors, etc.)
        return false;
    }


    private long resolveBatchSizeForThisQuery(AggregateType aggregateType,
                                              String eventStreamLogName,
                                              Logger eventStoreStreamLog,
                                              long lastBatchSizeForThisQuery,
                                              long defaultBatchFetchSize,
                                              AtomicInteger consecutiveNoPersistedEventsReturned,
                                              AtomicLong nextFromInclusiveGlobalOrder,
                                              EventStoreUnitOfWork unitOfWork) {
        var batchSizeForThisQuery                       = lastBatchSizeForThisQuery;
        var currentConsecutiveNoPersistedEventsReturned = consecutiveNoPersistedEventsReturned.get();
        if (currentConsecutiveNoPersistedEventsReturned > 0 && currentConsecutiveNoPersistedEventsReturned % 100 == 0) {
            var highestPersistedGlobalEventOrder = persistenceStrategy.findHighestGlobalEventOrderPersisted(unitOfWork, aggregateType);
            if (highestPersistedGlobalEventOrder.isPresent()) {
                if (highestPersistedGlobalEventOrder.get().longValue() == nextFromInclusiveGlobalOrder.get() - 1) {
                    eventStoreStreamLog.debug("[{}] loadEventsByGlobalOrder RESETTING query batchSize back to default {} since highestPersistedGlobalEventOrder {} is the same as nextFromInclusiveGlobalOrder {} - 1",
                                              eventStreamLogName,
                                              defaultBatchFetchSize,
                                              highestPersistedGlobalEventOrder.get(),
                                              nextFromInclusiveGlobalOrder.get());
                    batchSizeForThisQuery = 0;
                } else {
//                    batchSizeForThisQuery = highestPersistedGlobalEventOrder.map(highestGlobalEventOrder -> highestGlobalEventOrder.longValue() - nextFromInclusiveGlobalOrder.get() - 1 + defaultBatchFetchSize)
//                                                                            .orElse(defaultBatchFetchSize);
//                    if (batchSizeForThisQuery > defaultBatchFetchSize) {
//                        eventStoreStreamLog.debug("[{}] loadEventsByGlobalOrder temporarily INCREASED query batchSize to {} from {} instead of default {} since highestPersistedGlobalEventOrder is {}",
//                                                  eventStreamLogName,
//                                                  batchSizeForThisQuery,
//                                                  lastBatchSizeForThisQuery,
//                                                  defaultBatchFetchSize,
//                                                  highestPersistedGlobalEventOrder.get());
//                    }
                    batchSizeForThisQuery = (long) (batchSizeForThisQuery + defaultBatchFetchSize * (currentConsecutiveNoPersistedEventsReturned / 100) * 1.0f);
                    if (batchSizeForThisQuery > defaultBatchFetchSize) {
                        eventStoreStreamLog.debug("[{}] loadEventsByGlobalOrder temporarily INCREASED query batchSize to {} from {} instead of default {} since number of consecutiveNoPersistedEventsReturned was {}",
                                                  eventStreamLogName,
                                                  batchSizeForThisQuery,
                                                  lastBatchSizeForThisQuery,
                                                  defaultBatchFetchSize,
                                                  currentConsecutiveNoPersistedEventsReturned);
                    }
                }
            } else {
                // No events persisted for this aggregate type
                eventStoreStreamLog.debug("[{}] loadEventsByGlobalOrder RESETTING query batchSize back to default {} since no events has ever been persisted",
                                          eventStreamLogName,
                                          defaultBatchFetchSize);
                batchSizeForThisQuery = 0;
            }
        } else if (currentConsecutiveNoPersistedEventsReturned > 0 && currentConsecutiveNoPersistedEventsReturned % 10 == 0) {
            batchSizeForThisQuery = (long) (batchSizeForThisQuery + defaultBatchFetchSize * (currentConsecutiveNoPersistedEventsReturned / 10) * 0.5f);
            if (batchSizeForThisQuery > defaultBatchFetchSize) {
                eventStoreStreamLog.debug("[{}] loadEventsByGlobalOrder temporarily INCREASED query batchSize to {} from {} instead of default {} since number of consecutiveNoPersistedEventsReturned was {}",
                                          eventStreamLogName,
                                          batchSizeForThisQuery,
                                          lastBatchSizeForThisQuery,
                                          defaultBatchFetchSize,
                                          currentConsecutiveNoPersistedEventsReturned);
            }
        } else if (currentConsecutiveNoPersistedEventsReturned == 0) {
            if (batchSizeForThisQuery != defaultBatchFetchSize) {
                eventStoreStreamLog.debug("[{}] loadEventsByGlobalOrder RESETTING query batchSize back to default {} from {} as new events have been received",
                                          eventStreamLogName,
                                          defaultBatchFetchSize,
                                          batchSizeForThisQuery);

                batchSizeForThisQuery = defaultBatchFetchSize;
            }
        }
        return batchSizeForThisQuery;
    }

    @Override
    public EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> getUnitOfWorkFactory() {
        return unitOfWorkFactory;
    }

    @Override
    public ConfigurableEventStore<CONFIG> addAggregateEventStreamConfiguration(CONFIG aggregateTypeConfiguration) {
        persistenceStrategy.addAggregateEventStreamConfiguration(aggregateTypeConfiguration);
        return this;
    }

    @Override
    public ConfigurableEventStore<CONFIG> addAggregateEventStreamConfiguration(AggregateType aggregateType, AggregateIdSerializer aggregateIdSerializer) {
        persistenceStrategy.addAggregateEventStreamConfiguration(aggregateType, aggregateIdSerializer);
        return this;
    }

    @Override
    public ConfigurableEventStore<CONFIG> addAggregateEventStreamConfiguration(AggregateType aggregateType, Class<?> aggregateIdType) {
        persistenceStrategy.addAggregateEventStreamConfiguration(aggregateType, aggregateIdType);
        return this;
    }

    @Override
    public Optional<CONFIG> findAggregateEventStreamConfiguration(AggregateType aggregateType) {
        return persistenceStrategy.findAggregateEventStreamConfiguration(aggregateType);
    }

    @Override
    public CONFIG getAggregateEventStreamConfiguration(AggregateType aggregateType) {
        return persistenceStrategy.getAggregateEventStreamConfiguration(aggregateType);
    }

    /**
     * Task responsible for polling the event store on behalf of a single subscriber
     */
    private class PollEventStoreTask implements Runnable {
        private final long                             demandForEvents;
        private final FluxSink<PersistedEvent>         sink;
        private final AggregateType                    aggregateType;
        private final Optional<Tenant>                 onlyIncludeEventIfItBelongsToTenant;
        private final String                           eventStreamLogName;
        private final Logger                           eventStoreStreamLog;
        private final Optional<Duration>               pollingInterval;
        private final AtomicInteger                    consecutiveNoPersistedEventsReturned;
        private final long                             batchFetchSize;
        private final AtomicLong                       lastBatchSizeForThisQuery;
        private final AtomicLong                       nextFromInclusiveGlobalOrder;
        private final Optional<SubscriptionGapHandler> subscriptionGapHandler;
        private final SubscriberId                     subscriberId;

        public PollEventStoreTask(long demandForEvents,
                                  FluxSink<PersistedEvent> sink,
                                  AggregateType aggregateType,
                                  Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                  String eventStreamLogName,
                                  Logger eventStoreStreamLog,
                                  Optional<Duration> pollingInterval,
                                  AtomicInteger consecutiveNoPersistedEventsReturned,
                                  long batchFetchSize,
                                  AtomicLong lastBatchSizeForThisQuery,
                                  AtomicLong nextFromInclusiveGlobalOrder,
                                  Optional<SubscriptionGapHandler> subscriptionGapHandler,
                                  SubscriberId subscriberId) {
            this.demandForEvents = demandForEvents;
            this.sink = sink;
            this.aggregateType = aggregateType;
            this.onlyIncludeEventIfItBelongsToTenant = onlyIncludeEventIfItBelongsToTenant;
            this.eventStreamLogName = eventStreamLogName;
            this.eventStoreStreamLog = eventStoreStreamLog;
            this.pollingInterval = pollingInterval;
            this.consecutiveNoPersistedEventsReturned = consecutiveNoPersistedEventsReturned;
            this.batchFetchSize = batchFetchSize;
            this.lastBatchSizeForThisQuery = lastBatchSizeForThisQuery;
            this.nextFromInclusiveGlobalOrder = nextFromInclusiveGlobalOrder;
            this.subscriptionGapHandler = subscriptionGapHandler;
            this.subscriberId = subscriberId;
        }

        @Override
        public void run() {
            eventStoreStreamLog.debug("[{}] Polling worker - Started with initial demand for events {}",
                                      eventStreamLogName,
                                      demandForEvents);
            var pollingSleep             = pollingInterval.orElse(Duration.ofMillis(DEFAULT_POLLING_INTERVAL_MILLISECONDS)).toMillis();
            var remainingDemandForEvents = demandForEvents;
            while (remainingDemandForEvents > 0 && !sink.isCancelled()) {
                var numberOfEventsPublished = pollForEvents(remainingDemandForEvents);
                remainingDemandForEvents -= numberOfEventsPublished;
                eventStoreStreamLog.trace("[{}] Polling worker published {} event(s) - Outstanding demand for events {}",
                                          eventStreamLogName,
                                          numberOfEventsPublished,
                                          remainingDemandForEvents);
                if (numberOfEventsPublished == 0) {
                    try {
                        Thread.sleep(pollingSleep);
                    } catch (InterruptedException e) {
                        // Ignore
                        Thread.currentThread().interrupt();
                    }
                }
            }
            eventStoreStreamLog.debug("[{}] Polling worker - Completed with remaining demand for events {}. Is Cancelled: {}",
                                      eventStreamLogName,
                                      remainingDemandForEvents,
                                      sink.isCancelled());
        }

        /**
         * Poll the event store for events
         *
         * @param remainingDemandForEvents the remaining demand from the subscriber/consumer
         * @return the number of events published to the subscriber/consumer
         */
        private long pollForEvents(long remainingDemandForEvents) {
            eventStoreStreamLog.trace("[{}] Polling worker - Polling for {} events",
                                      eventStreamLogName,
                                      remainingDemandForEvents);
            EventStoreUnitOfWork unitOfWork;
            try {
                unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
            } catch (Exception e) {
                if (IOExceptionUtil.isIOException(e)) {
                    eventStoreStreamLog.debug(msg("[{}] Polling worker - Experienced an IO/Connection related issue '{}' while creating a UnitOfWork",
                                                  eventStreamLogName,
                                                  e.getClass().getSimpleName()),
                                              e);
                } else {
                    log.error(msg("[{}] Polling worker - Experienced a non IO related issue '{}' while creating a UnitOfWork",
                                  eventStreamLogName,
                                  e.getClass().getSimpleName()),
                              e);
                }
                return 0;
            }

            try {
                var resolveBatchSizeForThisQueryTiming = StopWatch.start("resolveBatchSizeForThisQuery (" + subscriberId + ", " + aggregateType + ")");
                long batchSizeForThisQuery = resolveBatchSizeForThisQuery(aggregateType,
                                                                          eventStreamLogName,
                                                                          eventStoreStreamLog,
                                                                          lastBatchSizeForThisQuery.get(),
                                                                          Math.min(batchFetchSize, remainingDemandForEvents),
                                                                          consecutiveNoPersistedEventsReturned,
                                                                          nextFromInclusiveGlobalOrder,
                                                                          unitOfWork);
                eventStoreSubscriptionObserver.resolvedBatchSizeForEventStorePoll(subscriberId,
                                                                                  aggregateType,
                                                                                  batchFetchSize,
                                                                                  remainingDemandForEvents,
                                                                                  lastBatchSizeForThisQuery.get(),
                                                                                  consecutiveNoPersistedEventsReturned.get(),
                                                                                  nextFromInclusiveGlobalOrder.get(),
                                                                                  batchSizeForThisQuery,
                                                                                  resolveBatchSizeForThisQueryTiming.stop().getDuration()
                                                                                 );

                if (batchSizeForThisQuery == 0) {
                    eventStoreSubscriptionObserver.skippingPollingDueToNoNewEventsPersisted(subscriberId,
                                                                                            aggregateType,
                                                                                            batchFetchSize,
                                                                                            remainingDemandForEvents,
                                                                                            lastBatchSizeForThisQuery.get(),
                                                                                            consecutiveNoPersistedEventsReturned.get(),
                                                                                            nextFromInclusiveGlobalOrder.get(),
                                                                                            batchSizeForThisQuery
                                                                                           );
                    consecutiveNoPersistedEventsReturned.set(0);
                    lastBatchSizeForThisQuery.set(remainingDemandForEvents);

                    eventStoreStreamLog.debug("[{}] Polling worker - Skipping polling as no new events have been persisted since last poll",
                                              eventStreamLogName);
                    return 0;
                } else {
                    lastBatchSizeForThisQuery.set(batchSizeForThisQuery);
                    eventStoreStreamLog.trace("[{}] Polling worker - Using batchSizeForThisQuery: {}",
                                              eventStreamLogName,
                                              batchSizeForThisQuery);
                }

                var globalOrderRange = LongRange.from(nextFromInclusiveGlobalOrder.get(), batchSizeForThisQuery);
                var transientGapsToIncludeInQuery = subscriptionGapHandler.map(gapHandler -> gapHandler.findTransientGapsToIncludeInQuery(aggregateType, globalOrderRange))
                                                                          .orElse(null);

                var loadEventsByGlobalOrderTiming = StopWatch.start("loadEventsByGlobalOrder(" + subscriberId + ", " + aggregateType + ")");
                var persistedEvents = loadEventsByGlobalOrder(aggregateType,
                                                              globalOrderRange,
                                                              transientGapsToIncludeInQuery,
                                                              onlyIncludeEventIfItBelongsToTenant)
                        .toList();
                eventStoreSubscriptionObserver.eventStorePolled(subscriberId,
                                                                aggregateType,
                                                                globalOrderRange,
                                                                transientGapsToIncludeInQuery,
                                                                onlyIncludeEventIfItBelongsToTenant,
                                                                persistedEvents,
                                                                loadEventsByGlobalOrderTiming.stop().getDuration());

                subscriptionGapHandler.ifPresent(gapHandler -> {
                    var reconcileGapsTiming = StopWatch.start("reconcileGaps(" + subscriberId + ", " + aggregateType + ")");
                    gapHandler.reconcileGaps(aggregateType,
                                             globalOrderRange,
                                             persistedEvents,
                                             transientGapsToIncludeInQuery);
                    eventStoreSubscriptionObserver.reconciledGaps(subscriberId,
                                                                  aggregateType,
                                                                  globalOrderRange,
                                                                  transientGapsToIncludeInQuery, persistedEvents,
                                                                  reconcileGapsTiming.stop().getDuration());
                });
                unitOfWork.commit();
                unitOfWork = null;
                if (!persistedEvents.isEmpty()) {
                    consecutiveNoPersistedEventsReturned.set(0);
                    if (log.isTraceEnabled()) {
                        eventStoreStreamLog.debug("[{}] Polling worker - loadEventsByGlobalOrder using globalOrderRange {} and transientGapsToIncludeInQuery {} returned {} events: {}",
                                                  eventStreamLogName,
                                                  globalOrderRange,
                                                  transientGapsToIncludeInQuery,
                                                  persistedEvents.size(),
                                                  persistedEvents.stream().map(PersistedEvent::globalEventOrder).collect(Collectors.toList()));
                    } else {
                        eventStoreStreamLog.debug("[{}] Polling worker - loadEventsByGlobalOrder using globalOrderRange {} and transientGapsToIncludeInQuery {} returned {} events",
                                                  eventStreamLogName,
                                                  globalOrderRange,
                                                  transientGapsToIncludeInQuery,
                                                  persistedEvents.size());
                    }

                    var eventsToPublish = persistedEvents;
                    if (persistedEvents.size() > remainingDemandForEvents) {
                        eventStoreStreamLog.debug("[{}] Polling worker - Found {} event(s) to publish, but will only publish {} of the found event(s) as this matches with the remainingDemandForEvents",
                                                  eventStreamLogName,
                                                  persistedEvents.size(),
                                                  remainingDemandForEvents);
                        eventsToPublish = persistedEvents.subList(0, (int) remainingDemandForEvents);
                    }

                    for (int index = 0; index < eventsToPublish.size(); index++) {
                        if (sink.isCancelled()) {
                            eventStoreStreamLog.debug("[{}] Polling worker - Is Cancelled: true. Skipping publishing further events (has only published {} out of the planned {} events)",
                                                      eventStreamLogName,
                                                      index + 1,
                                                      eventsToPublish.size());
                            return index;
                        }
                        publishEventToSink(eventsToPublish.get(index));
                    }
                    return eventsToPublish.size();
                } else {
                    consecutiveNoPersistedEventsReturned.incrementAndGet();
                    eventStoreStreamLog.trace("[{}] Polling worker - loadEventsByGlobalOrder using globalOrderRange {} and transientGapsToIncludeInQuery {} returned no events",
                                              eventStreamLogName,
                                              globalOrderRange,
                                              transientGapsToIncludeInQuery);
                    return 0;
                }
            } catch (RuntimeException e) {
                log.error(msg("[{}] Polling worker - Polling failed", eventStreamLogName), e);
                if (unitOfWork != null) {
                    try {
                        eventStoreStreamLog.debug("[{}] Polling worker - rolling back UnitOfWork due to error during polling",
                                                  eventStreamLogName);
                        unitOfWork.rollback(e);
                    } catch (Exception rollbackException) {
                        log.error(msg("[{}] Polling worker - Failed to rollback unit of work", eventStreamLogName), rollbackException);
                    }
                }
                eventStoreStreamLog.error(msg("[{}] Polling worker - Returning Error for '{}' EventStream with nextFromInclusiveGlobalOrder {}",
                                              eventStreamLogName,
                                              aggregateType,
                                              nextFromInclusiveGlobalOrder.get()),
                                          e);
                return 0;
            }
        }

        private void publishEventToSink(PersistedEvent persistedEvent) {
            eventStoreStreamLog.trace("[{}] Polling worker - Publishing '{}' Event '{}' with globalOrder {} to Flux",
                                      eventStreamLogName,
                                      persistedEvent.aggregateType(),
                                      persistedEvent.event().getEventTypeOrNamePersistenceValue(),
                                      persistedEvent.globalEventOrder());
            var publishEventTiming = StopWatch.start("publishEventToSink (" + subscriberId + ", " + aggregateType + ")");
            sink.next(persistedEvent);
            eventStoreSubscriptionObserver.publishEvent(subscriberId,
                                                        aggregateType,
                                                        persistedEvent,
                                                        publishEventTiming.stop().getDuration());
            var nextGlobalOrder = persistedEvent.globalEventOrder().longValue() + 1L;
            eventStoreStreamLog.trace("[{}] Polling worker - Updating nextFromInclusiveGlobalOrder from {} to {}",
                                      eventStreamLogName,
                                      nextFromInclusiveGlobalOrder.get(),
                                      nextGlobalOrder);
            nextFromInclusiveGlobalOrder.set(nextGlobalOrder);
        }
    }
}
