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

package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.EventStoreEventBus;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.jdbi.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.PersistableEventEnricher;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.types.LongRange;
import org.slf4j.*;
import reactor.core.publisher.Flux;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.EventStoreInterceptorChain.newInterceptorChainForOperation;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;
import static dk.trustworks.essentials.shared.interceptor.DefaultInterceptorChain.sortInterceptorsByOrder;

/**
 * The {@link EventStore} adapter of the new engine: appends and stream loads of the existing
 * aggregate repositories, deciders and snapshot repositories go to an {@link EventLog}, in the caller's unit of work.
 * <p>
 * <b>Appends</b> run the {@link EventStoreInterceptor} chain, map each event with the {@link PersistableEventMapper} (and
 * any {@link PersistableEventEnricher}s), serialize payload and metadata with the configuration's
 * {@link JSONEventSerializer} and append them with {@link EventLog#append(Connection, AppendRequest)} on the unit of
 * work's connection, with the stream's expected last event order: the one given, otherwise the stream's last event
 * order as read in the unit of work (as the old engine does). A lost optimistic check
 * ({@link StreamAppendConflictException}) is thrown as {@link OptimisticAppendToStreamException} with the old engine's
 * message, and consumes no {@code global_order} (I10; see docs/design/event-log.md §11). The persisted events are
 * registered on the unit of work, so its
 * {@link EventStoreEventBus} publishes them at {@code BeforeCommit}/{@code AfterCommit} (and {@code AfterRollback}), and
 * the engine's commit callback is registered so the event log's readers are woken after commit only (tier 2, I7).
 * <p>
 * <b>Loads</b> run the interceptor chain and read with the engine's reads on the unit of work's connection. A stream
 * returned by {@link #loadEventsByGlobalOrder(LoadEventsByGlobalOrder)} must be closed before the unit of work ends;
 * it reads committed rows only and is not a subscription.
 * <p>
 * <b>Not supported</b>: polling and subscriptions - every {@code pollEvents}/{@code unboundedPollForEvents}
 * overload throws {@link UnsupportedOperationException}; use {@link EventLog} subscriptions
 * ({@link SubscriptionDefinition}, or the {@link ViewProjection} and {@link EventReactor} processors).
 * {@link #findEvent(EventId)} throws as {@link EventStore}'s default does. The {@link EventStoreSubscriptionObserver} is
 * the no-op observer. Event, causation and correlation ids must be UUIDs.
 */
public final class EventLogEventStore implements ConfigurableEventStore<EventLogAggregateEventStreamConfiguration> {
    private static final Logger log = LoggerFactory.getLogger(EventLogEventStore.class);

    static final String POLLING_NOT_SUPPORTED = "The EventLogEventStore does not poll: use EventLog subscriptions (SubscriptionDefinition, ViewProjection or EventReactor) instead";

    private final EventLog                                                                  eventLog;
    private final EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                         unitOfWorkFactory;
    private final JSONEventSerializer                                                       jsonSerializer;
    private final PersistableEventMapper                                                    eventMapper;
    private final TenantSerializer<?>                                                       tenantSerializer;
    private final List<PersistableEventEnricher>                                            enrichers;
    private final EventStoreEventBus                                                        eventStoreEventBus;
    private final EventStoreSubscriptionObserver                                            observer = new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver();
    private final ConcurrentMap<AggregateType, EventLogAggregateEventStreamConfiguration> configurations = new ConcurrentHashMap<>();
    private final List<EventStoreInterceptor>                                               interceptors = new CopyOnWriteArrayList<>();
    private final Set<InMemoryProjector>                                                    inMemoryProjectors = new CopyOnWriteArraySet<>();
    private final ConcurrentMap<Class<?>, InMemoryProjector>                                inMemoryProjectorPerProjectionType = new ConcurrentHashMap<>();
    private final Set<ProcessorMutant>                                                    mutants;

    /**
     * An adapter without multi-tenancy, enrichers or a shared local event bus. See
     * {@link #EventLogEventStore(EventLog, EventStoreUnitOfWorkFactory, JSONEventSerializer, PersistableEventMapper, TenantSerializer, List, Optional)}
     */
    public EventLogEventStore(EventLog eventLog,
                              EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                              JSONEventSerializer jsonSerializer,
                              PersistableEventMapper eventMapper) {
        this(eventLog, unitOfWorkFactory, jsonSerializer, eventMapper, new TenantSerializer.NoSupportForMultiTenancySerializer(), List.of(), Optional.empty());
    }

