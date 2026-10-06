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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.EventStoreEventBus;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.internal.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.shared.time.StopWatch;
import dk.trustworks.essentials.types.LongRange;
import org.slf4j.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptorChain.newInterceptorChainForOperation;
import static dk.trustworks.essentials.shared.Exceptions.isCriticalError;
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
     * The middles of wide gaps every polling subscription awaits in memory only, kept per subscriber and aggregate type
     * so they outlive one subscribe of the subscription - see {@link GapMiddlesAwaitedAcrossSubscribes}
     */
    private final GapMiddlesAwaitedAcrossSubscribes          gapMiddlesAwaitedAcrossSubscribes = new GapMiddlesAwaitedAcrossSubscribes();

    /**
     * Create a {@link PostgresqlEventStoreBuilder} that names every argument and accepts both plain values and
     * {@link Optional}s.
     *
     * @param <CONFIG> the concrete {@link AggregateEventStreamConfiguration}
     * @return the builder
     */
    public static <CONFIG extends AggregateEventStreamConfiguration> PostgresqlEventStoreBuilder<CONFIG> builder() {
        return new PostgresqlEventStoreBuilder<>();
    }

    /**
     * Create a {@link PostgresqlEventStore} without EventStreamGapHandler (specifically with {@link NoEventStreamGapHandler}) as a backwards compatible configuration and
     * {@link NoOpEventStoreSubscriptionObserver}
     *
     * @param unitOfWorkFactory                       the unit of work factory
     * @param aggregateEventStreamPersistenceStrategy the persistence strategy - please see {@link AggregateEventStreamPersistenceStrategy} documentation regarding <b>Security</b> considerations
     * @param <STRATEGY>                              the persistence strategy type
     */
    public <STRATEGY extends AggregateEventStreamPersistenceStrategy<CONFIG>> PostgresqlEventStore(EventStoreUnitOfWorkFactory unitOfWorkFactory,
                                                                                                   STRATEGY aggregateEventStreamPersistenceStrategy) {
        this(unitOfWorkFactory,
             aggregateEventStreamPersistenceStrategy,
             Optional.empty(),
             eventStore -> new NoEventStreamGapHandler<>(),
             new NoOpEventStoreSubscriptionObserver());
    }

    /**
     * Create a {@link PostgresqlEventStore} without EventStreamGapHandler (specifically with {@link NoEventStreamGapHandler}) as a backwards compatible configuration and
     * {@link NoOpEventStoreSubscriptionObserver}
     *
     * @param unitOfWorkFactory                       the unit of work factory
     * @param aggregateEventStreamPersistenceStrategy the persistence strategy - please see {@link AggregateEventStreamPersistenceStrategy} documentation regarding <b>Security</b> considerations
     * @param <STRATEGY>                              the persistence strategy type
     */
    public <STRATEGY extends AggregateEventStreamPersistenceStrategy<CONFIG>> PostgresqlEventStore(EventStoreUnitOfWorkFactory unitOfWorkFactory,
                                                                                                   STRATEGY aggregateEventStreamPersistenceStrategy,
                                                                                                   EventStoreSubscriptionObserver eventStoreSubscriptionObserver) {
        this(unitOfWorkFactory,
             aggregateEventStreamPersistenceStrategy,
             Optional.empty(),
             eventStore -> new NoEventStreamGapHandler<>(),
             eventStoreSubscriptionObserver);
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
     *                                                and calls to {@link #pollEvents(AggregateType, long, Optional, Optional, Optional, Optional, Optional)}
     * @param <STRATEGY>                              the persistence strategy type
     */
    <STRATEGY extends AggregateEventStreamPersistenceStrategy<CONFIG>> PostgresqlEventStore(EventStoreUnitOfWorkFactory unitOfWorkFactory,
                                                                                                   STRATEGY aggregateEventStreamPersistenceStrategy,
                                                                                                   Optional<EventStoreEventBus> eventStoreLocalEventBusOption,
                                                                                                   Function<PostgresqlEventStore<CONFIG>, EventStreamGapHandler<CONFIG>> eventStreamGapHandlerFactory,
                                                                                                   EventStoreSubscriptionObserver eventStoreSubscriptionObserver) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.persistenceStrategy = requireNonNull(aggregateEventStreamPersistenceStrategy, "No eventStreamPersistenceStrategy provided");
        requireNonNull(eventStoreLocalEventBusOption, "No eventStoreLocalEventBus option provided");
        requireNonNull(eventStreamGapHandlerFactory, "No eventStreamGapHandlerFactory provided");
        this.eventStoreEventBus = eventStoreLocalEventBusOption.orElseGet(() -> new EventStoreEventBus(unitOfWorkFactory));
        this.eventStreamGapHandler = eventStreamGapHandlerFactory.apply(this);
        this.eventStoreSubscriptionObserver = requireNonNull(eventStoreSubscriptionObserver, "No eventStoreSubscriptionObserver provided");

        eventStoreInterceptors = new CopyOnWriteArrayList<>();
        inMemoryProjectors = new HashSet<>();
        inMemoryProjectorPerProjectionType = new ConcurrentHashMap<>();
    }

    /**
     * Create a {@link PostgresqlEventStore} without EventStreamGapHandler (specifically with {@link NoEventStreamGapHandler})<br>
     * Same as calling {@link #PostgresqlEventStore(EventStoreUnitOfWorkFactory, AggregateEventStreamPersistenceStrategy)}
     *
     * @param unitOfWorkFactory                       the unit of work factory
     * @param aggregateEventStreamPersistenceStrategy the persistence strategy - please see {@link AggregateEventStreamPersistenceStrategy} documentation regarding <b>Security</b> considerations
     * @param <CONFIG>                                The concrete {@link AggregateEventStreamConfiguration}
     * @param <STRATEGY>                              the persistence strategy type
     * @return new {@link PostgresqlEventStore} instance
     */
    public static <CONFIG extends AggregateEventStreamConfiguration, STRATEGY extends AggregateEventStreamPersistenceStrategy<CONFIG>> PostgresqlEventStore withoutGapHandling(EventStoreUnitOfWorkFactory unitOfWorkFactory,
                                                                                                                                                                               STRATEGY aggregateEventStreamPersistenceStrategy) {
        return new PostgresqlEventStore<>(unitOfWorkFactory,
                                          aggregateEventStreamPersistenceStrategy,
                                          Optional.empty(),
                                          eventStore -> new NoEventStreamGapHandler<>(),
                                          new NoOpEventStoreSubscriptionObserver());
    }

    /**
     * Create a {@link PostgresqlEventStore} with {@link EventStreamGapHandler} (specifically with {@link PostgresqlEventStreamGapHandler})<br>
     * Same as calling {@link #PostgresqlEventStore(EventStoreUnitOfWorkFactory, AggregateEventStreamPersistenceStrategy, Optional, Function, EventStoreSubscriptionObserver)} with an empty {@link EventStoreEventBus} {@link Optional}
     * and {@link NoOpEventStoreSubscriptionObserver}
     *
     * @param unitOfWorkFactory                       the unit of work factory
     * @param aggregateEventStreamPersistenceStrategy the persistence strategy - please see {@link AggregateEventStreamPersistenceStrategy} documentation regarding <b>Security</b> considerations
     * @param <CONFIG>                                The concrete {@link AggregateEventStreamConfiguration}
     * @param <STRATEGY>                              the persistence strategy type
     * @return new {@link PostgresqlEventStore} instance
     */
    public static <CONFIG extends AggregateEventStreamConfiguration, STRATEGY extends AggregateEventStreamPersistenceStrategy<CONFIG>> PostgresqlEventStore withGapHandling(EventStoreUnitOfWorkFactory unitOfWorkFactory,
                                                                                                                                                                            STRATEGY aggregateEventStreamPersistenceStrategy) {
        return new PostgresqlEventStore<>(unitOfWorkFactory,
                                          aggregateEventStreamPersistenceStrategy,
                                          Optional.empty(),
                                          eventStore -> new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory),
                                          new NoOpEventStoreSubscriptionObserver());
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

    /**
     * Drops the middles of wide gaps the subscriber's polls awaited in memory only - see
     * {@link GapMiddlesAwaitedAcrossSubscribes}
     */
    @Override
    public void forgetGapMiddlesAwaitedInMemory(SubscriberId subscriberId, AggregateType aggregateType) {
        gapMiddlesAwaitedAcrossSubscribes.forget(subscriberId, aggregateType);
    }

    @Override
    public EventBus localEventBus() {
        return eventStoreEventBus;
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

    /**
     * {@inheritDoc}
     * <p>
     * One {@link #loadEvent(LoadEvent)} per registered aggregate type, in table-name order, so every lookup goes through
     * the {@link EventStoreInterceptor} chain like any other {@link LoadEvent}.
     */
    @Override
    public Optional<PersistedEvent> findEvent(EventId eventId) {
        requireNonNull(eventId, "No eventId provided");
        return persistenceStrategy.getSeparateTablePerEventStreamTableNameAggregates()
                                  .entrySet()
                                  .stream()
                                  .sorted(Map.Entry.comparingByKey())
                                  .map(tableAndAggregateType -> findEventIn(tableAndAggregateType.getValue(), eventId))
                                  .flatMap(Optional::stream)
                                  .findFirst();
    }

    private Optional<PersistedEvent> findEventIn(AggregateType aggregateType, EventId eventId) {
        try {
            return loadEvent(new LoadEvent(aggregateType, eventId));
        } catch (IllegalArgumentException e) {
            // A UUID-typed event-id column cannot hold an id that is not a UUID, so the event is not in this table
            return Optional.empty();
        }
    }

    @Override
    public List<PersistedEvent> loadEventsCausedBy(LoadEventsCausedBy operation) {
        requireNonNull(operation, "You must supply a LoadEventsCausedBy operation instance");
        return newInterceptorChainForOperation(operation,
                                               this,
                                               eventStoreInterceptors,
                                               (eventStoreInterceptor, eventStoreInterceptorChain) -> eventStoreInterceptor.intercept(operation, eventStoreInterceptorChain),
                                               () -> persistenceStrategy.loadEventsCausedBy(unitOfWorkFactory.getRequiredUnitOfWork(),
                                                                                            operation.causedByEventId))
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

    private Stream<PersistedEvent> loadEventsByGlobalOrderFromPersistence(LoadEventsByGlobalOrder operation) {
        var onlyLoadPayloadIfEventBelongsToTenant = operation.getOnlyLoadPayloadIfEventBelongsToTenant();
        if (onlyLoadPayloadIfEventBelongsToTenant.isPresent() && operation.getOnlyIncludeEventIfItBelongsToTenant().isEmpty()) {
            return persistenceStrategy.loadEventsByGlobalOrderOmittingOtherTenantsPayloads(unitOfWorkFactory.getRequiredUnitOfWork(),
                                                                                           operation.aggregateType,
                                                                                           operation.getGlobalEventOrderRange(),
                                                                                           operation.getIncludeAdditionalGlobalOrders(),
                                                                                           onlyLoadPayloadIfEventBelongsToTenant.get());
        }
        return persistenceStrategy.loadEventsByGlobalOrder(unitOfWorkFactory.getRequiredUnitOfWork(),
                                                           operation.aggregateType,
                                                           operation.getGlobalEventOrderRange(),
                                                           operation.getIncludeAdditionalGlobalOrders(),
                                                           operation.getOnlyIncludeEventIfItBelongsToTenant());
    }

    @Override
    public Stream<PersistedEvent> loadEventsByGlobalOrder(LoadEventsByGlobalOrder operation) {
        requireNonNull(operation, "You must supply an LoadEventsByGlobalOrder operation instance");

        return newInterceptorChainForOperation(operation,
                                               this,
                                               eventStoreInterceptors,
                                               (eventStoreInterceptor, eventStoreInterceptorChain) -> eventStoreInterceptor.intercept(operation, eventStoreInterceptorChain),
                                               () -> loadEventsByGlobalOrderFromPersistence(operation))
                .proceed();
    }

    @Override
    public Flux<PersistedEvent> pollEvents(AggregateType aggregateType,
                                           long fromInclusiveGlobalOrder,
                                           Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                           Optional<Duration> pollingInterval,
                                           Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                           Optional<SubscriberId> subscriberId,
                                           Optional<Function<String, EventStorePollingOptimizer>> eventStorePollingOptimizerFactory) {
        return pollEvents(aggregateType,
                          fromInclusiveGlobalOrder,
                          loadEventsByGlobalOrderBatchSize,
                          pollingInterval,
                          onlyIncludeEventIfItBelongsToTenant,
                          subscriberId,
                          eventStorePollingOptimizerFactory,
                          Optional.empty());
    }

    /**
     * Honours the acknowledgement: a gap fill's transient gap is resolved when the subscriber acknowledges the event, not
     * once a poll handed it on - see {@link GapFillsAwaitingAcknowledgement}
     */
    @Override
    public Flux<PersistedEvent> pollEvents(AggregateType aggregateType,
                                           long fromInclusiveGlobalOrder,
                                           Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                           Optional<Duration> pollingInterval,
                                           Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                           Optional<SubscriberId> subscriberId,
                                           Optional<Function<String, EventStorePollingOptimizer>> eventStorePollingOptimizerFactory,
                                           SubscriberAcknowledgement acknowledgement) {
        return pollEvents(aggregateType,
                          fromInclusiveGlobalOrder,
                          loadEventsByGlobalOrderBatchSize,
                          pollingInterval,
                          onlyIncludeEventIfItBelongsToTenant,
                          subscriberId,
                          eventStorePollingOptimizerFactory,
                          Optional.of(requireNonNull(acknowledgement, "No acknowledgement provided")));
    }

    private Flux<PersistedEvent> pollEvents(AggregateType aggregateType,
                                            long fromInclusiveGlobalOrder,
                                            Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                            Optional<Duration> pollingInterval,
                                            Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                            Optional<SubscriberId> subscriberId,
                                            Optional<Function<String, EventStorePollingOptimizer>> eventStorePollingOptimizerFactory,
                                            Optional<SubscriberAcknowledgement> acknowledgement) {
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

        var eventStoreOptimizer = eventStorePollingOptimizerFactory.map(pollingOptimizerFactory -> pollingOptimizerFactory.apply(eventStreamLogName)).orElse(EventStorePollingOptimizer.None());

        // One awaitingAcknowledgement per subscribe, registered before the first request, so the subscriber can acknowledge
        // whatever it is handed - and the middles of wide gaps the subscriber's earlier subscribes still await
        return registeredWithAcknowledgement(acknowledgement, subscriptionGapHandler, aggregateType, eventStreamLogName, nextFromInclusiveGlobalOrder, (awaitingAcknowledgement, gapMiddlesAwaitedInMemory) -> Flux.create((FluxSink<PersistedEvent> sink) -> {
            var actualSubscriberId      = subscriberId.orElse(NO_SUBSCRIBER_ID);
            var scheduler               = Schedulers.newSingle("Publish-" + actualSubscriberId + "-" + aggregateType, true);
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
                                                          actualSubscriberId,
                                                          eventStoreOptimizer,
                                                          awaitingAcknowledgement,
                                                          gapMiddlesAwaitedInMemory));
            });

            // Also when a polling worker ended the flux with an error (see PollEventStoreTask), not just on cancel
            sink.onDispose(scheduler);

        }, FluxSink.OverflowStrategy.ERROR));
    }

    @Override
    public Flux<PersistedEvent> unboundedPollForEvents(AggregateType aggregateType,
                                                       long fromInclusiveGlobalOrder,
                                                       Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                                       Optional<Duration> pollingInterval,
                                                       Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                                       Optional<SubscriberId> subscriberId) {
        return unboundedPollForEvents(aggregateType,
                                      fromInclusiveGlobalOrder,
                                      loadEventsByGlobalOrderBatchSize,
                                      pollingInterval,
                                      onlyIncludeEventIfItBelongsToTenant,
                                      subscriberId,
                                      Optional.empty());
    }

    /**
     * Honours the acknowledgement: a gap fill's transient gap is resolved when the subscriber acknowledges the event, not
     * once a poll emitted it - see {@link GapFillsAwaitingAcknowledgement}
     */
    @Override
    public Flux<PersistedEvent> unboundedPollForEvents(AggregateType aggregateType,
                                                       long fromInclusiveGlobalOrder,
                                                       Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                                       Optional<Duration> pollingInterval,
                                                       Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                                       Optional<SubscriberId> subscriberId,
                                                       SubscriberAcknowledgement acknowledgement) {
        return unboundedPollForEvents(aggregateType,
                                      fromInclusiveGlobalOrder,
                                      loadEventsByGlobalOrderBatchSize,
                                      pollingInterval,
                                      onlyIncludeEventIfItBelongsToTenant,
                                      subscriberId,
                                      Optional.of(requireNonNull(acknowledgement, "No acknowledgement provided")));
    }

    private Flux<PersistedEvent> unboundedPollForEvents(AggregateType aggregateType,
                                                        long fromInclusiveGlobalOrder,
                                                        Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                                        Optional<Duration> pollingInterval,
                                                        Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                                        Optional<SubscriberId> subscriberId,
                                                        Optional<SubscriberAcknowledgement> acknowledgement) {
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

        // One per subscription (each subscribe gets its own), as in pollEvents
        BiFunction<Optional<GapFillsAwaitingAcknowledgement>, Optional<GapMiddlesAwaitedInMemory>, Flux<PersistedEvent>> pollingWith = (awaitingAcknowledgement, gapMiddlesAwaitedInMemory) -> {
            var persistedEventsFlux = Flux.defer(() -> {
                // The first poll runs on the subscribing thread, which may already be inside a UnitOfWork. The poll then
                // joins it, and must leave ending it to its owner.
                var                  startedUnitOfWork = unitOfWorkFactory.getCurrentUnitOfWork().isEmpty();
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
                        commitIfStartedByThisPoll(unitOfWork, startedUnitOfWork);
                        unitOfWork = null;
                        return Flux.empty();
                    } else {
                        lastBatchSizeForThisQuery.set(batchSizeForThisQuery);
                    }

                    // After an empty poll: straight over a hole at the read position, or nothing to read in the range at all
                    var rangeWithEvents  = rangeToRead(unitOfWork, aggregateType, nextFromInclusiveGlobalOrder.get(), batchSizeForThisQuery, batchFetchSize, consecutiveNoPersistedEventsReturned.get(), eventStreamLogName);
                    var globalOrderRange = rangeWithEvents.orElseGet(() -> LongRange.from(nextFromInclusiveGlobalOrder.get(), batchSizeForThisQuery));
                    var transientGapsToIncludeInQuery = subscriptionGapHandler.map(gapHandler -> findTransientGapsToIncludeInQuery(gapHandler, aggregateType, globalOrderRange))
                                                                              .orElse(null);

                    var loadEventsByGlobalOrderTiming = StopWatch.start("loadEventsByGlobalOrder(" + actualSubscriberId + ", " + aggregateType + ")");
                    // Every tenant's events: see loadEventsForPoll. Plus those committed late in the middle of a wide gap
                    var gapMiddleFills = loadGapMiddleFills(gapMiddlesAwaitedInMemory, unitOfWork, aggregateType, batchFetchSize, onlyIncludeEventIfItBelongsToTenant, eventStreamLogName);
                    var loadedEvents   = withGapMiddleFills(gapMiddleFills, loadEventsForPoll(aggregateType, rangeWithEvents, globalOrderRange, transientGapsToIncludeInQuery, onlyIncludeEventIfItBelongsToTenant));
                    // Without the gap fills handed on before and not acknowledged yet: their gap is open, so they are read again
                    var tenantFilter    = tenantFilter(aggregateType, onlyIncludeEventIfItBelongsToTenant);
                    var persistedEvents = notAwaitingAcknowledgement(eventsBelongingToTenant(loadedEvents, tenantFilter), awaitingAcknowledgement);
                    eventStoreSubscriptionObserver.eventStorePolled(actualSubscriberId,
                                                                    aggregateType,
                                                                    globalOrderRange,
                                                                    transientGapsToIncludeInQuery,
                                                                    onlyIncludeEventIfItBelongsToTenant,
                                                                    persistedEvents,
                                                                    loadEventsByGlobalOrderTiming.stop().getDuration());
                    // The gaps filled by events about to be emitted stay open until they have been - see gapFillsAmong
                    var gapFillsToEmit = gapFillsAmong(persistedEvents, gapsReadAgain(transientGapsToIncludeInQuery, gapMiddleFills));
                    var gapReconciliation = subscriptionGapHandler.map(gapHandler -> {
                        var reconcileGapsTiming = StopWatch.start("reconcileGaps(" + actualSubscriberId + ", " + aggregateType + ")");
                        var outcome = reconcileGaps(gapHandler,
                                                    aggregateType,
                                                    globalOrderRange,
                                                    eventsToReconcile(loadedEvents, awaitingAcknowledgement),
                                                    gapsResolvedBeforePublishing(transientGapsToIncludeInQuery, gapFillsToEmit, awaitingAcknowledgement));
                        eventStoreSubscriptionObserver.reconciledGaps(actualSubscriberId,
                                                                      aggregateType,
                                                                      globalOrderRange,
                                                                      transientGapsToIncludeInQuery, loadedEvents,
                                                                      reconcileGapsTiming.stop().getDuration());
                        return outcome;
                    }).orElse(GapReconciliation.NONE);
                    commitIfStartedByThisPoll(unitOfWork, startedUnitOfWork);
                    unitOfWork = null;
                    // After the commit, so a reconciliation that rolls back is not counted.
                    if (!gapReconciliation.isEmpty()) {
                        eventStoreSubscriptionObserver.gapReconciliationOutcome(actualSubscriberId, aggregateType, gapReconciliation);
                    }
                    // Past every event loaded - including other tenants' events, which are not emitted - once all were emitted
                    var nextGlobalOrderAfterThisPoll = nextGlobalOrderAfter(loadedEvents);
                    if (loadedEvents.size() > 0) {
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

                    // Once the reconciliation committed: the middles of the wide gaps it found, and the middle fills read
                    trackGapMiddles(gapMiddlesAwaitedInMemory, globalOrderRange, loadedEvents, tenantFilter, eventStreamLogName);
                    // Right before they are emitted - once the reconciliation committed - so the subscriber can acknowledge them
                    awaitingAcknowledgement.ifPresent(awaiting -> awaiting.awaitAcknowledgement(gapFillsToEmit));
                    var emitted = Flux.fromIterable(persistedEvents);
                    if (awaitingAcknowledgement.isEmpty() && subscriptionGapHandler.isPresent() && !gapFillsToEmit.isEmpty()) {
                        // Subscribed only once every event was emitted, and not at all when the subscriber cancels first:
                        // then the gaps stay open - and the middle fills awaited - and the next subscription asks for them again
                        var gapHandler = subscriptionGapHandler.get();
                        emitted = emitted.concatWith(Mono.fromRunnable(() -> {
                            gapMiddlesAwaitedInMemory.ifPresent(middles -> middles.handled(gapFillsToEmit));
                            resolveGapsFilledByPublishedEvents(gapHandler, actualSubscriberId, aggregateType, gapFillsToEmit, eventStreamLogName);
                        }));
                    }
                    return emitted.doOnComplete(() -> nextFromInclusiveGlobalOrder.accumulateAndGet(nextGlobalOrderAfterThisPoll, Math::max));
                } catch (RuntimeException e) {
                    log.error(msg("[{}] Polling failed", eventStreamLogName), e);
                    if (unitOfWork != null) {
                        rollbackIfStartedByThisPoll(unitOfWork, startedUnitOfWork, e, eventStreamLogName);
                        unitOfWork = null;
                    }
                    eventStoreStreamLog.error(msg("[{}] Returning Error for '{}' EventStream with nextFromInclusiveGlobalOrder {}",
                                                  eventStreamLogName,
                                                  aggregateType,
                                                  nextFromInclusiveGlobalOrder.get()),
                                              e);
                    return Flux.error(e);
                } finally {
                    // Safety net for any exit that ended neither way - an Error, or a future early return. A UnitOfWork
                    // left open here outlives the poll: if the subscription is disposed before the next poll picks it up,
                    // it holds its connection and a lock on the event table for the life of the process.
                    if (unitOfWork != null) {
                        rollbackIfStartedByThisPoll(unitOfWork, startedUnitOfWork, null, eventStreamLogName);
                    }
                }
            }).doOnNext(event -> {
                // Never backwards: an event filling a gap lies below the read position, and moving back to it would deliver
                // every event above it again
                final long nextGlobalOrder = event.globalEventOrder().longValue() + 1L;
                eventStoreStreamLog.trace("[{}] Updating nextFromInclusiveGlobalOrder from {} to at least {}",
                                          eventStreamLogName,
                                          nextFromInclusiveGlobalOrder.get(),
                                          nextGlobalOrder);
                nextFromInclusiveGlobalOrder.accumulateAndGet(nextGlobalOrder, Math::max);
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

            var polling = persistedEventsFlux
                    .repeatWhen(longFlux -> Flux.interval(pollingInterval.orElse(Duration.ofMillis(DEFAULT_POLLING_INTERVAL_MILLISECONDS)))
                                                .onBackpressureDrop()
                                                .publishOn(Schedulers.newSingle("Publish-" + subscriberId.orElse(NO_SUBSCRIBER_ID) + "-" + aggregateType, true)));
            return polling;
        };
        // Registered when subscribed, before the first poll
        return registeredWithAcknowledgement(acknowledgement, subscriptionGapHandler, aggregateType, eventStreamLogName, nextFromInclusiveGlobalOrder, pollingWith);
    }

    /**
     * The polling flux {@code pollingWith} builds - registered with the subscriber's acknowledgement, if there is one, on
     * every subscribe and before anything is polled, so the subscriber can acknowledge whatever it is handed. Every
     * subscribe gets its own gap fills awaiting acknowledgement; a subscribe of the flux again once the previous one ended
     * ({@code retry()}, {@code repeat()}) replaces the previous registration - see {@link AcknowledgementRegistrations}.
     * <p>
     * Every subscribe also takes over the middles of wide gaps the subscriber's earlier subscribes - of this flux or of
     * another one, such as the one a resume after a {@code SubscriptionErrorPolicy} stop replaces - still await below the
     * read position it starts at, and leaves what it still awaits for the next once it ends (see
     * {@link GapMiddlesAwaitedAcrossSubscribes}).
     *
     * @param readPosition where the flux reads from next - where a subscribe starts reading
     */
    private Flux<PersistedEvent> registeredWithAcknowledgement(Optional<SubscriberAcknowledgement> acknowledgement,
                                                               Optional<SubscriptionGapHandler> subscriptionGapHandler,
                                                               AggregateType aggregateType,
                                                               String eventStreamLogName,
                                                               AtomicLong readPosition,
                                                               BiFunction<Optional<GapFillsAwaitingAcknowledgement>, Optional<GapMiddlesAwaitedInMemory>, Flux<PersistedEvent>> pollingWith) {
        if (acknowledgement.isEmpty()) {
            return Flux.defer(() -> {
                var gapMiddlesAwaitedInMemory = gapMiddlesAwaitedBy(subscriptionGapHandler, aggregateType, readPosition.get());
                return handingOnGapMiddlesWhenEnded(pollingWith.apply(Optional.empty(), gapMiddlesAwaitedInMemory), gapMiddlesAwaitedInMemory);
            });
        }
        return new AcknowledgementRegistrations(acknowledgement.get()).registeredOnEverySubscribe(() -> {
            var gapMiddlesAwaitedInMemory = gapMiddlesAwaitedBy(subscriptionGapHandler, aggregateType, readPosition.get());
            var awaitingAcknowledgement   = awaitingAcknowledgement(acknowledgement, subscriptionGapHandler, aggregateType, eventStreamLogName);
            return new AcknowledgementRegistrations.PerSubscribe(acknowledgementListener(awaitingAcknowledgement, gapMiddlesAwaitedInMemory),
                                                                 handingOnGapMiddlesWhenEnded(pollingWith.apply(awaitingAcknowledgement, gapMiddlesAwaitedInMemory), gapMiddlesAwaitedInMemory));
        });
    }

    /**
     * @return the middles of wide gaps one subscribe of a subscription with a gap handler awaits in memory only - starting
     * with those the subscriber's earlier subscribes still await below {@code fromInclusive}, where it starts reading
     */
    private Optional<GapMiddlesAwaitedInMemory> gapMiddlesAwaitedBy(Optional<SubscriptionGapHandler> subscriptionGapHandler, AggregateType aggregateType, long fromInclusive) {
        return subscriptionGapHandler.map(gapHandler -> gapMiddlesAwaitedAcrossSubscribes.subscribe(gapHandler.subscriberId(),
                                                                                                   aggregateType,
                                                                                                   fromInclusive,
                                                                                                   GapMiddlesAwaitedInMemory.timeoutFor(gapHandler)));
    }

    /**
     * {@code events}, leaving the middles the subscribe still awaits for the subscriber's next subscribe once it ends -
     * when it is cancelled, which a stop, a resume, a fenced-lock release does, synchronously, before the subscription
     * subscribes again
     */
    private Flux<PersistedEvent> handingOnGapMiddlesWhenEnded(Flux<PersistedEvent> events, Optional<GapMiddlesAwaitedInMemory> gapMiddlesAwaitedInMemory) {
        return gapMiddlesAwaitedInMemory.map(middles -> events.doFinally(signal -> gapMiddlesAwaitedAcrossSubscribes.subscribeEnded(middles)))
                                        .orElse(events);
    }

    /**
     * Ends a poll's {@link EventStoreUnitOfWork} successfully - but only if the poll started it. A unit of work the poll
     * joined belongs to whoever started it, and committing it here would end their transaction behind their back.
     */
    private static void commitIfStartedByThisPoll(EventStoreUnitOfWork unitOfWork, boolean startedByThisPoll) {
        if (startedByThisPoll) {
            unitOfWork.commit();
        }
    }

    /**
     * Ends a poll's {@link EventStoreUnitOfWork} unsuccessfully. A unit of work the poll started is rolled back; one it
     * joined is only marked rollback-only, leaving the rollback to its owner - the same rule
     * {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkFactory#usingUnitOfWork} follows.
     */
    private void rollbackIfStartedByThisPoll(EventStoreUnitOfWork unitOfWork, boolean startedByThisPoll, Throwable cause, String eventStreamLogName) {
        try {
            if (startedByThisPoll) {
                unitOfWork.rollback(cause);
            } else {
                unitOfWork.markAsRollbackOnly(cause);
            }
        } catch (Exception rollbackException) {
            log.error(msg("[{}] Failed to rollback unit of work", eventStreamLogName), rollbackException);
        }
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
                    batchSizeForThisQuery = grownBatchSize(batchSizeForThisQuery, defaultBatchFetchSize * (currentConsecutiveNoPersistedEventsReturned / 100) * 1.0f);
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
            batchSizeForThisQuery = grownBatchSize(batchSizeForThisQuery, defaultBatchFetchSize * (currentConsecutiveNoPersistedEventsReturned / 10) * 0.5f);
            if (batchSizeForThisQuery > defaultBatchFetchSize) {
                eventStoreStreamLog.debug("[{}] loadEventsByGlobalOrder temporarily INCREASED query batchSize to {} from {} instead of default {} since number of consecutiveNoPersistedEventsReturned was {}",
                                          eventStreamLogName,
                                          batchSizeForThisQuery,
                                          lastBatchSizeForThisQuery,
                                          defaultBatchFetchSize,
                                          currentConsecutiveNoPersistedEventsReturned);
            }
        } else if (currentConsecutiveNoPersistedEventsReturned > 0) {
            // A hole at the read position - global orders that will never be committed, such as rolled-back appends - is
            // only passed once the range covers it. The first empty polls therefore double the range, so a hole is passed
            // within a few polls rather than after the slow growth every 10th and 100th empty poll brings. An idle
            // subscriber pays for it with an empty range scan of a few more global orders
            var maxBatchSizeWhenDoubling = Math.max(defaultBatchFetchSize * 10, 100);
            if (batchSizeForThisQuery < maxBatchSizeWhenDoubling) {
                batchSizeForThisQuery = Math.min(maxBatchSizeWhenDoubling, grownBatchSize(batchSizeForThisQuery, batchSizeForThisQuery));
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

    /**
     * {@code batchSize} plus {@code growth} - but by at least one: a growth that rounds down to nothing (a batch size
     * of 1 grown by 0.5) would otherwise leave the batch size, and a hole of that size at the read position, unchanged
     */
    private static long grownBatchSize(long batchSize, float growth) {
        return Math.max(batchSize + 1, (long) (batchSize + growth));
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
        private final EventStorePollingOptimizer       pollingOptimizer;
        /**
         * Present when the subscriber acknowledges what it handled - see {@link GapFillsAwaitingAcknowledgement}
         */
        private final Optional<GapFillsAwaitingAcknowledgement> awaitingAcknowledgement;
        /**
         * Present when the subscription has a gap handler - see {@link GapMiddlesAwaitedInMemory}
         */
        private final Optional<GapMiddlesAwaitedInMemory>       gapMiddlesAwaitedInMemory;
        /**
         * Whether the latest {@link #pollForEvents} read any event, whether or not it belonged to the subscriber's tenant.
         * Only touched by the thread running this task.
         */
        private       boolean                          lastPollConsumedEvents;

        // private, not public: PollEventStoreTask is itself a private inner class, so a public constructor was
        // reachable by nobody and only served to trip the construction-ergonomics ceiling. Narrowing it is not an
        // API change — a builder here would have invented public surface for a type that has none.
        private PollEventStoreTask(long demandForEvents,
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
                                  SubscriberId subscriberId,
                                  EventStorePollingOptimizer pollingOptimizer,
                                  Optional<GapFillsAwaitingAcknowledgement> awaitingAcknowledgement,
                                  Optional<GapMiddlesAwaitedInMemory> gapMiddlesAwaitedInMemory) {
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
            this.pollingOptimizer = pollingOptimizer;
            this.awaitingAcknowledgement = awaitingAcknowledgement;
            this.gapMiddlesAwaitedInMemory = gapMiddlesAwaitedInMemory;
        }

        @Override
        public void run() {
            eventStoreStreamLog.debug("[{}] Polling worker - Started with initial demand for events {}",
                                      eventStreamLogName,
                                      demandForEvents);
            var pollingSleep             = pollingInterval.orElse(Duration.ofMillis(DEFAULT_POLLING_INTERVAL_MILLISECONDS)).toMillis();
            var remainingDemandForEvents = demandForEvents;

            while (remainingDemandForEvents > 0 && !sink.isCancelled() && !Thread.currentThread().isInterrupted()) {

                var numberOfEventsPublished = pollForEvents(remainingDemandForEvents);
                remainingDemandForEvents -= numberOfEventsPublished;
                eventStoreStreamLog.trace("[{}] Polling worker published {} event(s) - Outstanding demand for events {}",
                                          eventStreamLogName,
                                          numberOfEventsPublished,
                                          remainingDemandForEvents);
                // A poll that only moved past other tenants' events published nothing, but there may be more to read
                if (numberOfEventsPublished == 0 && !lastPollConsumedEvents) {
                    pollingOptimizer.eventStorePollingReturnedNoEvents();
                    eventStoreStreamLog.trace("[{}] Skipping polling cycle based on optimizer", eventStreamLogName);
                    // A zero delay leaves nothing between one empty poll and the next - wait the polling interval, unless the optimizer opted out
                    var delayMs = pollingOptimizer.currentDelayMs();
                    if (delayMs <= 0 && !pollingOptimizer.mayRepollImmediatelyAfterAnEmptyPoll()) {
                        delayMs = pollingSleep;
                    }
                    if (delayMs > 0) {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException e) {
                            // Restore the flag - the loop condition then ends this worker rather than polling without sleeping
                            Thread.currentThread().interrupt();
                        }
                    }
                } else {
                    pollingOptimizer.eventStorePollingReturnedEvents();
                }
            }
            eventStoreStreamLog.debug("[{}] Polling worker - Completed with remaining demand for events {}. Is Cancelled: {}",
                                      eventStreamLogName,
                                      remainingDemandForEvents,
                                      sink.isCancelled());
            if (!sink.isCancelled() && Thread.currentThread().isInterrupted()) {
                // Not by a cancel (which marks the sink cancelled before it interrupts), so nothing else ends the flux: the
                // subscriber would wait for the events it requested, and no poll would run on this thread again. End it, so
                // the stop is seen - with an error rather than a completion, as the subscriber was not handed what it asked for
                log.warn("[{}] Polling worker - Its thread was interrupted with {} event(s) still demanded, and the subscription was not cancelled. " +
                         "Ending the event stream with an error, as no further poll would run. Event handling on the polling thread must not interrupt it, nor restore an interrupt",
                         eventStreamLogName,
                         remainingDemandForEvents);
                sink.error(new InterruptedException(msg("[{}] The polling worker's thread was interrupted", eventStreamLogName)));
            }
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
            lastPollConsumedEvents = false;
            var                  startedUnitOfWork = unitOfWorkFactory.getCurrentUnitOfWork().isEmpty();
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
                    commitIfStartedByThisPoll(unitOfWork, startedUnitOfWork);
                    unitOfWork = null;
                    return 0;
                } else {
                    lastBatchSizeForThisQuery.set(batchSizeForThisQuery);
                    eventStoreStreamLog.trace("[{}] Polling worker - Using batchSizeForThisQuery: {}",
                                              eventStreamLogName,
                                              batchSizeForThisQuery);
                }

                // After an empty poll: straight over a hole at the read position, or nothing to read in the range at all
                var rangeWithEvents  = rangeToRead(unitOfWork, aggregateType, nextFromInclusiveGlobalOrder.get(), batchSizeForThisQuery, Math.min(batchFetchSize, remainingDemandForEvents), consecutiveNoPersistedEventsReturned.get(), eventStreamLogName);
                var globalOrderRange = rangeWithEvents.orElseGet(() -> LongRange.from(nextFromInclusiveGlobalOrder.get(), batchSizeForThisQuery));
                var transientGapsToIncludeInQuery = subscriptionGapHandler.map(gapHandler -> findTransientGapsToIncludeInQuery(gapHandler, aggregateType, globalOrderRange))
                                                                          .orElse(null);

                var loadEventsByGlobalOrderTiming = StopWatch.start("loadEventsByGlobalOrder(" + subscriberId + ", " + aggregateType + ")");
                // Every tenant's events: see loadEventsForPoll
                // Plus the events committed late in the middle of a wide gap, at most a batch of them
                var gapMiddleFills = loadGapMiddleFills(gapMiddlesAwaitedInMemory, unitOfWork, aggregateType, batchFetchSize, onlyIncludeEventIfItBelongsToTenant, eventStreamLogName);
                var loadedEvents   = withGapMiddleFills(gapMiddleFills, loadEventsForPoll(aggregateType, rangeWithEvents, globalOrderRange, transientGapsToIncludeInQuery, onlyIncludeEventIfItBelongsToTenant));
                var tenantFilter = tenantFilter(aggregateType, onlyIncludeEventIfItBelongsToTenant);
                eventStoreSubscriptionObserver.eventStorePolled(subscriberId,
                                                                aggregateType,
                                                                globalOrderRange,
                                                                transientGapsToIncludeInQuery,
                                                                onlyIncludeEventIfItBelongsToTenant,
                                                                eventsBelongingToTenant(loadedEvents, tenantFilter),
                                                                loadEventsByGlobalOrderTiming.stop().getDuration());

                // No more than demanded is published, and this poll only consumes - reconciles, and moves the read
                // position past - the events up to the last one it publishes. The rest are read again by the next poll:
                // the range events from the read position, and the gap fills (lowest, so first in the result) because
                // they are still transient gaps - resolving one that is then not published would lose its event. A gap
                // fill handed on before and not acknowledged yet is read again too (its gap is open), and not published again
                var consumedEvents = eventsWithinDemand(loadedEvents, remainingDemandForEvents, tenantFilter);
                var eventsToPublish = notAwaitingAcknowledgement(eventsBelongingToTenant(consumedEvents, tenantFilter), awaitingAcknowledgement);
                if (consumedEvents.size() < loadedEvents.size()) {
                    eventStoreStreamLog.debug("[{}] Polling worker - Loaded {} event(s), but will only publish {} event(s), as this matches the remainingDemandForEvents {}",
                                              eventStreamLogName,
                                              loadedEvents.size(),
                                              eventsToPublish.size(),
                                              remainingDemandForEvents);
                }
                // The gaps filled by events about to be published stay open until they have been - see gapFillsAmong
                var gapFillsToPublish = gapFillsAmong(eventsToPublish, gapsReadAgain(transientGapsToIncludeInQuery, gapMiddleFills));

                var gapReconciliation = subscriptionGapHandler.map(gapHandler -> {
                    var reconcileGapsTiming = StopWatch.start("reconcileGaps(" + subscriberId + ", " + aggregateType + ")");
                    var outcome = reconcileGaps(gapHandler,
                                                aggregateType,
                                                globalOrderRange,
                                                eventsToReconcile(consumedEvents, awaitingAcknowledgement),
                                                gapsResolvedBeforePublishing(transientGapsToIncludeInQuery, gapFillsToPublish, awaitingAcknowledgement));
                    eventStoreSubscriptionObserver.reconciledGaps(subscriberId,
                                                                  aggregateType,
                                                                  globalOrderRange,
                                                                  transientGapsToIncludeInQuery, consumedEvents,
                                                                  reconcileGapsTiming.stop().getDuration());
                    return outcome;
                }).orElse(GapReconciliation.NONE);
                commitIfStartedByThisPoll(unitOfWork, startedUnitOfWork);
                unitOfWork = null;
                // After the commit, so a reconciliation that rolls back is not counted.
                if (!gapReconciliation.isEmpty()) {
                    eventStoreSubscriptionObserver.gapReconciliationOutcome(subscriberId, aggregateType, gapReconciliation);
                }
                if (!loadedEvents.isEmpty()) {
                    consecutiveNoPersistedEventsReturned.set(0);
                    // Not by reading the gap fills still awaiting acknowledgement again - that moves nothing forward
                    lastPollConsumedEvents = consumedEvents.stream().anyMatch(event -> !isAwaitingAcknowledgement(event));
                    if (log.isTraceEnabled()) {
                        eventStoreStreamLog.debug("[{}] Polling worker - loadEventsByGlobalOrder using globalOrderRange {} and transientGapsToIncludeInQuery {} returned {} events: {}",
                                                  eventStreamLogName,
                                                  globalOrderRange,
                                                  transientGapsToIncludeInQuery,
                                                  loadedEvents.size(),
                                                  loadedEvents.stream().map(PersistedEvent::globalEventOrder).collect(Collectors.toList()));
                    } else {
                        eventStoreStreamLog.debug("[{}] Polling worker - loadEventsByGlobalOrder using globalOrderRange {} and transientGapsToIncludeInQuery {} returned {} events",
                                                  eventStreamLogName,
                                                  globalOrderRange,
                                                  transientGapsToIncludeInQuery,
                                                  loadedEvents.size());
                    }

                    // Right before they are published - once the reconciliation committed - so the subscriber can acknowledge them
                    // Only the consumed events: a middle fill beyond the demand stays awaited, and is read again
                    trackGapMiddles(gapMiddlesAwaitedInMemory, globalOrderRange, consumedEvents, tenantFilter, eventStreamLogName);
                    awaitingAcknowledgement.ifPresent(awaiting -> awaiting.awaitAcknowledgement(gapFillsToPublish));
                    for (int index = 0; index < eventsToPublish.size(); index++) {
                        if (sink.isCancelled()) {
                            // The gaps its gap fills fill stay open: the next subscription asks for them again
                            eventStoreStreamLog.debug("[{}] Polling worker - Is Cancelled: true. Skipping publishing further events (has only published {} out of the planned {} events)",
                                                      eventStreamLogName,
                                                      index,
                                                      eventsToPublish.size());
                            return index;
                        }
                        publishEventToSink(eventsToPublish.get(index));
                    }
                    if (awaitingAcknowledgement.isPresent()) {
                        // Resolved as the subscriber acknowledges them
                    } else if (!sink.isCancelled()) {
                        gapMiddlesAwaitedInMemory.ifPresent(middles -> middles.handled(gapFillsToPublish));
                        subscriptionGapHandler.ifPresent(gapHandler -> resolveGapsFilledByPublishedEvents(gapHandler, subscriberId, aggregateType, gapFillsToPublish, eventStreamLogName));
                    } else if (!gapFillsToPublish.isEmpty()) {
                        eventStoreStreamLog.debug("[{}] Polling worker - Is Cancelled: true. Leaving the gaps filled by {} open - the subscriber may not have handled them",
                                                  eventStreamLogName,
                                                  gapFillsToPublish.stream().map(PersistedEvent::globalEventOrder).toList());
                    }
                    // Also past the consumed events of other tenants, which were not published
                    nextFromInclusiveGlobalOrder.accumulateAndGet(nextGlobalOrderAfter(consumedEvents), Math::max);
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
                    eventStoreStreamLog.debug("[{}] Polling worker - rolling back UnitOfWork due to error during polling",
                                              eventStreamLogName);
                    rollbackIfStartedByThisPoll(unitOfWork, startedUnitOfWork, e, eventStreamLogName);
                    unitOfWork = null;
                }
                eventStoreStreamLog.error(msg("[{}] Polling worker - Returning Error for '{}' EventStream with nextFromInclusiveGlobalOrder {}",
                                              eventStreamLogName,
                                              aggregateType,
                                              nextFromInclusiveGlobalOrder.get()),
                                          e);
                return 0;
            } finally {
                // Safety net for any exit that ended neither way - an Error, or a future early return. A UnitOfWork
                // left open here outlives the poll: if the subscription is disposed before the next poll picks it up,
                // it holds its connection and a lock on the event table for the life of the process.
                if (unitOfWork != null) {
                    rollbackIfStartedByThisPoll(unitOfWork, startedUnitOfWork, null, eventStreamLogName);
                }
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
            // Never backwards: an event filling a gap lies below the read position, and moving back to it would deliver
            // every event above it again
            var nextGlobalOrder = persistedEvent.globalEventOrder().longValue() + 1L;
            eventStoreStreamLog.trace("[{}] Polling worker - Updating nextFromInclusiveGlobalOrder from {} to at least {}",
                                      eventStreamLogName,
                                      nextFromInclusiveGlobalOrder.get(),
                                      nextGlobalOrder);
            nextFromInclusiveGlobalOrder.accumulateAndGet(nextGlobalOrder, Math::max);
        }

        private boolean isAwaitingAcknowledgement(PersistedEvent event) {
            return awaitingAcknowledgement.isPresent() && awaitingAcknowledgement.get().isAwaiting(event);
        }

        /**
         * The leading events of a poll's result that fit within the demand: up to, and not including, the first event of
         * the subscriber's tenant beyond it. Other tenants' events in between are consumed too - not published, but read -
         * and so are the gap fills still awaiting acknowledgement, which are not published again.
         */
        private List<PersistedEvent> eventsWithinDemand(List<PersistedEvent> loadedEvents, long remainingDemandForEvents, Optional<Predicate<PersistedEvent>> tenantFilter) {
            var belongsToTenant = tenantFilter.orElse(event -> true);
            var published       = 0L;
            for (var index = 0; index < loadedEvents.size(); index++) {
                if (belongsToTenant.test(loadedEvents.get(index)) && !isAwaitingAcknowledgement(loadedEvents.get(index))) {
                    if (published == remainingDemandForEvents) {
                        return loadedEvents.subList(0, index);
                    }
                    published++;
                }
            }
            return loadedEvents;
        }
    }

    /**
     * The events a poll reads: the global order range and the transient gaps asked for again, for <b>every</b> tenant.
     * A tenant-filtered subscription filters them in memory, after the gap handler has seen them: filtered in SQL,
     * other tenants' events would be missing global orders, recorded as transient gaps and later promoted to permanent
     * ones - and the read position could not move past them. The other tenants' rows are still read, but without their
     * payload and metadata (see {@link LoadEventsByGlobalOrder#getOnlyLoadPayloadIfEventBelongsToTenant()}), so only
     * their global order, tenant and a few small columns are transferred; they are never deserialized, nor published.
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcEventStore}
     * loads every tenant for the same reason.
     * <p>
     * Nothing at all when {@link #rangeToRead} found nothing persisted at or above the read position and no transient
     * gap is asked for again.
     *
     * @param rangeWithEvents what {@link #rangeToRead} returned
     */
    private List<PersistedEvent> loadEventsForPoll(AggregateType aggregateType,
                                                   Optional<LongRange> rangeWithEvents,
                                                   LongRange globalOrderRange,
                                                   List<GlobalEventOrder> transientGapsToIncludeInQuery,
                                                   Optional<Tenant> onlyIncludeEventIfItBelongsToTenant) {
        if (rangeWithEvents.isEmpty() && (transientGapsToIncludeInQuery == null || transientGapsToIncludeInQuery.isEmpty())) {
            // Nothing is persisted at or above the read position, and no transient gap is asked for: the lookup that
            // found that replaced the range query
            return List.of();
        }
        return loadEventsForPoll(aggregateType, globalOrderRange, transientGapsToIncludeInQuery, onlyIncludeEventIfItBelongsToTenant);
    }

    /**
     * The global order range a poll reads. Right after a poll that read nothing, one indexed lookup of the lowest global
     * order persisted at or above the read position
     * ({@link AggregateEventStreamPersistenceStrategy#findLowestGlobalEventOrderPersisted(EventStoreUnitOfWork, AggregateType, LongRange)})
     * takes the place of reading the range:
     * <ul>
     *     <li>none - the stream is idle at its head: empty, and the poll reads no range (only the transient gaps it asks
     *     for again, if any). An idle subscription so costs one index lookup per poll, as the range query it replaces did;</li>
     *     <li>one above the range - a hole at the read position, such as a sequence moved forward ({@code setval}, a
     *     restore): the range is widened straight to it plus {@code batchSize}, so the hole is passed - and reconciled,
     *     bounded (see {@link GapEnds}) - by this poll. It used to be passed only after the range had grown over it on
     *     empty polls: with the default settings a hole of a million orders took hours. The range holds at most
     *     {@code batchSize} events, as nothing lies below the order found;</li>
     *     <li>one within the range - the range as resolved.</li>
     * </ul>
     * A poll after one that read events reads its range without the lookup: while events keep coming nothing is added.
     *
     * @return the range to read, or empty when nothing is persisted at or above {@code nextFromInclusiveGlobalOrder}
     */
    private Optional<LongRange> rangeToRead(EventStoreUnitOfWork unitOfWork,
                                            AggregateType aggregateType,
                                            long nextFromInclusiveGlobalOrder,
                                            long batchSizeForThisQuery,
                                            long batchSize,
                                            int consecutiveNoPersistedEventsReturned,
                                            String eventStreamLogName) {
        var range = LongRange.from(nextFromInclusiveGlobalOrder, batchSizeForThisQuery);
        if (consecutiveNoPersistedEventsReturned == 0) {
            return Optional.of(range);
        }
        var lowest = persistenceStrategy.findLowestGlobalEventOrderPersisted(unitOfWork, aggregateType, LongRange.from(nextFromInclusiveGlobalOrder));
        if (lowest.isEmpty()) {
            return Optional.empty();
        }
        long lowestOrder = lowest.get().longValue();
        if (lowestOrder <= range.getToInclusive()) {
            return Optional.of(range);
        }
        log.debug("[{}] The lowest global order persisted at or above {} is {}: widening this poll's range over the hole in between",
                  eventStreamLogName,
                  nextFromInclusiveGlobalOrder,
                  lowestOrder);
        return Optional.of(LongRange.between(nextFromInclusiveGlobalOrder, lowestOrder + Math.max(1, batchSize) - 1));
    }

    private List<PersistedEvent> loadEventsForPoll(AggregateType aggregateType,
                                                   LongRange globalOrderRange,
                                                   List<GlobalEventOrder> transientGapsToIncludeInQuery,
                                                   Optional<Tenant> onlyIncludeEventIfItBelongsToTenant) {
        var operation = new LoadEventsByGlobalOrder(aggregateType,
                                                    globalOrderRange,
                                                    transientGapsToIncludeInQuery,
                                                    null);
        onlyIncludeEventIfItBelongsToTenant.ifPresent(operation::setOnlyLoadPayloadIfEventBelongsToTenant);
        return loadEventsByGlobalOrder(operation).toList();
    }

    /**
     * The events that committed late in the middles of wide gaps a polling subscription awaits in memory only (see
     * {@link GapMiddlesAwaitedInMemory}), after dropping the middles whose timeout has passed - for every tenant, as
     * {@link #loadEventsForPoll} reads them.
     * <p>
     * One indexed lookup per awaited middle ({@link AggregateEventStreamPersistenceStrategy#findLowestGlobalEventOrderPersisted(EventStoreUnitOfWork, AggregateType, LongRange)})
     * finds the lowest event committed in it - in practice there is none, and nothing else is read. From the first middle
     * that has one, at most {@code maxEvents} orders are loaded, so a middle a large transaction filled is read a batch per
     * poll, never at once. Every order loaded is awaited, so none is handed on twice: one stays awaited until the
     * subscriber is done with it, and a poll that reads it again before then leaves it out (see {@link #trackGapMiddles}).
     *
     * @return the events found, lowest first - none when nothing is awaited
     */
    private List<PersistedEvent> loadGapMiddleFills(Optional<GapMiddlesAwaitedInMemory> gapMiddlesAwaitedInMemory,
                                                    EventStoreUnitOfWork unitOfWork,
                                                    AggregateType aggregateType,
                                                    long maxEvents,
                                                    Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                                    String eventStreamLogName) {
        // What ended subscribes left behind and no later subscribe took over is forgotten once it timed out
        gapMiddlesAwaitedAcrossSubscribes.forgetTimedOut();
        if (gapMiddlesAwaitedInMemory.isEmpty() || gapMiddlesAwaitedInMemory.get().isEmpty()) {
            return List.of();
        }
        var awaited = gapMiddlesAwaitedInMemory.get();
        var dropped = awaited.dropTimedOut();
        if (!dropped.isEmpty()) {
            log.debug("[{}] Stopped awaiting the middle(s) {} of wide gaps after {} - awaited in memory only, so nothing is recorded for them",
                      eventStreamLogName,
                      dropped,
                      awaited.timeout());
        }
        for (var middle : awaited.awaited()) {
            var lowest = persistenceStrategy.findLowestGlobalEventOrderPersisted(unitOfWork, aggregateType, middle)
                                            .map(GlobalEventOrder::longValue)
                                            .filter(order -> order >= middle.fromInclusive && order <= middle.getToInclusive());
            if (lowest.isPresent()) {
                long from = lowest.get();
                return loadEventsForPoll(aggregateType,
                                         LongRange.between(from, Math.min(middle.getToInclusive(), from + Math.max(1, maxEvents) - 1)),
                                         null,
                                         onlyIncludeEventIfItBelongsToTenant);
            }
        }
        return List.of();
    }

    /**
     * {@code loadedEvents} with the {@code gapMiddleFills} - in global order, each order once
     */
    private static List<PersistedEvent> withGapMiddleFills(List<PersistedEvent> gapMiddleFills, List<PersistedEvent> loadedEvents) {
        if (gapMiddleFills.isEmpty()) {
            return loadedEvents;
        }
        var byGlobalOrder = new TreeMap<Long, PersistedEvent>();
        gapMiddleFills.forEach(event -> byGlobalOrder.put(event.globalEventOrder().longValue(), event));
        loadedEvents.forEach(event -> byGlobalOrder.putIfAbsent(event.globalEventOrder().longValue(), event));
        return List.copyOf(byGlobalOrder.values());
    }

    /**
     * The gaps a poll read again: the transient gaps it asked for, and the orders of the middle fills it found - both are
     * delivered as gap fills (see {@link #gapFillsAmong})
     *
     * @param transientGapsIncludedInQuery may be null
     */
    private static List<GlobalEventOrder> gapsReadAgain(List<GlobalEventOrder> transientGapsIncludedInQuery, List<PersistedEvent> gapMiddleFills) {
        if (gapMiddleFills.isEmpty()) {
            return transientGapsIncludedInQuery;
        }
        var gaps = new ArrayList<GlobalEventOrder>();
        if (transientGapsIncludedInQuery != null) {
            gaps.addAll(transientGapsIncludedInQuery);
        }
        gapMiddleFills.forEach(event -> gaps.add(event.globalEventOrder()));
        return gaps;
    }

    /**
     * Once a poll's reconciliation committed, and before it hands anything on: every event it consumed that the subscriber
     * is not handed - another tenant's - stops being awaited in a middle, so it is never read as a middle fill again; and
     * the middles of the wide gaps its reconciliation found are awaited from now on. The reconciliation recorded only their
     * ends as transient gaps (see {@link GapEnds}).
     * <p>
     * A middle fill handed to the subscriber stays awaited until the subscriber is done with it, as the transient gap of
     * any other gap fill stays open: once it acknowledged it, or - when it does not acknowledge - once the poll handed it
     * on without being cancelled ({@link GapMiddlesAwaitedInMemory#handled}). This subscribe does not hand it on twice:
     * a later poll that reads it again leaves it out while it awaits acknowledgement, and without acknowledgement it was
     * done with before the next poll. A subscribe cancelled first leaves it awaited for the next subscribe, which hands it
     * on again.
     *
     * @param consumedEvents the events the poll consumed - the ones it gave the reconciliation, apart from the gap fills
     *                       awaiting acknowledgement
     * @param tenantFilter   which of them the subscriber is handed - see {@link #tenantFilter}
     */
    private static void trackGapMiddles(Optional<GapMiddlesAwaitedInMemory> gapMiddlesAwaitedInMemory,
                                        LongRange globalOrderRange,
                                        List<PersistedEvent> consumedEvents,
                                        Optional<Predicate<PersistedEvent>> tenantFilter,
                                        String eventStreamLogName) {
        if (gapMiddlesAwaitedInMemory.isEmpty() || consumedEvents.isEmpty()) {
            return;
        }
        var awaited = gapMiddlesAwaitedInMemory.get();
        tenantFilter.ifPresent(belongsToTenant -> consumedEvents.stream()
                                                                .filter(belongsToTenant.negate())
                                                                .forEach(event -> awaited.delivered(event.globalEventOrder().longValue())));
        var gapEnds = GapEnds.below(globalOrderRange.fromInclusive, consumedEvents.stream().mapToLong(event -> event.globalEventOrder().longValue()));
        for (var middle : gapEnds.awaitedInMemoryOnly()) {
            awaited.await(middle);
            long gapFrom = middle.fromInclusive - SubscriptionGapHandler.MAX_AWAITED_ORDERS_PER_GAP_END;
            long gapTo   = middle.getToInclusive() + SubscriptionGapHandler.MAX_AWAITED_ORDERS_PER_GAP_END;
            log.warn("[{}] Global order {} opened a gap of {} orders above {} - the global order sequence was moved forward, or a large append has not committed. " +
                             "Awaiting the {} lowest and the {} highest of them durably, and the {} in between ({}) in memory only: a restart within {} does not wait for those",
                     eventStreamLogName,
                     gapTo + 1,
                     gapTo - gapFrom + 1,
                     gapFrom - 1,
                     SubscriptionGapHandler.MAX_AWAITED_ORDERS_PER_GAP_END,
                     SubscriptionGapHandler.MAX_AWAITED_ORDERS_PER_GAP_END,
                     middle.getToInclusive() - middle.fromInclusive + 1,
                     middle,
                     awaited.timeout());
        }
    }

    /**
     * The events among {@code eventsToPublish} that fill one of the transient gaps the poll asked for again.
     * <p>
     * A poll resolves such a gap only once the subscriber is done with the event filling it - not when it reconciles the
     * poll's gaps, before handing anything on. The subscriber's resume point lies above a gap fill (it moved past the
     * higher events handled before the gap filled), so the transient gap is the only durable record that the fill is
     * still owed: resolved first, a subscription stopped - or a process that died - before the fill was handled resumed
     * above it and never asked for it again.
     * <ul>
     *     <li>A subscriber that acknowledges what it handled ({@link SubscriberAcknowledgement}) gets the fill's gap
     *     resolved when it acknowledges the fill, in its own unit of work - see {@link GapFillsAwaitingAcknowledgement}.
     *     That covers a subscriber that handles asynchronously: a batch collected, an I/O retry in its backoff, an event
     *     waiting for demand in a {@code limitRate} queue.</li>
     *     <li>For any other subscriber, done is handed on: publishing to the sink, after which the poll resolves the gaps
     *     of the fills it published. A poll that was cancelled before it handed all of its events on resolves none of
     *     its gap fills: the subscriber may not have handled them, and the next subscription asks for them again - at
     *     the cost of delivering a fill it did handle twice.</li>
     * </ul>
     * Other tenants' gap fills are not handed on, so the reconciliation resolves them as before.
     *
     * @param transientGapsIncludedInQuery the gaps the poll asked for again - may be null
     */
    private static List<PersistedEvent> gapFillsAmong(List<PersistedEvent> eventsToPublish, List<GlobalEventOrder> transientGapsIncludedInQuery) {
        if (transientGapsIncludedInQuery == null || transientGapsIncludedInQuery.isEmpty() || eventsToPublish.isEmpty()) {
            return List.of();
        }
        var gaps = new HashSet<>(transientGapsIncludedInQuery);
        return eventsToPublish.stream()
                              .filter(event -> gaps.contains(event.globalEventOrder()))
                              .toList();
    }

    /**
     * The transient gaps a poll's reconciliation is told it asked for: all but those filled by an event it is about to
     * publish, which stay open until the subscriber is done with it (see {@link #gapFillsAmong}), and those filled by a
     * gap fill handed on before and not acknowledged yet. The gap handler does not count a gap whose event it is given
     * as a new gap, and does not promote it either.
     */
    private static List<GlobalEventOrder> gapsResolvedBeforePublishing(List<GlobalEventOrder> transientGapsIncludedInQuery,
                                                                       List<PersistedEvent> gapFillsToPublish,
                                                                       Optional<GapFillsAwaitingAcknowledgement> awaitingAcknowledgement) {
        var stillAwaiting = awaitingAcknowledgement.map(GapFillsAwaitingAcknowledgement::awaitingEvents).orElse(List.of());
        if (gapFillsToPublish.isEmpty() && stillAwaiting.isEmpty()) {
            return transientGapsIncludedInQuery;
        }
        var leftOpen = new HashSet<GlobalEventOrder>();
        gapFillsToPublish.forEach(event -> leftOpen.add(event.globalEventOrder()));
        stillAwaiting.forEach(event -> leftOpen.add(event.globalEventOrder()));
        return transientGapsIncludedInQuery.stream()
                                           .filter(gap -> !leftOpen.contains(gap))
                                           .toList();
    }

    /**
     * The events a poll's reconciliation is given: those it read, plus the gap fills handed on before and still awaiting
     * acknowledgement that it did not read again - the gap handler promotes no gap whose event it is given, and the gap
     * of an unacknowledged fill must stay open. They lie below the poll's range, so they are no new gaps either.
     */
    private static List<PersistedEvent> eventsToReconcile(List<PersistedEvent> events, Optional<GapFillsAwaitingAcknowledgement> awaitingAcknowledgement) {
        return awaitingAcknowledgement.map(awaiting -> awaiting.withAwaitingEvents(events)).orElse(events);
    }

    /**
     * {@code events} without the gap fills handed on before and not acknowledged yet - a poll reads them again while
     * their gap is open, and must not hand them on again
     */
    private static List<PersistedEvent> notAwaitingAcknowledgement(List<PersistedEvent> events, Optional<GapFillsAwaitingAcknowledgement> awaitingAcknowledgement) {
        if (awaitingAcknowledgement.isEmpty() || events.isEmpty()) {
            return events;
        }
        var awaiting = awaitingAcknowledgement.get();
        return events.stream()
                     .filter(event -> !awaiting.isAwaiting(event))
                     .toList();
    }

    /**
     * Calls to a subscription's gap handler hold its monitor: an acknowledging subscriber resolves gaps on its own thread
     * (see {@link GapFillsAwaitingAcknowledgement}). Never held across a commit.
     */
    private static List<GlobalEventOrder> findTransientGapsToIncludeInQuery(SubscriptionGapHandler gapHandler, AggregateType aggregateType, LongRange globalOrderRange) {
        synchronized (gapHandler) {
            return gapHandler.findTransientGapsToIncludeInQuery(aggregateType, globalOrderRange);
        }
    }

    /**
     * See {@link #findTransientGapsToIncludeInQuery(SubscriptionGapHandler, AggregateType, LongRange)}
     */
    private static GapReconciliation reconcileGaps(SubscriptionGapHandler gapHandler,
                                                   AggregateType aggregateType,
                                                   LongRange globalOrderRange,
                                                   List<PersistedEvent> persistedEvents,
                                                   List<GlobalEventOrder> transientGapsIncludedInQuery) {
        synchronized (gapHandler) {
            return gapHandler.reconcileGapsAndReport(aggregateType, globalOrderRange, persistedEvents, transientGapsIncludedInQuery);
        }
    }

    /**
     * @return the gap fills one subscription hands on and its subscriber has not acknowledged yet - when it acknowledges,
     * and there is a gap handler; otherwise none, and gap fills are resolved once they were handed on
     */
    private Optional<GapFillsAwaitingAcknowledgement> awaitingAcknowledgement(Optional<SubscriberAcknowledgement> acknowledgement,
                                                                              Optional<SubscriptionGapHandler> subscriptionGapHandler,
                                                                              AggregateType aggregateType,
                                                                              String eventStreamLogName) {
        if (acknowledgement.isEmpty() || subscriptionGapHandler.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new GapFillsAwaitingAcknowledgement(subscriptionGapHandler.get(),
                                                               aggregateType,
                                                               unitOfWorkFactory,
                                                               eventStoreSubscriptionObserver,
                                                               eventStreamLogName));
    }

    /**
     * The listener to register with the subscriber's acknowledgement - also without a gap handler (then there is nothing
     * to resolve), so {@link SubscriberAcknowledgement#isHonoured()} tells the subscriber that handing on does not
     * resolve anything here. See {@link AcknowledgementRegistrations} for how long the registration lives.
     */
    private static Consumer<List<PersistedEvent>> acknowledgementListener(Optional<GapFillsAwaitingAcknowledgement> awaitingAcknowledgement,
                                                                          Optional<GapMiddlesAwaitedInMemory> gapMiddlesAwaitedInMemory) {
        return awaitingAcknowledgement.<Consumer<List<PersistedEvent>>>map(awaiting -> events -> {
                                          awaiting.acknowledged(events);
                                          // A middle fill among them is no longer owed - also if the subscribe that handed it on has ended since
                                          gapMiddlesAwaitedInMemory.ifPresent(middles -> middles.handled(events));
                                      })
                                      .orElse(events -> {
                                      });
    }

    /**
     * Resolve the gaps the published {@code gapFills} fill - for a subscriber that does not acknowledge what it handled -
     * in a unit of work of their own (joining the caller's, if there is one), with
     * {@link SubscriptionGapHandler#resolveFilledGaps}. Only polls that published a gap fill do this, once per poll. A
     * failure is logged and leaves the gaps open, so a later poll delivers those events again rather than losing them.
     */
    private void resolveGapsFilledByPublishedEvents(SubscriptionGapHandler gapHandler,
                                                    SubscriberId subscriberId,
                                                    AggregateType aggregateType,
                                                    List<PersistedEvent> gapFills,
                                                    String eventStreamLogName) {
        if (gapFills.isEmpty()) {
            return;
        }
        try {
            var outcome = unitOfWorkFactory.withUnitOfWork(unitOfWork -> {
                synchronized (gapHandler) {
                    return gapHandler.resolveFilledGaps(aggregateType, gapFills);
                }
            });
            // After its unit of work committed (unless it joined the caller's), as for every other gap reconciliation.
            // Not a reconciledGaps pass: those stay one per poll
            if (!outcome.isEmpty()) {
                eventStoreSubscriptionObserver.gapReconciliationOutcome(subscriberId, aggregateType, outcome);
            }
        } catch (RuntimeException e) {
            log.warn(msg("[{}] Could not resolve the gaps filled by the published event(s) {} - they stay transient gaps, so a later poll delivers those events again",
                         eventStreamLogName,
                         gapFills.stream().map(PersistedEvent::globalEventOrder).toList()),
                     e);
        }
    }

    /**
     * @param tenantFilter see {@link #tenantFilter}
     */
    private static List<PersistedEvent> eventsBelongingToTenant(List<PersistedEvent> events, Optional<Predicate<PersistedEvent>> tenantFilter) {
        return tenantFilter.map(belongsToTenant -> events.stream()
                                                         .filter(belongsToTenant)
                                                         .toList())
                           .orElse(events);
    }

    /**
     * The in-memory equivalent of the SQL tenant filter {@code (tenant IS NULL OR tenant = :tenant)}: an event without a
     * tenant belongs to every tenant, and no tenant filter keeps every event. Tenants are compared the way the SQL does -
     * by their {@link TenantSerializer#serialize serialized form} under the aggregate type's {@link TenantSerializer} - so
     * this keeps exactly the events whose payload the SQL ({@code onlyLoadPayloadIfEventBelongsToTenant}) did not omit,
     * also for a serializer whose form differs from {@link Object#toString()}.
     * <p>
     * Built once per poll rather than per event: the serializer and the wanted tenant's serialized form are the same for
     * every event the poll read.
     *
     * @return whether an event belongs to {@code onlyIncludeEventIfItBelongsToTenant}; empty when there is no tenant
     * filter, so every event belongs
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Optional<Predicate<PersistedEvent>> tenantFilter(AggregateType aggregateType, Optional<Tenant> onlyIncludeEventIfItBelongsToTenant) {
        return onlyIncludeEventIfItBelongsToTenant.map(tenant -> {
            TenantSerializer tenantSerializer = persistenceStrategy.getAggregateEventStreamConfiguration(aggregateType).tenantSerializer;
            var               wantedTenant    = tenantSerializer.serialize(tenant);
            return event -> event.tenant()
                                 .map(eventTenant -> Objects.equals(tenantSerializer.serialize(eventTenant), wantedTenant))
                                 .orElse(true);
        });
    }

    /**
     * @return the global order right after the highest one among {@code events}, or {@link Long#MIN_VALUE} when there are none
     */
    private static long nextGlobalOrderAfter(List<PersistedEvent> events) {
        return events.stream()
                     .mapToLong(event -> event.globalEventOrder().longValue() + 1L)
                     .max()
                     .orElse(Long.MIN_VALUE);
    }
}