    /**
     * @param eventLog          the started engine; aggregate types are registered with it by {@link #addAggregateEventStreamConfiguration}
     * @param unitOfWorkFactory the existing JDBI or Spring {@link EventStoreUnitOfWorkFactory}, over the engine's database
     * @param jsonSerializer    the serializer for payloads and metadata, from {@code EssentialsJSONEventSerializers}; used by
     *                          the configurations this adapter creates
     * @param eventMapper       maps the events of an {@link AppendToStream} to {@link PersistableEvent}s, as for the old engine
     * @param tenantSerializer  the tenant serializer of the configurations this adapter creates
     * @param enrichers         applied in order to each mapped event, as the old engine's persistence strategy does
     * @param localEventBus     the local event bus to use; empty for a new {@link EventStoreEventBus} on {@code unitOfWorkFactory}
     */
    @SuppressWarnings("unchecked")
    public EventLogEventStore(EventLog eventLog,
                              EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                              JSONEventSerializer jsonSerializer,
                              PersistableEventMapper eventMapper,
                              TenantSerializer<?> tenantSerializer,
                              List<PersistableEventEnricher> enrichers,
                              Optional<EventStoreEventBus> localEventBus) {
        this(eventLog, unitOfWorkFactory, jsonSerializer, eventMapper, tenantSerializer, enrichers, localEventBus, Set.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    EventLogEventStore(EventLog eventLog,
                       EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                       JSONEventSerializer jsonSerializer,
                       PersistableEventMapper eventMapper,
                       TenantSerializer<?> tenantSerializer,
                       List<PersistableEventEnricher> enrichers,
                       Optional<EventStoreEventBus> localEventBus,
                       Set<ProcessorMutant> mutants) {
        this.eventLog = requireNonNull(eventLog, "No eventLog provided");
        this.unitOfWorkFactory = (EventStoreUnitOfWorkFactory) requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.jsonSerializer = requireNonNull(jsonSerializer, "No jsonSerializer provided");
        this.eventMapper = requireNonNull(eventMapper, "No eventMapper provided");
        this.tenantSerializer = requireNonNull(tenantSerializer, "No tenantSerializer provided");
        this.enrichers = List.copyOf(requireNonNull(enrichers, "No enrichers provided"));
        this.eventStoreEventBus = requireNonNull(localEventBus, "No localEventBus option provided").orElseGet(() -> new EventStoreEventBus(unitOfWorkFactory));
        this.mutants = mutants.isEmpty() ? Set.of() : EnumSet.copyOf(mutants);
        jdbiOf(unitOfWorkFactory).ifPresent(EventLogEventStore::registerJdbiTypes);
    }

    /**
     * Register the Essentials event-store value types ({@link CorrelationId}, {@link EventId}, {@link EventOrder},
     * {@link GlobalEventOrder}, {@link EventRevision}) as JDBI arguments and column mappers, as the old engine's
     * persistence strategy does on construction. Components sharing the {@code Jdbi} rely on it - e.g.
     * {@code PostgresqlAggregateSnapshotRepository} binds an {@link EventOrder}. The constructor calls it for a
     * unit-of-work factory with a public {@code getJdbi()} (foundation's JDBI and Spring JDBI factories); call it yourself
     * for any other factory.
     */
    public static void registerJdbiTypes(org.jdbi.v3.core.Jdbi jdbi) {
        requireNonNull(jdbi, "No jdbi provided");
        jdbi.registerArgument(new CorrelationIdArgumentFactory());
        jdbi.registerColumnMapper(new CorrelationIdColumnMapper());
        jdbi.registerArgument(new EventIdArgumentFactory());
        jdbi.registerColumnMapper(new EventIdColumnMapper());
        jdbi.registerArgument(new EventOrderArgumentFactory());
        jdbi.registerColumnMapper(new EventOrderColumnMapper());
        jdbi.registerArgument(new GlobalEventOrderArgumentFactory());
        jdbi.registerColumnMapper(new GlobalEventOrderColumnMapper());
        jdbi.registerArgument(new EventRevisionArgumentFactory());
        jdbi.registerColumnMapper(new EventRevisionColumnMapper());
    }

    /** The factory's {@code Jdbi}, through its public {@code getJdbi()} if it has one; looked up reflectively so no Spring type is loaded */
    private static Optional<org.jdbi.v3.core.Jdbi> jdbiOf(Object unitOfWorkFactory) {
        try {
            var getter = unitOfWorkFactory.getClass().getMethod("getJdbi");
            return getter.getReturnType() == org.jdbi.v3.core.Jdbi.class ? Optional.ofNullable((org.jdbi.v3.core.Jdbi) getter.invoke(unitOfWorkFactory)) : Optional.empty();
        } catch (ReflectiveOperationException e) {
            return Optional.empty();
        }
    }

    /**
     * @return the engine this adapter appends to and reads from
     */
    public EventLog eventLog() {
        return eventLog;
    }

    // ---- configuration ----

    @Override
    public ConfigurableEventStore<EventLogAggregateEventStreamConfiguration> addAggregateEventStreamConfiguration(EventLogAggregateEventStreamConfiguration configuration) {
        requireNonNull(configuration, "No configuration provided");
        try {
            eventLog.registerAggregateType(configuration.aggregateType.toString());
        } catch (SQLException e) {
            throw new EventStoreException(msg("[{}] Failed to register the aggregate type with the event log", configuration.aggregateType), e);
        }
        configurations.put(configuration.aggregateType, configuration);
        return this;
    }

    @Override
    public ConfigurableEventStore<EventLogAggregateEventStreamConfiguration> addAggregateEventStreamConfiguration(AggregateType aggregateType, AggregateIdSerializer aggregateIdSerializer) {
        return addAggregateEventStreamConfiguration(new EventLogAggregateEventStreamConfiguration(aggregateType, jsonSerializer, aggregateIdSerializer, tenantSerializer));
    }

    @Override
    public ConfigurableEventStore<EventLogAggregateEventStreamConfiguration> addAggregateEventStreamConfiguration(AggregateType aggregateType, Class<?> aggregateIdType) {
        return addAggregateEventStreamConfiguration(aggregateType, AggregateIdSerializer.serializerFor(requireNonNull(aggregateIdType, "No aggregateIdType provided")));
    }

    @Override
    public EventLogAggregateEventStreamConfiguration getAggregateEventStreamConfiguration(AggregateType aggregateType) {
        return findAggregateEventStreamConfiguration(aggregateType)
                .orElseThrow(() -> new EventStoreException(msg("No AggregateEventStreamConfiguration registered for aggregate type '{}'", aggregateType)));
    }

    @Override
    public Optional<EventLogAggregateEventStreamConfiguration> findAggregateEventStreamConfiguration(AggregateType aggregateType) {
        return Optional.ofNullable(configurations.get(requireNonNull(aggregateType, "No aggregateType provided")));
    }

    @Override
    public ConfigurableEventStore<EventLogAggregateEventStreamConfiguration> addGenericInMemoryProjector(InMemoryProjector inMemoryProjector) {
        inMemoryProjectors.add(requireNonNull(inMemoryProjector, "No inMemoryProjection"));
        return this;
    }

    @Override
    public ConfigurableEventStore<EventLogAggregateEventStreamConfiguration> removeGenericInMemoryProjector(InMemoryProjector inMemoryProjector) {
        inMemoryProjectors.remove(requireNonNull(inMemoryProjector, "No inMemoryProjection"));
        return this;
    }

    @Override
    public ConfigurableEventStore<EventLogAggregateEventStreamConfiguration> addSpecificInMemoryProjector(Class<?> projectionType, InMemoryProjector inMemoryProjector) {
        inMemoryProjectorPerProjectionType.put(requireNonNull(projectionType, "No projectionType provided"), requireNonNull(inMemoryProjector, "No inMemoryProjection"));
        return this;
    }

    @Override
    public ConfigurableEventStore<EventLogAggregateEventStreamConfiguration> removeSpecificInMemoryProjector(Class<?> projectionType) {
        inMemoryProjectorPerProjectionType.remove(requireNonNull(projectionType, "No projectionType provided"));
        return this;
    }

    @Override
    public ConfigurableEventStore<EventLogAggregateEventStreamConfiguration> addEventStoreInterceptor(EventStoreInterceptor eventStoreInterceptor) {
        interceptors.add(requireNonNull(eventStoreInterceptor, "No eventStoreInterceptor provided"));
        sortInterceptorsByOrder(interceptors);
        return this;
    }

    @Override
    public ConfigurableEventStore<EventLogAggregateEventStreamConfiguration> removeEventStoreInterceptor(EventStoreInterceptor eventStoreInterceptor) {
        interceptors.remove(requireNonNull(eventStoreInterceptor, "No eventStoreInterceptor provided"));
        sortInterceptorsByOrder(interceptors);
        return this;
    }

    @Override
    public EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> getUnitOfWorkFactory() {
        return unitOfWorkFactory;
    }

    @Override
    public EventBus localEventBus() {
        return eventStoreEventBus;
    }

    @Override
    public EventStoreSubscriptionObserver getEventStoreSubscriptionObserver() {
        return observer;
    }

    @Override
    public List<EventStoreInterceptor> getEventStoreInterceptors() {
        return Collections.unmodifiableList(interceptors);
    }

    // ---- appends ----

    @Override
    public <ID> AggregateEventStream<ID> appendToStream(AppendToStream<ID> operation) {
        requireNonNull(operation, "You must supply an AppendToStream operation instance");
        var unitOfWork = unitOfWorkFactory.getRequiredUnitOfWork();
        return newInterceptorChainForOperation(operation,
                                               this,
                                               interceptors,
                                               (interceptor, chain) -> interceptor.intercept(operation, chain),
                                               () -> {
                                                   var stream = persist(unitOfWork, operation.aggregateType, operation.aggregateId,
                                                                        operation.getAppendEventsAfterEventOrder(), operation.getEventsToAppend());
                                                   unitOfWork.registerEventsPersisted(stream.eventList());
                                                   // Every append passes here once, as in PostgresqlEventStore (ES-4)
                                                   AppendedEvents.appended(stream.eventList());
                                                   return stream;
                                               })
                .proceed();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <ID> AggregateEventStream<ID> persist(EventStoreUnitOfWork unitOfWork, AggregateType aggregateType, ID aggregateId,
                                                  Optional<Long> appendEventsAfterEventOrder, List<?> eventsToAppend) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(aggregateId, "No aggregateId provided");
        requireNonNull(appendEventsAfterEventOrder, "No appendEventsAfterEventOrder provided");
        requireNonNull(eventsToAppend, "No eventsToAppend provided");
        var configuration = getAggregateEventStreamConfiguration(aggregateType);
        if (eventsToAppend.isEmpty()) {
            return AggregateEventStream.of(configuration, aggregateId, LongRange.only(EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED.longValue()), Stream.empty());
        }
        var type              = aggregateType.toString();
        var serializedId      = configuration.aggregateIdSerializer.serialize(aggregateId);
        var connection        = connectionOf(unitOfWork);
        var initialEventOrder = appendEventsAfterEventOrder.orElseGet(() -> lastEventOrder(connection, type, serializedId));
        var eventOrder        = initialEventOrder;
        var persistable       = new ArrayList<PersistableEvent>(eventsToAppend.size());
        for (var raw : eventsToAppend) {
            var event = eventMapper.map(aggregateId, configuration, raw, EventOrder.of(++eventOrder));
            for (var enricher : enrichers) {
                event = enricher.enrich(event);
                if (event == null) {
                    throw new IllegalStateException(msg("{} returned null", PersistableEventEnricher.class.getSimpleName()));
                }
            }
            persistable.add(event);
        }

        var tenant       = persistable.getFirst().tenant();
        var toAppend     = new ArrayList<EventToAppend>(persistable.size());
        var serialized   = new ArrayList<EventJSON>(persistable.size());
        var metadata     = new ArrayList<EventMetaDataJSON>(persistable.size());
        var timestamps   = new ArrayList<OffsetDateTime>(persistable.size());
        for (var event : persistable) {
            if (!event.tenant().equals(tenant)) {
                throw new IllegalArgumentException(msg("[{}] The events appended to aggregate '{}' in one operation must belong to the same tenant", aggregateType, aggregateId));
            }
            var eventJson    = configuration.jsonSerializer.serializeEvent(event.event());
            var metaDataJson = configuration.jsonSerializer.serializeMetaData(event.metaData());
            var timestamp    = event.timestamp().orElseGet(OffsetDateTime::now).withOffsetSameInstant(ZoneOffset.UTC);
            serialized.add(eventJson);
            metadata.add(metaDataJson);
            timestamps.add(timestamp);
            toAppend.add(new EventToAppend(UUID.fromString(event.eventId().toString()),
                                           causedBy(configuration, event),
                                           event.correlationId().map(id -> UUID.fromString(id.toString())).orElse(null),
                                           eventJson.getEventTypeOrNamePersistenceValue(),
                                           event.eventRevision().intValue(),
                                           timestamp.toInstant(),
                                           eventJson.getJson(),
                                           metaDataJson.getJson()));
        }
        var request = AppendRequest.afterEventOrder(type, serializedId, initialEventOrder, toAppend)
                                   .withTenant(tenant.map(t -> ((TenantSerializer) configuration.tenantSerializer).serialize(t)).orElse(null));
        AppendResult result;
        try {
            result = eventLog.append(connection, request);
        } catch (StreamAppendConflictException e) {
            if (mutants.contains(ProcessorMutant.CONFLICT_UNMAPPED)) {
                throw e;
            }
            var message = optimisticConcurrencyMessage(aggregateType, aggregateId, initialEventOrder, persistable.size());
            log.debug("{}\nCaused by {}: {}", message, e.getClass().getSimpleName(), e.getMessage());
            throw new OptimisticAppendToStreamException(message, e);
        } catch (SQLException e) {
            throw appendFailed(aggregateType, aggregateId, initialEventOrder, persistable.size(), e);
        }
        if (mutants.contains(ProcessorMutant.PUBLISH_BEFORE_COMMIT)) {
            eventLog.afterCommit(List.of(type));
        } else {
            EventLogCommits.afterCommit(unitOfWork, eventLog, type);
        }

        var persisted = new ArrayList<PersistedEvent>(persistable.size());
        for (var i = 0; i < persistable.size(); i++) {
            persisted.add(PersistedEvent.from(persistable.get(i), aggregateType, GlobalEventOrder.of(result.events().get(i).globalOrder()),
                                              serialized.get(i), metadata.get(i), timestamps.get(i)));
        }
        return AggregateEventStream.of(configuration, aggregateId, LongRange.between(initialEventOrder + 1, initialEventOrder + persistable.size()), persisted.stream());
    }

    /** A cause that is not a UUID is dropped with a warning, as the old engine does for its UUID column */
    private static UUID causedBy(EventLogAggregateEventStreamConfiguration configuration, PersistableEvent event) {
        if (event.causedByEventId().isEmpty()) {
            return null;
        }
        var cause = event.causedByEventId().get().toString();
        try {
            return UUID.fromString(cause);
        } catch (IllegalArgumentException e) {
            log.warn("[{}] Not recording causedByEventId '{}' for event '{}': the event log's caused_by_event_id column type is UUID and the cause is not a UUID",
                     configuration.aggregateType, cause, event.eventId());
            return null;
        }
    }

    /** The old engine's message, word for word (SeparateTablePerAggregateTypePersistenceStrategy.persist) */
    static String optimisticConcurrencyMessage(AggregateType aggregateType, Object aggregateId, long initialEventOrder, int numberOfEvents) {
        return msg("""
                   [{}] Optimistic-Concurrency-Exception: Failed to append {} Event(s), with Event-Order range [{}], to Stream related to Aggregate with ID '{}'.
                   This happens when multiple processes try to append events to the same aggregate (ID: '{}') simultaneously.
                   Another transaction has already appended events with the same event order(s) to this aggregate,
                   meaning the aggregate state changed between when you read it and when you tried to save your changes.
                   
                   Resolution options:
                   1) Retry the operation after loading the latest state of this aggregate, e.g. by catching {}
                   2) Implement domain-specific conflict resolution logic for this aggregate type
                   3) Consider using a different concurrency control strategy if conflicts are frequent
                   
                   See DEBUG log for technical details
                   """,
                   aggregateType,
                   numberOfEvents,
                   eventOrderRange(initialEventOrder, numberOfEvents),
                   aggregateId,
                   aggregateId,
                   OptimisticAppendToStreamException.class.getSimpleName());
    }

    private static String eventOrderRange(long initialEventOrder, int numberOfEvents) {
        var first = initialEventOrder + 1;
        return numberOfEvents == 1 ? String.valueOf(first) : (first + ".." + (initialEventOrder + numberOfEvents));
    }

    private static AppendToStreamException appendFailed(AggregateType aggregateType, Object aggregateId, long initialEventOrder, int numberOfEvents, SQLException e) {
        var message = msg("""
                          [{}] Append-To-Stream-Exception: Failed to append {} Event(s), with Event-Order range [{}], to Stream related to Aggregate with ID '{}'.
                          This is a general persistence failure not related to concurrent modifications.
                          Check database connectivity, schema issues, or data validation errors.
                          See ERROR log for technical details.
                          """,
                          aggregateType,
                          numberOfEvents,
                          eventOrderRange(initialEventOrder, numberOfEvents),
                          aggregateId);
        log.error(message, e);
        return new AppendToStreamException(message, new EventStoreException(e.getMessage(), e));
    }

    private long lastEventOrder(Connection connection, String aggregateType, String aggregateId) {
        try {
            return eventLog.findLastEvent(connection, aggregateType, aggregateId).map(StoredEvent::eventOrder).orElse(AppendRequest.NEW_STREAM);
        } catch (SQLException e) {
            throw new EventStoreException(msg("[{}] Failed to read the last event order of stream '{}'", aggregateType, aggregateId), e);
        }
    }

    private static Connection connectionOf(EventStoreUnitOfWork unitOfWork) {
        return unitOfWork.handle().getConnection();
    }

    // ---- loads ----

    @Override
    public <ID> Optional<PersistedEvent> loadLastPersistedEventRelatedTo(LoadLastPersistedEventRelatedTo<ID> operation) {
        requireNonNull(operation, "You must supply an LoadLastPersistedEventRelatedTo operation instance");
        return newInterceptorChainForOperation(operation,
                                               this,
                                               interceptors,
                                               (interceptor, chain) -> interceptor.intercept(operation, chain),
                                               () -> {
                                                   var configuration = getAggregateEventStreamConfiguration(operation.aggregateType);
                                                   return read(() -> eventLog.findLastEvent(connection(), operation.aggregateType.toString(),
                                                                                            configuration.aggregateIdSerializer.serialize(operation.aggregateId)))
                                                           .map(event -> toPersistedEvent(configuration, event));
                                               })
                .proceed();
    }

    @Override
    public Optional<PersistedEvent> loadEvent(LoadEvent operation) {
        requireNonNull(operation, "You must supply an LoadEvent operation instance");
        return newInterceptorChainForOperation(operation,
                                               this,
                                               interceptors,
                                               (interceptor, chain) -> interceptor.intercept(operation, chain),
                                               () -> {
                                                   var configuration = getAggregateEventStreamConfiguration(operation.aggregateType);
                                                   return read(() -> eventLog.findEvent(connection(), operation.aggregateType.toString(), UUID.fromString(operation.eventId.toString())))
                                                           .map(event -> toPersistedEvent(configuration, event));
                                               })
                .proceed();
    }

    @Override
    public List<PersistedEvent> loadEvents(LoadEvents operation) {
        requireNonNull(operation, "You must supply an LoadEvents operation instance");
        return newInterceptorChainForOperation(operation,
                                               this,
                                               interceptors,
                                               (interceptor, chain) -> interceptor.intercept(operation, chain),
                                               () -> {
                                                   var configuration = getAggregateEventStreamConfiguration(operation.aggregateType);
                                                   var ids           = operation.eventIds.stream().map(id -> UUID.fromString(id.toString())).toList();
                                                   return read(() -> eventLog.findEvents(connection(), operation.aggregateType.toString(), ids))
                                                           .stream()
                                                           .map(event -> toPersistedEvent(configuration, event))
                                                           .toList();
                                               })
                .proceed();
    }

    @Override
    public List<PersistedEvent> loadEventsCausedBy(LoadEventsCausedBy operation) {
        requireNonNull(operation, "You must supply a LoadEventsCausedBy operation instance");
        return newInterceptorChainForOperation(operation,
                                               this,
                                               interceptors,
                                               (interceptor, chain) -> interceptor.intercept(operation, chain),
                                               () -> {
                                                   UUID cause;
                                                   try {
                                                       cause = UUID.fromString(operation.causedByEventId.toString());
                                                   } catch (IllegalArgumentException e) {
                                                       // the engine's caused_by_event_id is a uuid: no event can have been caused by a non-UUID id
                                                       return List.<PersistedEvent>of();
                                                   }
                                                   return read(() -> eventLog.findCausedBy(connection(), cause))
                                                           .stream()
                                                           .map(event -> toPersistedEvent(getAggregateEventStreamConfiguration(AggregateType.of(event.aggregateType())), event))
                                                           .toList();
                                               })
                .proceed();
    }

    @Override
    public <ID> Optional<AggregateEventStream<ID>> fetchStream(FetchStream<ID> operation) {
        requireNonNull(operation, "You must supply an FetchStream operation instance");
        return newInterceptorChainForOperation(operation,
                                               this,
                                               interceptors,
                                               (interceptor, chain) -> interceptor.intercept(operation, chain),
                                               () -> {
                                                   // Decided after the interceptors ran: an interceptor can replace the range
                                                   var range = operation.getEventOrderRange();
                                                   if (range.isEmpty()) {
                                                       return Optional.<AggregateEventStream<ID>>empty();
                                                   }
                                                   var configuration = getAggregateEventStreamConfiguration(operation.aggregateType);
                                                   var tenant        = serializedTenant(configuration, operation.getTenant());
                                                   var events = read(() -> eventLog.loadStream(connection(), operation.aggregateType.toString(),
                                                                                               configuration.aggregateIdSerializer.serialize(operation.aggregateId),
                                                                                               range.fromInclusive, range.isClosedRange() ? range.toInclusive : Long.MAX_VALUE));
                                                   var persisted = events.stream()
                                                                         .filter(event -> tenant == null || event.tenant() == null || tenant.equals(event.tenant()))
                                                                         .map(event -> toPersistedEvent(configuration, event))
                                                                         .toList();
                                                   if (persisted.isEmpty()) {
                                                       return Optional.<AggregateEventStream<ID>>empty();
                                                   }
                                                   return Optional.of(AggregateEventStream.of(configuration, operation.aggregateId, range, persisted.stream()));
                                               })
                .proceed();
    }

    @Override
    public Stream<PersistedEvent> loadEventsByGlobalOrder(LoadEventsByGlobalOrder operation) {
        requireNonNull(operation, "You must supply an LoadEventsByGlobalOrder operation instance");
        return newInterceptorChainForOperation(operation,
                                               this,
                                               interceptors,
                                               (interceptor, chain) -> interceptor.intercept(operation, chain),
                                               () -> loadEventsByGlobalOrderFromEventLog(operation))
                .proceed();
    }

    private Stream<PersistedEvent> loadEventsByGlobalOrderFromEventLog(LoadEventsByGlobalOrder operation) {
        // Decided after the interceptors ran: an interceptor can replace the range or the additional orders
        var range      = operation.getGlobalEventOrderRange();
        var additional = operation.getIncludeAdditionalGlobalOrders() == null ? List.<GlobalEventOrder>of() : operation.getIncludeAdditionalGlobalOrders();
        if (range.isEmpty() && additional.isEmpty()) {
            return Stream.empty();
        }
        var configuration = getAggregateEventStreamConfiguration(operation.aggregateType);
        var onlyTenant    = serializedTenant(configuration, operation.getOnlyIncludeEventIfItBelongsToTenant());
        // the old engine's "omit other tenants' payloads" read: every event, other tenants' payload and metadata as {}
        var payloadTenant = onlyTenant == null ? serializedTenant(configuration, operation.getOnlyLoadPayloadIfEventBelongsToTenant()) : null;
        var read = new GlobalOrderRead(operation.aggregateType.toString(),
                                       range.fromInclusive,
                                       range.isEmpty() ? range.fromInclusive - 1 : (range.isClosedRange() ? range.toInclusive : Long.MAX_VALUE),
                                       additional.stream().map(GlobalEventOrder::longValue).toList(),
                                       Set.of(),
                                       onlyTenant,
                                       configuration.queryFetchSize);
        var events = read(() -> eventLog.loadByGlobalOrder(connection(), read));
        return events.map(event -> payloadTenant == null || event.tenant() == null || payloadTenant.equals(event.tenant())
                                   ? toPersistedEvent(configuration, event)
                                   : toPersistedEvent(configuration, withoutPayload(event)));
    }

    private static StoredEvent withoutPayload(StoredEvent event) {
        return new StoredEvent(event.aggregateType(), event.globalOrder(), event.eventOrder(), event.eventId(), event.causedByEventId(), event.correlationId(),
                               event.eventType(), event.eventRevision(), event.timestamp(), event.aggregateId(), "{}", "{}", event.tenant());
    }

    @Override
    public Optional<GlobalEventOrder> findHighestGlobalEventOrderPersisted(AggregateType aggregateType) {
        requireNonNull(aggregateType, "No aggregateType provided");
        getAggregateEventStreamConfiguration(aggregateType);
        var highest = read(() -> eventLog.findHighestGlobalOrder(connection(), aggregateType.toString()));
        return highest.isPresent() ? Optional.of(GlobalEventOrder.of(highest.getAsLong())) : Optional.empty();
    }

    @Override
    public Optional<GlobalEventOrder> findLowestGlobalEventOrderPersisted(AggregateType aggregateType) {
        requireNonNull(aggregateType, "No aggregateType provided");
        getAggregateEventStreamConfiguration(aggregateType);
        var lowest = read(() -> eventLog.findLowestGlobalOrder(connection(), aggregateType.toString()));
        return lowest.isPresent() ? Optional.of(GlobalEventOrder.of(lowest.getAsLong())) : Optional.empty();
    }

    @Override
    public <ID, PROJECTION> Optional<PROJECTION> inMemoryProjection(AggregateType aggregateType, ID aggregateId, Class<PROJECTION> projectionType) {
        requireNonNull(projectionType, "No projectionType provided");
        var projector = inMemoryProjectorPerProjectionType.computeIfAbsent(projectionType,
                                                                           type -> inMemoryProjectors.stream()
                                                                                                     .filter(candidate -> candidate.supports(projectionType))
                                                                                                     .findFirst()
                                                                                                     .orElseThrow(() -> new EventStoreException(msg("Couldn't find an {} that supports projection-type '{}'",
                                                                                                                                                    InMemoryProjector.class.getSimpleName(),
                                                                                                                                                    projectionType.getName()))));
        return inMemoryProjection(aggregateType, aggregateId, projectionType, projector);
    }

    @Override
    public <ID, PROJECTION> Optional<PROJECTION> inMemoryProjection(AggregateType aggregateType, ID aggregateId, Class<PROJECTION> projectionType, InMemoryProjector inMemoryProjector) {
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
        return inMemoryProjector.projectEvents(aggregateType, aggregateId, projectionType, this);
    }

    // ---- not supported: polling ----

    @Override
    public Flux<PersistedEvent> pollEvents(AggregateType aggregateType, long fromInclusiveGlobalOrder, Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                           Optional<Duration> pollingInterval, Optional<Tenant> onlyIncludeEventIfItBelongsToTenant, Optional<SubscriberId> subscriptionId,
                                           Optional<Function<String, EventStorePollingOptimizer>> eventStorePollingOptimizerFactory) {
        throw new UnsupportedOperationException(POLLING_NOT_SUPPORTED);
    }

    @Override
    public Flux<PersistedEvent> pollEvents(AggregateType aggregateType, long fromInclusiveGlobalOrder, Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                           Optional<Duration> pollingInterval, Optional<Tenant> onlyIncludeEventIfItBelongsToTenant, Optional<SubscriberId> subscriptionId,
                                           Optional<Function<String, EventStorePollingOptimizer>> eventStorePollingOptimizerFactory, SubscriberAcknowledgement acknowledgement) {
        throw new UnsupportedOperationException(POLLING_NOT_SUPPORTED);
    }

    @Override
    public Flux<PersistedEvent> unboundedPollForEvents(AggregateType aggregateType, long fromInclusiveGlobalOrder, Optional<Integer> loadEventsByGlobalOrderBatchSize,
                                                       Optional<Duration> pollingInterval, Optional<Tenant> onlyIncludeEventIfItBelongsToTenant, Optional<SubscriberId> subscriptionId) {
        throw new UnsupportedOperationException(POLLING_NOT_SUPPORTED);
    }

    // ---- mapping ----

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String serializedTenant(AggregateEventStreamConfiguration configuration, Optional<? extends Tenant> tenant) {
        return tenant == null || tenant.isEmpty() ? null : ((TenantSerializer) configuration.tenantSerializer).serialize(tenant.get());
    }

    /** {@link StoredEvent} to {@link PersistedEvent}, as the old engine's row mapper reads a row */
    @SuppressWarnings("unchecked")
    private static PersistedEvent toPersistedEvent(EventLogAggregateEventStreamConfiguration configuration, StoredEvent event) {
        var typeOrName = event.eventType();
        var eventJson = EventType.isSerializedEventType(typeOrName)
                        ? new EventJSON(configuration.jsonSerializer, EventType.of(typeOrName), event.payload())
                        : new EventJSON(configuration.jsonSerializer, EventName.of(typeOrName), event.payload());
        return PersistedEvent.from(EventId.of(event.eventId().toString()),
                                   configuration.aggregateType,
                                   configuration.aggregateIdSerializer.deserialize(event.aggregateId()),
                                   eventJson,
                                   EventOrder.of(event.eventOrder()),
                                   EventRevision.of(event.eventRevision()),
                                   GlobalEventOrder.of(event.globalOrder()),
                                   new EventMetaDataJSON(configuration.jsonSerializer, EventMetaData.class.getName(), event.metadata()),
                                   event.timestamp(),
                                   event.causedByEventId() == null ? Optional.empty() : Optional.of(EventId.of(event.causedByEventId().toString())),
                                   event.correlationId() == null ? Optional.empty() : Optional.of(CorrelationId.of(event.correlationId().toString())),
                                   (Optional<? extends Tenant>) configuration.tenantSerializer.deserialize(event.tenant()));
    }

    private Connection connection() {
        return connectionOf(unitOfWorkFactory.getRequiredUnitOfWork());
    }

    @FunctionalInterface
    private interface Read<T> {
        T run() throws SQLException;
    }

    private static <T> T read(Read<T> read) {
        try {
            return read.run();
        } catch (SQLException e) {
            throw new EventStoreException("Event log read failed: " + e.getMessage(), e);
        }
    }
}
